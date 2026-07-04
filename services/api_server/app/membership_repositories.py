from __future__ import annotations

import math
import uuid
from datetime import UTC, date, datetime
from typing import Any

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy import insert, update

from app.db import (
    connect,
    engine,
    membership_addons_table,
    membership_plans_table,
    user_memberships_table,
    user_monthly_entitlements_table,
    user_trial_entitlements_table,
)
from app import admin_repositories
from app.core.config import settings
from app.services import alipay

TRIAL_TRANSCRIPTION_MINUTES = 20
TRIAL_KNOWLEDGE_QA = 10


def current_period_month() -> str:
    return datetime.utcnow().strftime("%Y-%m")


def now_iso() -> str:
    return datetime.now(UTC).isoformat()


def _db_retry(operation):
    for attempt in range(3):
        try:
            return operation()
        except OperationalError:
            engine.dispose()
            if attempt >= 2:
                raise
    raise RuntimeError("数据库操作失败")


def get_user_membership_profile(user_id: str) -> dict[str, Any]:
    period = current_period_month()

    def operation():
        with connect() as conn:
            plan_rows = conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()
            plans = [_plan_view(dict(row._mapping)) for row in plan_rows]
            plans_by_id = {plan["id"]: plan for plan in plans}
            addon_rows = conn.execute(select(membership_addons_table).order_by(membership_addons_table.c.sort_order.asc())).fetchall()
            addons = [_addon_view(dict(row._mapping)) for row in addon_rows]
            membership_row = conn.execute(
                select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)
            ).fetchone()
            membership = dict(membership_row._mapping) if membership_row is not None else {}
            active = _membership_active(membership)
            membership_plan_id = str(membership.get("plan_id") or "") if active else ""
            if active and membership_plan_id in plans_by_id:
                _ensure_period_entitlement(conn, user_id, period, plans_by_id[membership_plan_id], now_iso())
            entitlement = _get_period_entitlement(conn, user_id, period)
            trial = _ensure_trial_entitlement(conn, user_id, now_iso())
            entitlement_active = bool(active and membership_plan_id)
            plan_id = str(entitlement.get("plan_id") or membership_plan_id or "") if entitlement_active else ""
            current_plan = plans_by_id.get(plan_id)
            frozen = admin_repositories.is_user_frozen(user_id)
            profile_active = bool(entitlement_active and current_plan is not None)
            membership_minutes_total = int(entitlement.get("transcription_minutes_total") or 0) if profile_active and entitlement else 0
            membership_minutes_used = int(entitlement.get("transcription_minutes_used") or 0) if profile_active and entitlement else 0
            membership_qa_total = int(entitlement.get("knowledge_qa_total") or 0) if profile_active and entitlement else 0
            membership_qa_used = int(entitlement.get("knowledge_qa_used") or 0) if profile_active and entitlement else 0
            return {
                "active": profile_active,
                "account_status": "frozen" if frozen else "normal",
                "plan_id": current_plan["id"] if current_plan else "none",
                "plan_name": current_plan["name"] if current_plan else "无套餐",
                "expires_at": _date_text(membership.get("expires_at")) if profile_active else None,
                "period_month": period,
                "transcription_minutes_total": membership_minutes_total + int(trial.get("transcription_minutes_total") or 0),
                "transcription_minutes_used": membership_minutes_used + int(trial.get("transcription_minutes_used") or 0),
                "knowledge_qa_total": membership_qa_total + int(trial.get("knowledge_qa_total") or 0),
                "knowledge_qa_used": membership_qa_used + int(trial.get("knowledge_qa_used") or 0),
                "payment_enabled": alipay.payment_enabled() and not frozen,
                "apple_iap_enabled": settings.apple_iap_enabled and not frozen,
                "plans": [plan for plan in plans if plan["enabled"]],
                "addons": [addon for addon in addons if addon["enabled"]],
            }

    return _db_retry(operation)


def require_transcription_quota(user_id: str) -> dict[str, int]:
    remaining = get_transcription_quota_remaining(user_id)
    if remaining <= 0:
        raise HTTPException(status_code=402, detail="额度已耗尽，请充值后继续享受权益")
    return {"remaining": remaining}


def get_transcription_quota_remaining(user_id: str) -> int:
    def operation():
        with connect() as conn:
            entitlement = _ensure_current_entitlement_for_usage(conn, user_id)
            trial = _ensure_trial_entitlement(conn, user_id, now_iso())
            monthly_remaining = int(entitlement.get("transcription_minutes_total") or 0) - int(entitlement.get("transcription_minutes_used") or 0)
            trial_remaining = int(trial.get("transcription_minutes_total") or 0) - int(trial.get("transcription_minutes_used") or 0)
            return max(0, monthly_remaining) + max(0, trial_remaining)

    return _db_retry(operation)


def consume_transcription_minutes(user_id: str, minutes: int, grace_minutes: int = 0) -> dict[str, int]:
    amount = max(1, int(math.ceil(float(minutes or 0))))
    grace = max(0, int(grace_minutes or 0))

    def operation():
        with connect() as conn:
            entitlement = _ensure_current_entitlement_for_usage(conn, user_id)
            trial = _ensure_trial_entitlement(conn, user_id, now_iso())
            used = int(entitlement.get("transcription_minutes_used") or 0)
            total = int(entitlement.get("transcription_minutes_total") or 0)
            trial_used = int(trial.get("transcription_minutes_used") or 0)
            trial_total = int(trial.get("transcription_minutes_total") or 0)
            monthly_remaining = max(0, total - used)
            trial_remaining = max(0, trial_total - trial_used)
            remaining = monthly_remaining + trial_remaining
            if remaining + grace < amount:
                raise HTTPException(status_code=402, detail=f"额度已耗尽，请充值后继续享受权益")
            monthly_consume = min(monthly_remaining, amount)
            if monthly_consume > 0 and entitlement.get("id"):
                conn.execute(
                    update(user_monthly_entitlements_table)
                    .where(user_monthly_entitlements_table.c.id == entitlement["id"])
                    .values(
                        transcription_minutes_used=used + monthly_consume,
                        updated_at=now_iso(),
                    )
                )
            trial_consume = min(trial_remaining, max(0, amount - monthly_consume))
            if trial_consume > 0:
                conn.execute(
                    update(user_trial_entitlements_table)
                    .where(user_trial_entitlements_table.c.user_id == user_id)
                    .values(
                        transcription_minutes_used=trial_used + trial_consume,
                        updated_at=now_iso(),
                    )
                )
            next_remaining = max(0, remaining - monthly_consume - trial_consume)
            return {
                "used": used + monthly_consume,
                "remaining": next_remaining,
                "grace_used": max(0, amount - max(0, remaining)),
            }

    return _db_retry(operation)


def require_knowledge_quota(user_id: str) -> dict[str, int]:
    def operation():
        with connect() as conn:
            entitlement = _ensure_current_entitlement_for_usage(conn, user_id)
            trial = _ensure_trial_entitlement(conn, user_id, now_iso())
            monthly_remaining = int(entitlement.get("knowledge_qa_total") or 0) - int(entitlement.get("knowledge_qa_used") or 0)
            trial_remaining = int(trial.get("knowledge_qa_total") or 0) - int(trial.get("knowledge_qa_used") or 0)
            return max(0, monthly_remaining) + max(0, trial_remaining)

    remaining = _db_retry(operation)
    if remaining <= 0:
        raise HTTPException(status_code=402, detail="额度已耗尽，请充值后继续享受权益")
    return {"remaining": remaining}


def consume_knowledge_qa(user_id: str, count: int = 1) -> dict[str, int]:
    amount = max(1, int(count or 1))

    def operation():
        with connect() as conn:
            entitlement = _ensure_current_entitlement_for_usage(conn, user_id)
            trial = _ensure_trial_entitlement(conn, user_id, now_iso())
            used = int(entitlement.get("knowledge_qa_used") or 0)
            total = int(entitlement.get("knowledge_qa_total") or 0)
            trial_used = int(trial.get("knowledge_qa_used") or 0)
            trial_total = int(trial.get("knowledge_qa_total") or 0)
            monthly_remaining = max(0, total - used)
            trial_remaining = max(0, trial_total - trial_used)
            remaining = monthly_remaining + trial_remaining
            if remaining < amount:
                raise HTTPException(status_code=402, detail="额度已耗尽，请充值后继续享受权益")
            monthly_consume = min(monthly_remaining, amount)
            if monthly_consume > 0 and entitlement.get("id"):
                conn.execute(
                    update(user_monthly_entitlements_table)
                    .where(user_monthly_entitlements_table.c.id == entitlement["id"])
                    .values(
                        knowledge_qa_used=used + monthly_consume,
                        updated_at=now_iso(),
                    )
                )
            trial_consume = min(trial_remaining, max(0, amount - monthly_consume))
            if trial_consume > 0:
                conn.execute(
                    update(user_trial_entitlements_table)
                    .where(user_trial_entitlements_table.c.user_id == user_id)
                    .values(
                        knowledge_qa_used=trial_used + trial_consume,
                        updated_at=now_iso(),
                    )
                )
            return {"used": used + monthly_consume, "remaining": remaining - monthly_consume - trial_consume}

    return _db_retry(operation)


def _load_current_entitlement_for_usage(user_id: str) -> dict[str, Any]:
    def operation():
        with connect() as conn:
            return _ensure_current_entitlement_for_usage(conn, user_id)

    return _db_retry(operation)


def _ensure_current_entitlement_for_usage(conn, user_id: str) -> dict[str, Any]:
    period = current_period_month()
    membership_row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    membership = dict(membership_row._mapping) if membership_row is not None else {}
    plan = None
    if _membership_active(membership) and membership.get("plan_id"):
        plan_row = conn.execute(select(membership_plans_table).where(membership_plans_table.c.id == str(membership["plan_id"]))).fetchone()
        if plan_row is not None:
            plan = _plan_view(dict(plan_row._mapping))
    if plan is not None:
        _ensure_period_entitlement(conn, user_id, period, plan, now_iso())
        return _get_period_entitlement(conn, user_id, period)
    entitlement = _get_period_entitlement(conn, user_id, period)
    if entitlement.get("plan_id"):
        entitlement = {
            **entitlement,
            "transcription_minutes_total": int(entitlement.get("transcription_minutes_extra") or 0),
            "knowledge_qa_total": int(entitlement.get("knowledge_qa_extra") or 0),
        }
    return entitlement


def _get_period_entitlement(conn, user_id: str, period: str) -> dict[str, Any]:
    row = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    return dict(row._mapping) if row is not None else {}


def _ensure_trial_entitlement(conn, user_id: str, timestamp: str) -> dict[str, Any]:
    row = conn.execute(
        select(user_trial_entitlements_table).where(user_trial_entitlements_table.c.user_id == user_id)
    ).fetchone()
    if row is not None:
        return dict(row._mapping)
    try:
        conn.execute(
            insert(user_trial_entitlements_table).values(
                user_id=user_id,
                transcription_minutes_total=TRIAL_TRANSCRIPTION_MINUTES,
                knowledge_qa_total=TRIAL_KNOWLEDGE_QA,
                transcription_minutes_used=0,
                knowledge_qa_used=0,
                created_at=timestamp,
                updated_at=timestamp,
            )
        )
    except IntegrityError:
        pass
    created = conn.execute(
        select(user_trial_entitlements_table).where(user_trial_entitlements_table.c.user_id == user_id)
    ).fetchone()
    return dict(created._mapping) if created is not None else {}


def _ensure_period_entitlement(conn, user_id: str, period: str, plan: dict[str, Any], timestamp: str) -> None:
    row = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    plan_minutes = int(plan.get("transcription_minutes") or 0)
    plan_qa = int(plan.get("knowledge_qa") or 0)
    if row is None:
        try:
            conn.execute(
                insert(user_monthly_entitlements_table).values(
                    id=f"entitlement-{uuid.uuid4().hex[:20]}",
                    user_id=user_id,
                    period_month=period,
                    plan_id=str(plan["id"]),
                    transcription_minutes_total=plan_minutes,
                    knowledge_qa_total=plan_qa,
                    transcription_minutes_extra=0,
                    knowledge_qa_extra=0,
                    transcription_minutes_used=0,
                    knowledge_qa_used=0,
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
        except IntegrityError:
            pass
        return
    data = dict(row._mapping)
    if data.get("plan_id") == plan["id"]:
        return
    extra_minutes = int(data.get("transcription_minutes_extra") or 0)
    extra_qa = int(data.get("knowledge_qa_extra") or 0)
    conn.execute(
        update(user_monthly_entitlements_table)
        .where(user_monthly_entitlements_table.c.id == data["id"])
        .values(
            plan_id=str(plan["id"]),
            transcription_minutes_total=plan_minutes + extra_minutes,
            knowledge_qa_total=plan_qa + extra_qa,
            updated_at=timestamp,
        )
    )


def _plan_view(plan: dict[str, Any]) -> dict[str, Any]:
    minutes = int(plan.get("transcription_minutes_monthly") or 0)
    price_cents = int(plan.get("price_cents") or 0)
    return {
        "id": str(plan.get("id") or ""),
        "name": str(plan.get("name") or ""),
        "price_cents": price_cents,
        "price": round(price_cents / 100, 2),
        "transcription_minutes": minutes,
        "hours": round(minutes / 60, 2),
        "knowledge_qa": int(plan.get("knowledge_qa_monthly") or 0),
        "enabled": bool(plan.get("enabled")),
        "sort_order": int(plan.get("sort_order") or 0),
        "apple_product_id": _apple_product_id("plan", str(plan.get("id") or "")),
    }


def _addon_view(addon: dict[str, Any]) -> dict[str, Any]:
    price_cents = int(addon.get("price_cents") or 0)
    return {
        "id": str(addon.get("id") or ""),
        "name": str(addon.get("name") or ""),
        "unit": str(addon.get("unit") or "hour"),
        "price_cents": price_cents,
        "price": round(price_cents / 100, 2),
        "enabled": bool(addon.get("enabled")),
        "sort_order": int(addon.get("sort_order") or 0),
        "apple_product_id": _apple_product_id("addon", str(addon.get("id") or "")),
    }


def _apple_product_id(product_type: str, product_id: str) -> str:
    prefix = str(settings.apple_product_prefix or "com.kunqiong.huiyi").strip().rstrip(".")
    clean_type = "addon" if product_type == "addon" else "plan"
    clean_id = str(product_id or "").strip().replace(" ", "_")
    return f"{prefix}.{clean_type}.{clean_id}"


def _membership_active(membership: dict[str, Any]) -> bool:
    expires = _parse_date(membership.get("expires_at"))
    if expires is not None:
        return expires >= date.today()
    return membership.get("member_status") == "active"


def _parse_date(value: Any) -> date | None:
    clean = str(value or "").strip()
    if not clean:
        return None
    try:
        return date.fromisoformat(clean[:10])
    except ValueError:
        return None


def _date_text(value: Any) -> str | None:
    parsed = _parse_date(value)
    return parsed.isoformat() if parsed else None
