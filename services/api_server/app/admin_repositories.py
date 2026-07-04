from __future__ import annotations

import time
import uuid
from datetime import UTC, date, datetime, timedelta
from typing import Any

from fastapi import HTTPException
from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.exc import OperationalError

from app.core.config import settings
from app.db import (
    admin_change_records_table,
    admin_users_table,
    announcements_table,
    apple_transactions_table,
    connect,
    engine,
    grant_batches_table,
    grant_items_table,
    membership_addons_table,
    membership_plans_table,
    orders_table,
    user_admin_states_table,
    user_memberships_table,
    user_monthly_entitlements_table,
    user_trial_entitlements_table,
    users_table,
)
from app.services.passwords import hash_password


DEFAULT_PLANS = [
    {
        "id": "basic",
        "name": "基础版",
        "price_cents": 1900,
        "transcription_minutes_monthly": 10 * 60,
        "knowledge_qa_monthly": 200,
        "enabled": True,
        "sort_order": 10,
    },
    {
        "id": "plus",
        "name": "进阶版",
        "price_cents": 3900,
        "transcription_minutes_monthly": 20 * 60,
        "knowledge_qa_monthly": 600,
        "enabled": True,
        "sort_order": 20,
    },
    {
        "id": "pro",
        "name": "专业版",
        "price_cents": 5900,
        "transcription_minutes_monthly": 40 * 60,
        "knowledge_qa_monthly": 1200,
        "enabled": True,
        "sort_order": 30,
    },
]

DEFAULT_ADDONS = [
    {
        "id": "transcription_hour",
        "name": "转写加量包",
        "unit": "hour",
        "price_cents": 200,
        "enabled": True,
        "sort_order": 10,
    }
]

DEFAULT_TRIAL_TRANSCRIPTION_MINUTES = 20
DEFAULT_TRIAL_KNOWLEDGE_QA = 10

ADMIN_SNAPSHOT_CACHE_TTL_SECONDS = 8
_admin_snapshot_cache: dict[str, Any] = {"expires_at": 0.0, "data": None}

ORDER_STATUS_LABELS = {
    "paid": "支付成功",
    "pending": "未支付",
    "failed": "支付失败",
    "refunded": "已退款",
}
ORDER_STATUS_VALUES = {label: value for value, label in ORDER_STATUS_LABELS.items()}
CHANNEL_LABELS = {
    "manual": "后台录入",
    "mock": "模拟支付",
    "wechat": "微信支付",
    "alipay": "支付宝",
    "apple": "Apple",
}
CHANNEL_VALUES = {label: value for value, label in CHANNEL_LABELS.items()}
ANNOUNCEMENT_STATUS_LABELS = {
    "draft": "草稿",
    "published": "已发布",
    "offline": "已下线",
}
ANNOUNCEMENT_STATUS_VALUES = {label: value for value, label in ANNOUNCEMENT_STATUS_LABELS.items()}
AUDIENCE_LABELS = {
    "all": "全部用户",
    "members": "会员用户",
    "non_members": "无会员用户",
}
AUDIENCE_VALUES = {label: value for value, label in AUDIENCE_LABELS.items()}


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
            time.sleep(0.8 * (attempt + 1))
    raise RuntimeError("数据库操作失败")


def _mapping(row) -> dict[str, Any]:
    return dict(row._mapping)


def _new_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:20]}"


def ensure_admin_defaults() -> None:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            existing_plan_ids = {
                str(row._mapping["id"])
                for row in conn.execute(select(membership_plans_table.c.id)).fetchall()
            }
            for plan in DEFAULT_PLANS:
                if plan["id"] in existing_plan_ids:
                    continue
                conn.execute(insert(membership_plans_table).values(**plan, created_at=timestamp, updated_at=timestamp))
            existing_addon_ids = {
                str(row._mapping["id"])
                for row in conn.execute(select(membership_addons_table.c.id)).fetchall()
            }
            for addon in DEFAULT_ADDONS:
                if addon["id"] in existing_addon_ids:
                    continue
                conn.execute(insert(membership_addons_table).values(**addon, created_at=timestamp, updated_at=timestamp))

            username = settings.admin_bootstrap_username
            password = settings.admin_bootstrap_password
            if username and password:
                row = conn.execute(select(admin_users_table).where(admin_users_table.c.username == username)).fetchone()
                values = {
                    "display_name": settings.admin_bootstrap_display_name,
                    "password_hash": hash_password(password),
                    "active": True,
                    "updated_at": timestamp,
                }
                if row is None:
                    conn.execute(
                        insert(admin_users_table).values(
                            id=_new_id("admin"),
                            username=username,
                            created_at=timestamp,
                            **values,
                        )
                    )
                else:
                    conn.execute(update(admin_users_table).where(admin_users_table.c.username == username).values(**values))

    _db_retry(operation)


def get_admin_by_username(username: str) -> dict[str, Any] | None:
    clean = username.strip()
    if not clean:
        return None

    def operation():
        with connect() as conn:
            row = conn.execute(select(admin_users_table).where(admin_users_table.c.username == clean)).fetchone()
            return _mapping(row) if row is not None else None

    return _db_retry(operation)


def get_admin_by_id(admin_id: str) -> dict[str, Any] | None:
    def operation():
        with connect() as conn:
            row = conn.execute(select(admin_users_table).where(admin_users_table.c.id == admin_id)).fetchone()
            return _mapping(row) if row is not None else None

    return _db_retry(operation)


def mark_admin_login(admin_id: str) -> None:
    timestamp = now_iso()
    _db_retry(lambda: _execute(update(admin_users_table).where(admin_users_table.c.id == admin_id).values(last_login_at=timestamp, updated_at=timestamp)))


def _execute(statement) -> None:
    with connect() as conn:
        conn.execute(statement)


def _invalidate_admin_snapshot_cache() -> None:
    _admin_snapshot_cache["expires_at"] = 0.0
    _admin_snapshot_cache["data"] = None


def list_plan_views() -> list[dict[str, Any]]:
    return [_plan_view(plan) for plan in list_plans()]


def list_addon_views() -> list[dict[str, Any]]:
    return [_addon_view(addon) for addon in list_addons()]


def list_plans() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()
            return [_plan_from_row(row) for row in rows]

    return _db_retry(operation)


def list_addons() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(membership_addons_table).order_by(membership_addons_table.c.sort_order.asc())).fetchall()
            return [_addon_from_row(row) for row in rows]

    return _db_retry(operation)


def get_addon(addon_id: str) -> dict[str, Any] | None:
    def operation():
        with connect() as conn:
            row = conn.execute(select(membership_addons_table).where(membership_addons_table.c.id == addon_id)).fetchone()
            return _addon_from_row(row) if row is not None else None

    return _db_retry(operation)


def get_plan(plan_id: str) -> dict[str, Any] | None:
    def operation():
        with connect() as conn:
            row = conn.execute(select(membership_plans_table).where(membership_plans_table.c.id == plan_id)).fetchone()
            return _plan_from_row(row) if row is not None else None

    return _db_retry(operation)


def update_plan(plan_id: str, price_cents: int, transcription_minutes: int, knowledge_qa: int, enabled: bool, admin_id: str) -> dict[str, Any]:
    current = get_plan(plan_id)
    if current is None:
        raise HTTPException(status_code=404, detail="套餐不存在")
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                update(membership_plans_table)
                .where(membership_plans_table.c.id == plan_id)
                .values(
                    price_cents=max(0, int(price_cents)),
                    transcription_minutes_monthly=max(0, int(transcription_minutes)),
                    knowledge_qa_monthly=max(0, int(knowledge_qa)),
                    enabled=bool(enabled),
                    updated_at=timestamp,
                )
            )
            row = conn.execute(select(membership_plans_table).where(membership_plans_table.c.id == plan_id)).fetchone()
            updated = _plan_from_row(row)
            _insert_change_record(
                conn,
                admin_id=admin_id,
                user_id=None,
                entity_type="plan",
                entity_id=plan_id,
                action_type="修改套餐定价",
                before_value=_plan_change_text(current),
                after_value=_plan_change_text(updated),
                note="修改已有套餐",
                created_at=timestamp,
            )
            return updated

    result = _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return _plan_view(result)


def update_addon(addon_id: str, price_cents: int, enabled: bool, admin_id: str) -> dict[str, Any]:
    current = get_addon(addon_id)
    if current is None:
        raise HTTPException(status_code=404, detail="加量包不存在")
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            conn.execute(
                update(membership_addons_table)
                .where(membership_addons_table.c.id == addon_id)
                .values(
                    price_cents=max(0, int(price_cents)),
                    enabled=bool(enabled),
                    updated_at=timestamp,
                )
            )
            row = conn.execute(select(membership_addons_table).where(membership_addons_table.c.id == addon_id)).fetchone()
            updated = _addon_from_row(row)
            _insert_change_record(
                conn,
                admin_id=admin_id,
                user_id=None,
                entity_type="addon",
                entity_id=addon_id,
                action_type="修改加量包定价",
                before_value=_addon_change_text(current),
                after_value=_addon_change_text(updated),
                note="修改已有加量包",
                created_at=timestamp,
            )
            return updated

    result = _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return _addon_view(result)


def list_users_for_admin() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            return _build_user_views(conn)

    return _db_retry(operation)


def get_user_detail(user_id: str) -> dict[str, Any]:
    def operation():
        with connect() as conn:
            user = _require_user(conn, user_id)
            detail = _build_user_detail(conn, user)
            return detail

    return _db_retry(operation)


def is_user_frozen(user_id: str) -> bool:
    def operation():
        with connect() as conn:
            row = conn.execute(select(user_admin_states_table.c.status).where(user_admin_states_table.c.user_id == user_id)).fetchone()
            return bool(row is not None and row._mapping["status"] == "frozen")

    return _db_retry(operation)


def set_user_frozen(user_id: str, frozen: bool, reason: str, note: str, admin_id: str) -> dict[str, Any]:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            user = _require_user(conn, user_id)
            state = conn.execute(select(user_admin_states_table).where(user_admin_states_table.c.user_id == user_id)).fetchone()
            before_status = "冻结" if state is not None and state._mapping["status"] == "frozen" else "正常"
            values = {
                "status": "frozen" if frozen else "normal",
                "freeze_reason": reason if frozen else None,
                "frozen_at": timestamp if frozen else None,
                "frozen_by_admin_id": admin_id if frozen else None,
                "updated_at": timestamp,
            }
            if state is None:
                conn.execute(insert(user_admin_states_table).values(user_id=user_id, **values))
            else:
                conn.execute(update(user_admin_states_table).where(user_admin_states_table.c.user_id == user_id).values(**values))
            after_status = "冻结" if frozen else "正常"
            _insert_change_record(
                conn,
                admin_id=admin_id,
                user_id=user_id,
                entity_type="user",
                entity_id=user_id,
                action_type="冻结用户" if frozen else "解除冻结",
                before_value=before_status,
                after_value=after_status,
                note=note or reason or "-",
                created_at=timestamp,
            )
            return user

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return get_user_detail(user_id)["user"]


def gift_user_benefit(user_id: str, member_days: int, transcription_minutes: int, knowledge_qa: int, note: str, admin_id: str) -> dict[str, Any]:
    member_days = max(0, int(member_days))
    transcription_minutes = max(0, int(transcription_minutes))
    knowledge_qa = max(0, int(knowledge_qa))
    timestamp = now_iso()
    period = current_period_month()

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            membership = _membership_for_user(conn, user_id)
            has_member_plan = _membership_has_active_plan(membership)
            effective_member_days = member_days if has_member_plan else 0
            if effective_member_days <= 0 and transcription_minutes <= 0 and knowledge_qa <= 0:
                if member_days > 0 and not has_member_plan:
                    raise HTTPException(status_code=422, detail="无套餐用户只能赠送额度")
                raise HTTPException(status_code=422, detail="请填写赠送内容")
            before = _user_brief_for_record(conn, user_id)
            if effective_member_days > 0:
                _extend_membership(conn, user_id, effective_member_days, timestamp)
            if transcription_minutes > 0 or knowledge_qa > 0:
                _add_user_quota_in_conn(conn, user_id, period, transcription_minutes, knowledge_qa, timestamp)
            after_parts = []
            if effective_member_days:
                after_parts.append(f"会员 +{effective_member_days}天")
            if transcription_minutes:
                after_parts.append(f"转写 +{_minutes_to_hours_text(transcription_minutes)}")
            if knowledge_qa:
                after_parts.append(f"问答 +{knowledge_qa}次")
            _insert_change_record(
                conn,
                admin_id=admin_id,
                user_id=user_id,
                entity_type="user",
                entity_id=user_id,
                action_type="赠送权益",
                before_value=before,
                after_value="，".join(after_parts),
                note=note or "人工赠送",
                created_at=timestamp,
            )

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return get_user_detail(user_id)["user"]


def change_user_month_plan(user_id: str, plan_id: str, admin_id: str, period_month: str | None = None) -> dict[str, Any]:
    clean_plan_id = str(plan_id or "").strip()
    clear_plan = clean_plan_id in {"", "none", "no_plan", "非会员", "无套餐"}
    plan = None if clear_plan else get_plan(clean_plan_id)
    if not clear_plan and plan is None:
        raise HTTPException(status_code=404, detail="套餐不存在")
    period = period_month or current_period_month()
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            before = _current_user_plan_name(conn, user_id, period)
            if clear_plan:
                _clear_month_plan_in_conn(conn, user_id, period, timestamp)
                _deactivate_membership(conn, user_id, timestamp)
                after = "无套餐"
            else:
                _set_month_plan_in_conn(conn, user_id, period, plan, timestamp)
                _extend_membership(conn, user_id, 30, timestamp, str(plan["id"]))
                after = str(plan["name"])
            _insert_change_record(
                conn,
                admin_id,
                user_id,
                "entitlement",
                f"{user_id}:{period}",
                "更改当月套餐",
                before,
                after,
                f"{period} 生效",
                timestamp,
            )

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return get_user_detail(user_id)["user"]


def list_orders() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(orders_table).order_by(orders_table.c.created_at.desc())).fetchall()
            data = {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)}
            return [_order_view(_mapping(row), data) for row in rows]

    return _db_retry(operation)


def list_user_alipay_orders(user_id: str, limit: int = 50) -> list[dict[str, Any]]:
    clean_limit = max(1, min(100, int(limit or 50)))

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            rows = conn.execute(
                select(orders_table)
                .where((orders_table.c.user_id == user_id) & (orders_table.c.channel == "alipay"))
                .order_by(orders_table.c.created_at.desc())
                .limit(clean_limit)
            ).fetchall()
            data = {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)}
            return [_order_view(_mapping(row), data) for row in rows]

    return _db_retry(operation)


def list_user_payment_orders(user_id: str, limit: int = 50) -> list[dict[str, Any]]:
    clean_limit = max(1, min(100, int(limit or 50)))

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            rows = conn.execute(
                select(orders_table)
                .where(orders_table.c.user_id == user_id)
                .order_by(orders_table.c.created_at.desc())
                .limit(clean_limit)
            ).fetchall()
            data = {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)}
            return [_order_view(_mapping(row), data) for row in rows]

    return _db_retry(operation)


def get_order(order_id: str) -> dict[str, Any]:
    def operation():
        with connect() as conn:
            row = conn.execute(select(orders_table).where(orders_table.c.id == order_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="订单不存在")
            return _order_view(_mapping(row), {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)})

    return _db_retry(operation)


def create_alipay_plan_order(user_id: str, plan_id: str) -> dict[str, Any]:
    clean_plan_id = str(plan_id or "").strip()
    timestamp = now_iso()
    order_id = f"KQ{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}{uuid.uuid4().hex[:4].upper()}"

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            plan_row = conn.execute(select(membership_plans_table).where(membership_plans_table.c.id == clean_plan_id)).fetchone()
            plan = _plan_from_row(plan_row) if plan_row is not None else None
            if plan is None or not bool(plan.get("enabled")):
                raise HTTPException(status_code=404, detail="套餐不存在或已停用")
            order = {
                "id": order_id,
                "channel_order_no": "-",
                "user_id": user_id,
                "product_type": "plan",
                "plan_id": clean_plan_id,
                "addon_id": None,
                "transcription_minutes": 0,
                "amount_cents": int(plan["price_cents"]),
                "status": "pending",
                "channel": "alipay",
                "paid_at": None,
                "admin_note": "App 支付宝下单",
                "created_by_admin_id": None,
                "created_at": timestamp,
                "updated_at": timestamp,
            }
            conn.execute(
                insert(orders_table).values(**order)
            )
            _insert_change_record(conn, None, user_id, "order", order_id, "创建支付宝订单", "-", f"{plan['name']} / {_money_text(plan['price_cents'])}", "App 下单", timestamp)
            return _order_view(order, {"plans_by_id": {clean_plan_id: plan}, "addons_by_id": {}})

    order = _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return order


def create_alipay_addon_order(user_id: str, addon_id: str, quantity: int = 1) -> dict[str, Any]:
    clean_addon_id = str(addon_id or "").strip()
    clean_quantity = max(1, min(100, int(quantity or 1)))
    timestamp = now_iso()
    order_id = f"KQ{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}{uuid.uuid4().hex[:4].upper()}"

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            addon_row = conn.execute(select(membership_addons_table).where(membership_addons_table.c.id == clean_addon_id)).fetchone()
            addon = _addon_from_row(addon_row) if addon_row is not None else None
            if addon is None or not bool(addon.get("enabled")):
                raise HTTPException(status_code=404, detail="加量包不存在或已停用")
            if addon.get("unit") != "hour":
                raise HTTPException(status_code=422, detail="暂不支持该加量包单位")
            transcription_minutes = clean_quantity * 60
            amount_cents = int(addon["price_cents"]) * clean_quantity
            membership_row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
            membership = _mapping(membership_row) if membership_row is not None else {}
            if not _membership_active(membership):
                raise HTTPException(status_code=422, detail="加量包仅限会员购买，请先开通会员套餐")
            order = {
                "id": order_id,
                "channel_order_no": "-",
                "user_id": user_id,
                "product_type": "addon",
                "plan_id": None,
                "addon_id": clean_addon_id,
                "transcription_minutes": transcription_minutes,
                "amount_cents": amount_cents,
                "status": "pending",
                "channel": "alipay",
                "paid_at": None,
                "admin_note": f"App 支付宝下单：{addon['name']} x {clean_quantity}",
                "created_by_admin_id": None,
                "created_at": timestamp,
                "updated_at": timestamp,
            }
            conn.execute(
                insert(orders_table).values(**order)
            )
            _insert_change_record(conn, None, user_id, "order", order_id, "创建支付宝订单", "-", f"{addon['name']} / {_money_text(amount_cents)}", "App 下单", timestamp)
            return _order_view(order, {"plans_by_id": {}, "addons_by_id": {clean_addon_id: addon}})

    order = _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return order


def mark_alipay_order_paid(order_id: str, trade_no: str, paid_amount_cents: int, paid_at: str | None = None) -> dict[str, Any]:
    timestamp = now_iso()
    paid_time = _normalize_datetime_to_iso(paid_at) if paid_at else timestamp

    def operation():
        with connect() as conn:
            row = conn.execute(select(orders_table).where(orders_table.c.id == order_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="订单不存在")
            order = _mapping(row)
            if order.get("channel") != "alipay":
                raise HTTPException(status_code=422, detail="订单渠道不匹配")
            expected_amount = int(order.get("amount_cents") or 0)
            if int(paid_amount_cents) != expected_amount:
                raise HTTPException(status_code=422, detail="订单金额不匹配")
            product_type = str(order.get("product_type") or "plan")
            plan = get_plan(str(order.get("plan_id") or "")) if product_type == "plan" else None
            addon = get_addon(str(order.get("addon_id") or "")) if product_type == "addon" else None
            if product_type == "plan" and plan is None:
                raise HTTPException(status_code=404, detail="套餐不存在")
            if product_type == "addon" and addon is None:
                raise HTTPException(status_code=404, detail="加量包不存在")
            if order.get("status") != "paid":
                conn.execute(
                    update(orders_table)
                    .where(orders_table.c.id == order_id)
                    .values(
                        channel_order_no=trade_no or order.get("channel_order_no") or "-",
                        status="paid",
                        paid_at=paid_time,
                        updated_at=timestamp,
                    )
                )
                if product_type == "plan":
                    _apply_paid_plan_in_conn(conn, str(order["user_id"]), plan, timestamp)
                    product_text = f"{plan['name']} / {_money_text(expected_amount)}"
                else:
                    _apply_paid_addon_in_conn(conn, str(order["user_id"]), int(order.get("transcription_minutes") or 0), timestamp)
                    product_text = f"{addon['name']} / {_minutes_to_hours_text(int(order.get('transcription_minutes') or 0))} / {_money_text(expected_amount)}"
                _insert_change_record(
                    conn,
                    None,
                    str(order["user_id"]),
                    "order",
                    order_id,
                    "支付宝支付成功",
                    ORDER_STATUS_LABELS.get(str(order.get("status")), str(order.get("status") or "")),
                    product_text,
                    trade_no or "支付宝异步通知",
                    timestamp,
                )

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return get_order(order_id)


def confirm_apple_transaction(
    user_id: str,
    product_id: str,
    transaction_id: str,
    original_transaction_id: str | None,
    environment: str | None,
    purchase_date_ms: int | None,
    signed_transaction_info: str,
) -> dict[str, Any]:
    clean_product_id = str(product_id or "").strip()
    clean_transaction_id = str(transaction_id or "").strip()
    clean_original_id = str(original_transaction_id or "").strip()
    clean_environment = str(environment or "").strip()
    clean_signed_info = str(signed_transaction_info or "").strip()
    timestamp = now_iso()
    order_id = f"KQ{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}{uuid.uuid4().hex[:4].upper()}"

    if not clean_product_id or not clean_transaction_id or not clean_signed_info:
        raise HTTPException(status_code=422, detail="Apple 支付凭证不完整")

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            existing_tx = conn.execute(
                select(apple_transactions_table).where(apple_transactions_table.c.transaction_id == clean_transaction_id)
            ).fetchone()
            if existing_tx is not None:
                existing = _mapping(existing_tx)
                if str(existing.get("user_id")) != user_id:
                    raise HTTPException(status_code=403, detail="Apple 交易归属不匹配")
                existing_order_id = str(existing.get("order_id") or "")
                if existing_order_id:
                    existing_order_row = conn.execute(select(orders_table).where(orders_table.c.id == existing_order_id)).fetchone()
                    if existing_order_row is None:
                        raise HTTPException(status_code=409, detail="Apple 交易已记录，但订单记录缺失")
                    return _order_view(
                        _mapping(existing_order_row),
                        {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)},
                    )

            product = _apple_product_for_id(conn, clean_product_id)
            if product is None:
                raise HTTPException(status_code=404, detail="Apple 商品未配置或已停用")
            product_type = str(product["type"])
            plan = product.get("plan")
            addon = product.get("addon")
            transcription_minutes = 0
            amount_cents = 0
            plan_id = None
            addon_id = None
            product_name = ""
            if product_type == "plan" and plan is not None:
                plan_id = str(plan["id"])
                amount_cents = int(plan["price_cents"])
                product_name = str(plan["name"])
            elif product_type == "addon" and addon is not None:
                membership = _membership_for_user(conn, user_id)
                if not _membership_active(membership):
                    raise HTTPException(status_code=422, detail="加量包仅限会员购买，请先开通会员套餐")
                if addon.get("unit") != "hour":
                    raise HTTPException(status_code=422, detail="暂不支持该加量包单位")
                addon_id = str(addon["id"])
                transcription_minutes = 60
                amount_cents = int(addon["price_cents"])
                product_name = str(addon["name"])
            else:
                raise HTTPException(status_code=404, detail="Apple 商品未配置或已停用")

            order = {
                "id": order_id,
                "channel_order_no": clean_transaction_id,
                "user_id": user_id,
                "product_type": product_type,
                "plan_id": plan_id,
                "addon_id": addon_id,
                "transcription_minutes": transcription_minutes,
                "amount_cents": amount_cents,
                "status": "paid",
                "channel": "apple",
                "paid_at": timestamp,
                "admin_note": "iOS Apple IAP",
                "created_by_admin_id": None,
                "created_at": timestamp,
                "updated_at": timestamp,
            }
            conn.execute(insert(orders_table).values(**order))
            conn.execute(
                insert(apple_transactions_table).values(
                    id=uuid.uuid4().hex,
                    user_id=user_id,
                    order_id=order_id,
                    product_id=clean_product_id,
                    transaction_id=clean_transaction_id,
                    original_transaction_id=clean_original_id or None,
                    environment=clean_environment or None,
                    purchase_date_ms=int(purchase_date_ms or 0) if purchase_date_ms is not None else None,
                    signed_transaction_info=clean_signed_info,
                    status="verified",
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
            if product_type == "plan":
                _apply_paid_plan_in_conn(conn, user_id, plan, timestamp)
                product_text = f"{product_name} / {_money_text(amount_cents)}"
            else:
                _apply_paid_addon_in_conn(conn, user_id, transcription_minutes, timestamp)
                product_text = f"{product_name} / {_minutes_to_hours_text(transcription_minutes)} / {_money_text(amount_cents)}"
            _insert_change_record(
                conn,
                None,
                user_id,
                "order",
                order_id,
                "Apple IAP 支付成功",
                "-",
                product_text,
                clean_transaction_id,
                timestamp,
            )
            return _order_view(order, {"plans_by_id": {plan_id: plan} if plan_id else {}, "addons_by_id": {addon_id: addon} if addon_id else {}})

    order = _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    return order


def create_manual_order(user_id: str, plan_id: str, amount_cents: int, status: str, channel: str, paid_at: str | None, note: str, admin_id: str) -> dict[str, Any]:
    plan = get_plan(plan_id)
    if plan is None:
        raise HTTPException(status_code=404, detail="套餐不存在")
    timestamp = now_iso()
    order_status = _order_status_value(status)
    order_channel = _channel_value(channel)
    order_id = f"KQ{datetime.now(UTC).strftime('%Y%m%d%H%M%S')}{uuid.uuid4().hex[:4].upper()}"

    def operation():
        with connect() as conn:
            _require_user(conn, user_id)
            conn.execute(
                insert(orders_table).values(
                    id=order_id,
                    channel_order_no="-",
                    user_id=user_id,
                    product_type="plan",
                    plan_id=plan_id,
                    addon_id=None,
                    transcription_minutes=0,
                    amount_cents=max(0, int(amount_cents)),
                    status=order_status,
                    channel=order_channel,
                    paid_at=_normalize_datetime_to_iso(paid_at) if paid_at else (timestamp if order_status == "paid" else None),
                    admin_note=note,
                    created_by_admin_id=admin_id,
                    created_at=timestamp,
                    updated_at=timestamp,
                )
            )
            if order_status == "paid":
                _apply_paid_plan_in_conn(conn, user_id, plan, timestamp)
            _insert_change_record(conn, admin_id, user_id, "order", order_id, "录入订单", "-", f"{plan['name']} / {_money_text(amount_cents)}", note or "后台录入", timestamp)

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    def fetch_created():
        with connect() as conn:
            row = conn.execute(select(orders_table).where(orders_table.c.id == order_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="订单不存在")
            return _order_view(_mapping(row), {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)})

    return _db_retry(fetch_created)


def preview_dispatch(payload: dict[str, Any]) -> dict[str, Any]:
    data = _load_dispatch_data_for_payload(payload)
    recipients = _dispatch_recipients(data, payload)
    return {
        "scopeLabel": _dispatch_scope_label(data, payload),
        "items": recipients,
        "total": len(recipients),
    }


def create_grant_batch(payload: dict[str, Any], admin_id: str) -> dict[str, Any]:
    member_days = max(0, int(payload.get("memberDays") or payload.get("member_days") or 0))
    transcription_minutes = max(0, int(payload.get("transcriptionMinutes") or payload.get("transcription_minutes") or 0))
    knowledge_qa = max(0, int(payload.get("knowledgeQa") or payload.get("knowledge_qa") or 0))
    data = _load_dispatch_data_for_payload(payload)
    recipients = _dispatch_recipients(data, payload)
    if not recipients:
        raise HTTPException(status_code=422, detail="没有符合条件的发放对象")
    can_grant_member_days = _dispatch_can_grant_member_days(recipients)
    if member_days > 0 and not can_grant_member_days:
        raise HTTPException(status_code=409, detail="发放对象已变化，当前名单包含无套餐用户，不能发放会员天数。请返回重新确认名单和发放内容。")
    effective_member_days = member_days
    if effective_member_days <= 0 and transcription_minutes <= 0 and knowledge_qa <= 0:
        raise HTTPException(status_code=422, detail="请填写发放内容")
    timestamp = now_iso()
    batch_id = _new_id("grant")
    scope_label = _dispatch_scope_label(data, payload)
    reason = str(payload.get("reason") or "人工发放")
    grant_type = str(payload.get("type") or payload.get("grantType") or "benefit")
    period = current_period_month()

    def operation():
        with connect() as conn:
            conn.execute(
                insert(grant_batches_table).values(
                    id=batch_id,
                    grant_type=grant_type,
                    audience_mode=str(payload.get("audienceMode") or "currentPlan"),
                    plan_id=payload.get("planId"),
                    start_date=payload.get("startDate"),
                    end_date=payload.get("endDate"),
                    before_date=payload.get("beforeDate"),
                    status_filter=payload.get("statusFilter"),
                    keyword=payload.get("keyword"),
                    scope_label=scope_label,
                    recipient_count=len(recipients),
                    member_days=effective_member_days,
                    transcription_minutes=transcription_minutes,
                    knowledge_qa_count=knowledge_qa,
                    reason=reason,
                    status="completed",
                    created_by_admin_id=admin_id,
                    created_at=timestamp,
                )
            )
            for item in recipients:
                user_id = item["id"]
                if effective_member_days:
                    _extend_membership(conn, user_id, effective_member_days, timestamp)
                if transcription_minutes or knowledge_qa:
                    _add_user_quota_in_conn(conn, user_id, period, transcription_minutes, knowledge_qa, timestamp)
                conn.execute(
                    insert(grant_items_table).values(
                        id=_new_id("grant-item"),
                        batch_id=batch_id,
                        user_id=user_id,
                        member_days=effective_member_days,
                        transcription_minutes=transcription_minutes,
                        knowledge_qa_count=knowledge_qa,
                        status="completed",
                        created_at=timestamp,
                    )
                )
                _insert_change_record(
                    conn,
                    admin_id,
                    user_id,
                    "grant",
                    batch_id,
                    "统一发放会员" if effective_member_days and not (transcription_minutes or knowledge_qa) else "统一发放权益",
                    "-",
                    _grant_content_text(effective_member_days, transcription_minutes, knowledge_qa),
                    reason,
                    timestamp,
                )

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    def fetch_created():
        with connect() as conn:
            row = conn.execute(select(grant_batches_table).where(grant_batches_table.c.id == batch_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="发放记录不存在")
            return _grant_batch_view(_mapping(row))

    return _db_retry(fetch_created)


def list_grant_batches() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(grant_batches_table).order_by(grant_batches_table.c.created_at.desc())).fetchall()
            return [_grant_batch_view(_mapping(row)) for row in rows]

    return _db_retry(operation)


def list_announcements() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(announcements_table).order_by(announcements_table.c.updated_at.desc())).fetchall()
            return [_announcement_view(_mapping(row)) for row in rows]

    return _db_retry(operation)


def save_announcement(payload: dict[str, Any], admin_id: str) -> dict[str, Any]:
    title = str(payload.get("title") or "").strip()
    content = str(payload.get("content") or "").strip()
    if not title:
        raise HTTPException(status_code=422, detail="请填写公告标题")
    if not content:
        raise HTTPException(status_code=422, detail="请填写公告内容")
    announcement_id = str(payload.get("id") or _new_id("announcement"))
    status = _announcement_status_value(str(payload.get("status") or "draft"))
    audience = _audience_value(str(payload.get("audience") or "all"))
    timestamp = now_iso()
    publish_at = _normalize_datetime_to_iso(payload.get("publishAt") or payload.get("publish_at")) if payload.get("publishAt") or payload.get("publish_at") else None
    if status == "published" and not publish_at:
        publish_at = timestamp

    def operation():
        with connect() as conn:
            row = conn.execute(select(announcements_table).where(announcements_table.c.id == announcement_id)).fetchone()
            target_count = _announcement_target_count(conn, audience) if status == "published" else 0
            values = {
                "title": title,
                "content": content,
                "audience": audience,
                "status": status,
                "pinned": bool(payload.get("pinned")),
                "publish_at": publish_at,
                "expire_at": _normalize_date_to_iso(payload.get("expireAt") or payload.get("expire_at")) if payload.get("expireAt") or payload.get("expire_at") else None,
                "target_count": target_count,
                "read_count": min(int(payload.get("readCount") or payload.get("read_count") or 0), target_count),
                "created_by_admin_id": admin_id,
                "updated_at": timestamp,
            }
            before = "-" if row is None else _announcement_record_text(_mapping(row))
            if row is None:
                conn.execute(insert(announcements_table).values(id=announcement_id, created_at=timestamp, **values))
            else:
                conn.execute(update(announcements_table).where(announcements_table.c.id == announcement_id).values(**values))
            after = f"{title} / {ANNOUNCEMENT_STATUS_LABELS[status]}"
            _insert_change_record(conn, admin_id, None, "announcement", announcement_id, "保存公告", before, after, "-", timestamp)

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    def fetch_created():
        with connect() as conn:
            row = conn.execute(select(announcements_table).where(announcements_table.c.id == announcement_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="公告不存在")
            return _announcement_view(_mapping(row))

    return _db_retry(fetch_created)


def toggle_announcement(announcement_id: str, admin_id: str) -> dict[str, Any]:
    timestamp = now_iso()

    def operation():
        with connect() as conn:
            row = conn.execute(select(announcements_table).where(announcements_table.c.id == announcement_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="公告不存在")
            data = _mapping(row)
            next_status = "offline" if data["status"] == "published" else "published"
            target_count = _announcement_target_count(conn, str(data.get("audience") or "all")) if next_status == "published" else int(data.get("target_count") or 0)
            conn.execute(
                update(announcements_table)
                .where(announcements_table.c.id == announcement_id)
                .values(
                    status=next_status,
                    publish_at=data.get("publish_at") or timestamp if next_status == "published" else data.get("publish_at"),
                    target_count=target_count,
                    updated_at=timestamp,
                )
            )
            _insert_change_record(
                conn,
                admin_id,
                None,
                "announcement",
                announcement_id,
                "发布公告" if next_status == "published" else "下线公告",
                ANNOUNCEMENT_STATUS_LABELS.get(str(data.get("status")), str(data.get("status"))),
                ANNOUNCEMENT_STATUS_LABELS[next_status],
                str(data.get("title") or ""),
                timestamp,
            )

    _db_retry(operation)
    _invalidate_admin_snapshot_cache()
    def fetch_created():
        with connect() as conn:
            row = conn.execute(select(announcements_table).where(announcements_table.c.id == announcement_id)).fetchone()
            if row is None:
                raise HTTPException(status_code=404, detail="公告不存在")
            return _announcement_view(_mapping(row))

    return _db_retry(fetch_created)


def list_change_records() -> list[dict[str, Any]]:
    def operation():
        with connect() as conn:
            rows = conn.execute(select(admin_change_records_table).order_by(admin_change_records_table.c.created_at.desc())).fetchall()
            return [_change_view(_mapping(row), {}) for row in rows]

    return _db_retry(operation)


def admin_bootstrap_payload() -> dict[str, Any]:
    data = _load_admin_snapshot(minimal=True)
    return {
        "plans": [_plan_view(plan) for plan in data["plans"]],
        "addons": list_addon_views(),
        "users": [_user_view(user, data) for user in data["users"]],
        "dataStages": _default_data_stages(),
        "orderRecords": [],
        "paymentRecords": [],
        "dispatchBatches": [],
        "announcements": [],
        "changeRecords": [],
    }


def current_period_month() -> str:
    return datetime.now(UTC).strftime("%Y-%m")


def _load_admin_snapshot(minimal: bool = False) -> dict[str, Any]:
    cached = _admin_snapshot_cache.get("data")
    if cached is not None and time.monotonic() < float(_admin_snapshot_cache.get("expires_at") or 0):
        if minimal:
            return {
                "users": cached["users"],
                "plans": cached["plans"],
                "plans_by_id": cached["plans_by_id"],
                "states": cached["states"],
                "memberships": cached["memberships"],
                "entitlements": cached["entitlements"],
                "entitlements_by_user_period": cached["entitlements_by_user_period"],
                "trial_entitlements": cached.get("trial_entitlements", {}),
                "paid_amounts": cached.get("paid_amounts", {}),
            }
        return cached

    def operation():
        with connect() as conn:
            users = [_mapping(row) for row in conn.execute(select(users_table).order_by(users_table.c.created_at.desc())).fetchall()]
            plans = [_plan_from_row(row) for row in conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()]
            states = {row._mapping["user_id"]: _mapping(row) for row in conn.execute(select(user_admin_states_table)).fetchall()}
            memberships = {row._mapping["user_id"]: _mapping(row) for row in conn.execute(select(user_memberships_table)).fetchall()}
            period = current_period_month()
            entitlements = [
                _mapping(row)
                for row in conn.execute(
                    select(user_monthly_entitlements_table).where(user_monthly_entitlements_table.c.period_month == period)
                ).fetchall()
            ]
            trial_entitlements = {
                row._mapping["user_id"]: _mapping(row)
                for row in conn.execute(select(user_trial_entitlements_table)).fetchall()
            }
            paid_amount_rows = conn.execute(
                select(
                    orders_table.c.user_id,
                    func.sum(orders_table.c.amount_cents).label("paid_cents"),
                )
                .where(orders_table.c.status == "paid")
                .group_by(orders_table.c.user_id)
            ).fetchall()
            paid_amounts = {str(row._mapping["user_id"]): int(row._mapping["paid_cents"] or 0) / 100 for row in paid_amount_rows}
            return {
                "users": users,
                "plans": plans,
                "plans_by_id": {plan["id"]: plan for plan in plans},
                "states": states,
                "memberships": memberships,
                "entitlements": entitlements,
                "entitlements_by_user_period": {(item["user_id"], item["period_month"]): item for item in entitlements},
                "trial_entitlements": trial_entitlements,
                "paid_amounts": paid_amounts,
            }

    data = _db_retry(operation)
    _admin_snapshot_cache["data"] = data
    _admin_snapshot_cache["expires_at"] = time.monotonic() + ADMIN_SNAPSHOT_CACHE_TTL_SECONDS
    return data


def _load_dispatch_data() -> dict[str, Any]:
    return _load_dispatch_data_for_payload({})


def _load_dispatch_data_for_payload(payload: dict[str, Any]) -> dict[str, Any]:
    mode = str(payload.get("audienceMode") or payload.get("audience_mode") or "currentPlan")
    manual_ids = list(dict.fromkeys(str(user_id) for user_id in payload.get("userIds", []) if str(user_id).strip()))
    needs_orders = mode in {"periodPurchase", "beforePurchase"}

    def operation():
        with connect() as conn:
            if mode == "currentList" and manual_ids:
                users = [
                    _mapping(row)
                    for row in conn.execute(
                        select(users_table).where(users_table.c.id.in_(manual_ids)).order_by(users_table.c.created_at.desc())
                    ).fetchall()
                ]
            else:
                users = [_mapping(row) for row in conn.execute(select(users_table).order_by(users_table.c.created_at.desc())).fetchall()]
            data = _build_user_context(conn, users)
            data["users"] = users
            if needs_orders:
                data["orders"] = [
                    _mapping(row)
                    for row in conn.execute(
                        select(orders_table)
                        .where((orders_table.c.status == "paid") & (orders_table.c.product_type == "plan"))
                        .order_by(orders_table.c.paid_at.desc(), orders_table.c.created_at.desc())
                    ).fetchall()
                ]
            else:
                data["orders"] = []
            return data

    return _db_retry(operation)


def _require_user(conn, user_id: str) -> dict[str, Any]:
    row = conn.execute(select(users_table).where(users_table.c.id == user_id)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    return _mapping(row)


def _plan_from_row(row) -> dict[str, Any]:
    data = _mapping(row)
    return {
        "id": data["id"],
        "name": data["name"],
        "price_cents": int(data.get("price_cents") or 0),
        "price": round(int(data.get("price_cents") or 0) / 100, 2),
        "transcription_minutes": int(data.get("transcription_minutes_monthly") or 0),
        "hours": _minutes_to_hours(int(data.get("transcription_minutes_monthly") or 0)),
        "qa": int(data.get("knowledge_qa_monthly") or 0),
        "enabled": bool(data.get("enabled")),
        "sort_order": int(data.get("sort_order") or 0),
        "created_at": data.get("created_at"),
        "updated_at": data.get("updated_at"),
    }


def _plan_view(plan: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": plan["id"],
        "name": plan["name"],
        "price": plan["price"],
        "priceCents": plan["price_cents"],
        "price_cents": plan["price_cents"],
        "hours": plan["hours"],
        "transcriptionMinutes": plan["transcription_minutes"],
        "transcription_minutes": plan["transcription_minutes"],
        "qa": plan["qa"],
        "knowledge_qa": plan["qa"],
        "enabled": bool(plan["enabled"]),
        "apple_product_id": _apple_product_id("plan", str(plan["id"])),
    }


def _addon_from_row(row) -> dict[str, Any]:
    data = _mapping(row)
    return {
        "id": data["id"],
        "name": data["name"],
        "unit": data.get("unit") or "hour",
        "price_cents": int(data.get("price_cents") or 0),
        "price": round(int(data.get("price_cents") or 0) / 100, 2),
        "enabled": bool(data.get("enabled")),
        "sort_order": int(data.get("sort_order") or 0),
        "created_at": data.get("created_at"),
        "updated_at": data.get("updated_at"),
    }


def _addon_view(addon: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": addon["id"],
        "name": addon["name"],
        "unit": addon["unit"],
        "price": addon["price"],
        "priceCents": addon["price_cents"],
        "price_cents": addon["price_cents"],
        "enabled": bool(addon["enabled"]),
        "apple_product_id": _apple_product_id("addon", str(addon["id"])),
    }


def _apple_product_id(product_type: str, product_id: str) -> str:
    prefix = str(settings.apple_product_prefix or "com.kunqiong.huiyi").strip().rstrip(".")
    clean_type = "addon" if product_type == "addon" else "plan"
    clean_id = str(product_id or "").strip().replace(" ", "_")
    return f"{prefix}.{clean_type}.{clean_id}"


def _apple_product_for_id(conn, product_id: str) -> dict[str, Any] | None:
    plans = [_plan_from_row(row) for row in conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()]
    for plan in plans:
        if bool(plan.get("enabled")) and _apple_product_id("plan", str(plan["id"])) == product_id:
            return {"type": "plan", "plan": plan}
    addons = [_addon_from_row(row) for row in conn.execute(select(membership_addons_table).order_by(membership_addons_table.c.sort_order.asc())).fetchall()]
    for addon in addons:
        if bool(addon.get("enabled")) and _apple_product_id("addon", str(addon["id"])) == product_id:
            return {"type": "addon", "addon": addon}
    return None


def _user_view(user: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
    user_id = str(user["id"])
    state = data["states"].get(user_id) or {}
    membership = data["memberships"].get(user_id) or {}
    period = str(data.get("period") or current_period_month())
    entitlement = data["entitlements_by_user_period"].get((user_id, period))
    trial = data.get("trial_entitlements", {}).get(user_id) or {}
    raw_member_active = _membership_active(membership)
    plan_id = entitlement.get("plan_id") if raw_member_active and entitlement and entitlement.get("plan_id") else None
    if not plan_id and raw_member_active:
        plan_id = membership.get("plan_id")
    plan_id = str(plan_id or "none")
    month_plan_name = data["plans_by_id"].get(plan_id, {}).get("name") if plan_id != "none" else "无套餐"
    member_active = raw_member_active and plan_id != "none"
    paid_amount = float(data.get("paid_amounts", {}).get(user_id, 0))
    monthly_minutes_total = int((entitlement or {}).get("transcription_minutes_total") or 0)
    monthly_qa_total = int((entitlement or {}).get("knowledge_qa_total") or 0)
    monthly_minutes_used = int((entitlement or {}).get("transcription_minutes_used") or 0)
    monthly_qa_used = int((entitlement or {}).get("knowledge_qa_used") or 0)
    trial_minutes_total = int(trial.get("transcription_minutes_total") if trial else DEFAULT_TRIAL_TRANSCRIPTION_MINUTES)
    trial_qa_total = int(trial.get("knowledge_qa_total") if trial else DEFAULT_TRIAL_KNOWLEDGE_QA)
    trial_minutes_used = int(trial.get("transcription_minutes_used") or 0)
    trial_qa_used = int(trial.get("knowledge_qa_used") or 0)
    actual_minutes_total = monthly_minutes_total + trial_minutes_total
    actual_qa_total = monthly_qa_total + trial_qa_total
    actual_minutes_used = monthly_minutes_used + trial_minutes_used
    actual_qa_used = monthly_qa_used + trial_qa_used
    actual_minutes_remaining = max(actual_minutes_total - actual_minutes_used, 0)
    actual_qa_remaining = max(actual_qa_total - actual_qa_used, 0)
    return {
        "id": user_id,
        "name": user.get("display_name") or user.get("username") or user_id,
        "phone": user.get("phone_e164") or user.get("username") or "",
        "createdAt": _date_label(user.get("created_at")),
        "status": "冻结" if state.get("status") == "frozen" else "正常",
        "member": "会员" if member_active else "无会员",
        "memberExpire": _date_label(membership.get("expires_at")) if member_active else "-",
        "monthPlanId": plan_id,
        "monthPlanName": month_plan_name or "无套餐",
        "paidAmount": paid_amount,
        "freezeReason": state.get("freeze_reason") or "",
        "transcriptionMinutesTotal": actual_minutes_total,
        "knowledgeQaTotal": actual_qa_total,
        "transcriptionMinutesUsed": actual_minutes_used,
        "knowledgeQaUsed": actual_qa_used,
        "monthlyTranscriptionMinutesTotal": monthly_minutes_total,
        "monthlyKnowledgeQaTotal": monthly_qa_total,
        "monthlyTranscriptionMinutesUsed": monthly_minutes_used,
        "monthlyKnowledgeQaUsed": monthly_qa_used,
        "trialTranscriptionMinutesTotal": trial_minutes_total,
        "trialKnowledgeQaTotal": trial_qa_total,
        "trialTranscriptionMinutesUsed": trial_minutes_used,
        "trialKnowledgeQaUsed": trial_qa_used,
        "actualTranscriptionMinutesTotal": actual_minutes_total,
        "actualKnowledgeQaTotal": actual_qa_total,
        "actualTranscriptionMinutesUsed": actual_minutes_used,
        "actualKnowledgeQaUsed": actual_qa_used,
        "actualTranscriptionMinutesRemaining": actual_minutes_remaining,
        "actualKnowledgeQaRemaining": actual_qa_remaining,
    }


def _order_view(order: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
    plan_id = str(order.get("plan_id") or "")
    plan = (data.get("plans_by_id") or {}).get(plan_id) or {}
    addon_id = str(order.get("addon_id") or "")
    addon = (data.get("addons_by_id") or {}).get(addon_id) or {}
    product_type = str(order.get("product_type") or "plan")
    product_name = plan.get("name") if product_type == "plan" else addon.get("name")
    return {
        "id": order["id"],
        "channelNo": order.get("channel_order_no") or "-",
        "createdAt": _datetime_label(order.get("created_at")) if order.get("created_at") else "-",
        "paidAt": _datetime_label(order.get("paid_at")) if order.get("paid_at") else "-",
        "updatedAt": _datetime_label(order.get("updated_at")) if order.get("updated_at") else "-",
        "date": _date_label(order.get("paid_at") or order.get("created_at")),
        "userId": order.get("user_id"),
        "productType": product_type,
        "planId": plan_id,
        "planName": plan.get("name") or plan_id or "-",
        "addonId": addon_id,
        "addonName": addon.get("name") or addon_id or "-",
        "productName": product_name or plan_id or addon_id or "-",
        "transcriptionMinutes": int(order.get("transcription_minutes") or 0),
        "amount": round(int(order.get("amount_cents") or 0) / 100, 2),
        "amountCents": int(order.get("amount_cents") or 0),
        "status": ORDER_STATUS_LABELS.get(str(order.get("status")), str(order.get("status") or "")),
        "channel": CHANNEL_LABELS.get(str(order.get("channel")), str(order.get("channel") or "")),
        "note": order.get("admin_note") or "",
    }


def _payment_view(order: dict[str, Any]) -> dict[str, Any]:
    return {
        "date": _date_label(order.get("paid_at") or order.get("created_at")),
        "userId": order.get("user_id"),
        "planId": order.get("plan_id"),
        "amount": round(int(order.get("amount_cents") or 0) / 100, 2),
    }


def _announcement_view(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": item["id"],
        "title": item.get("title") or "",
        "audience": AUDIENCE_LABELS.get(str(item.get("audience")), str(item.get("audience") or "")),
        "status": ANNOUNCEMENT_STATUS_LABELS.get(str(item.get("status")), str(item.get("status") or "")),
        "publishAt": _datetime_label(item.get("publish_at")) if item.get("publish_at") else "-",
        "expireAt": _date_label(item.get("expire_at")) if item.get("expire_at") else "-",
        "targetCount": int(item.get("target_count") or 0),
        "readCount": int(item.get("read_count") or 0),
        "pinned": bool(item.get("pinned")),
        "content": item.get("content") or "",
    }


def _change_view(record: dict[str, Any], data: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": record.get("id"),
        "time": _datetime_label(record.get("created_at")),
        "userId": record.get("user_id") or "-",
        "type": record.get("action_type") or "",
        "before": record.get("before_value") or "-",
        "after": record.get("after_value") or "-",
        "note": record.get("note") or "-",
    }


def _grant_batch_view(batch: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": batch["id"],
        "time": _datetime_label(batch.get("created_at")),
        "type": "权益发放",
        "scope": batch.get("scope_label") or "-",
        "count": int(batch.get("recipient_count") or 0),
        "content": _grant_content_text(int(batch.get("member_days") or 0), int(batch.get("transcription_minutes") or 0), int(batch.get("knowledge_qa_count") or 0)),
        "reason": batch.get("reason") or "-",
        "status": "已完成" if batch.get("status") == "completed" else str(batch.get("status") or ""),
    }


def _dispatch_recipients(data: dict[str, Any], payload: dict[str, Any]) -> list[dict[str, Any]]:
    mode = str(payload.get("audienceMode") or payload.get("audience_mode") or "currentPlan")
    plan_id = str(payload.get("planId") or payload.get("plan_id") or "")
    if plan_id in {"", "all", "全部"}:
        plan_id = ""
    if plan_id in {"none", "no_plan", "无套餐"} and mode in {"periodPurchase", "beforePurchase"}:
        return []
    keyword = str(payload.get("keyword") or "").strip().lower()
    status_filter = str(payload.get("statusFilter") or payload.get("status_filter") or "正常")
    sort_key = str(payload.get("sort") or "purchaseDesc")
    users = [_user_view(user, data) for user in data["users"]]
    if mode == "currentList":
        manual_ids = list(dict.fromkeys(str(user_id) for user_id in payload.get("userIds", []) if str(user_id).strip()))
        user_map = {user["id"]: user for user in users}
        return [user_map[user_id] for user_id in manual_ids if user_id in user_map]
    matched_ids: set[str] | None
    if mode == "currentPlan":
        if plan_id:
            matched_ids = {user["id"] for user in users if user.get("monthPlanId") == plan_id}
        else:
            matched_ids = {user["id"] for user in users if user.get("monthPlanId") not in {"", "none", "no_plan"}}
    elif mode in {"periodPurchase", "beforePurchase"}:
        start = str(payload.get("startDate") or payload.get("start_date") or "")
        end = str(payload.get("endDate") or payload.get("end_date") or "")
        before = str(payload.get("beforeDate") or payload.get("before_date") or "")
        matched_ids = set()
        for order in data.get("orders", []):
            if order.get("status") != "paid" or str(order.get("product_type") or "plan") != "plan":
                continue
            if plan_id and order.get("plan_id") != plan_id:
                continue
            if not plan_id and not order.get("plan_id"):
                continue
            paid_date = _date_label(order.get("paid_at") or order.get("created_at"))
            if mode == "periodPurchase" and not _date_in_range(paid_date, start, end):
                continue
            if mode == "beforePurchase" and before and paid_date >= before:
                continue
            matched_ids.add(str(order.get("user_id")))
    else:
        matched_ids = set()
    result = []
    for user in users:
        if matched_ids is not None and user["id"] not in matched_ids:
            continue
        if status_filter != "全部" and user["status"] != status_filter:
            continue
        haystack = " ".join([user["id"], user["name"], user["phone"]]).lower()
        if keyword and keyword not in haystack:
            continue
        result.append(user)
    return _sort_dispatch_recipients(result, data.get("orders", []), mode, plan_id, payload, sort_key)


def _sort_dispatch_recipients(users: list[dict[str, Any]], orders: list[dict[str, Any]], mode: str, plan_id: str, payload: dict[str, Any], sort_key: str) -> list[dict[str, Any]]:
    if mode == "currentList":
        return users

    def latest_paid_date(user_id: str) -> str:
        dates = []
        start = str(payload.get("startDate") or payload.get("start_date") or "")
        end = str(payload.get("endDate") or payload.get("end_date") or "")
        before = str(payload.get("beforeDate") or payload.get("before_date") or "")
        for order in orders:
            if str(order.get("user_id") or "") != user_id:
                continue
            if order.get("status") != "paid" or str(order.get("product_type") or "plan") != "plan":
                continue
            if plan_id and order.get("plan_id") != plan_id:
                continue
            if not plan_id and not order.get("plan_id"):
                continue
            paid_date = _date_label(order.get("paid_at") or order.get("created_at"))
            if mode == "periodPurchase" and not _date_in_range(paid_date, start, end):
                continue
            if mode == "beforePurchase" and before and paid_date >= before:
                continue
            dates.append(paid_date)
        return max(dates) if dates else str(next((user.get("createdAt") for user in users if user["id"] == user_id), ""))

    if sort_key == "purchaseAsc":
        return sorted(users, key=lambda user: (latest_paid_date(user["id"]), user.get("name") or ""))
    if sort_key == "createdDesc":
        return sorted(users, key=lambda user: (user.get("createdAt") or "", user.get("name") or ""), reverse=True)
    if sort_key == "createdAsc":
        return sorted(users, key=lambda user: (user.get("createdAt") or "", user.get("name") or ""))
    if sort_key == "nameAsc":
        return sorted(users, key=lambda user: (user.get("name") or "", latest_paid_date(user["id"])), reverse=False)
    return sorted(users, key=lambda user: (latest_paid_date(user["id"]), user.get("name") or ""), reverse=True)


def _dispatch_can_grant_member_days(recipients: list[dict[str, Any]]) -> bool:
    return bool(recipients) and all(
        item.get("member") == "会员" and str(item.get("monthPlanId") or "") not in {"", "none", "no_plan"}
        for item in recipients
    )


def _dispatch_scope_label(data: dict[str, Any], payload: dict[str, Any]) -> str:
    mode = str(payload.get("audienceMode") or "currentPlan")
    plan_id = str(payload.get("planId") or "")
    plan = data["plans_by_id"].get(plan_id, {})
    if plan_id in {"", "all", "全部"}:
        plan_name = "全部套餐"
    elif plan_id in {"none", "no_plan"}:
        plan_name = "无套餐"
    else:
        plan_name = plan.get("name") or "指定套餐"
    if mode == "periodPurchase":
        return f"{payload.get('startDate') or '-'} 至 {payload.get('endDate') or '-'} 购买{plan_name}"
    if mode == "beforePurchase":
        return f"{payload.get('beforeDate') or '-'} 前购买{plan_name}"
    if mode == "currentList":
        return "指定名单"
    return f"当前套餐为{plan_name}"


def _ensure_monthly_entitlement(conn, user_id: str, period_month: str, timestamp: str) -> None:
    row = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period_month)
        )
    ).fetchone()
    if row is not None:
        return
    conn.execute(
        insert(user_monthly_entitlements_table).values(
            id=_new_id("entitlement"),
            user_id=user_id,
            period_month=period_month,
            plan_id=None,
            transcription_minutes_total=0,
            knowledge_qa_total=0,
            transcription_minutes_extra=0,
            knowledge_qa_extra=0,
            transcription_minutes_used=0,
            knowledge_qa_used=0,
            created_at=timestamp,
            updated_at=timestamp,
        )
    )


def _set_month_plan_in_conn(conn, user_id: str, period: str, plan: dict[str, Any], timestamp: str) -> None:
    _ensure_monthly_entitlement(conn, user_id, period, timestamp)
    row = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    extra_minutes = int(row._mapping.get("transcription_minutes_extra") or 0) if row is not None else 0
    extra_qa = int(row._mapping.get("knowledge_qa_extra") or 0) if row is not None else 0
    conn.execute(
        update(user_monthly_entitlements_table)
        .where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
        .values(
            plan_id=plan["id"],
            transcription_minutes_total=int(plan["transcription_minutes"]) + extra_minutes,
            knowledge_qa_total=int(plan["qa"]) + extra_qa,
            updated_at=timestamp,
        )
    )


def _apply_paid_plan_in_conn(conn, user_id: str, plan: dict[str, Any], timestamp: str) -> None:
    _append_paid_plan_quota_in_conn(conn, user_id, current_period_month(), plan, timestamp)
    _refresh_membership_from_today(conn, user_id, 30, timestamp, str(plan["id"]))


def _append_paid_plan_quota_in_conn(conn, user_id: str, period: str, plan: dict[str, Any], timestamp: str) -> None:
    membership_row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    membership = _mapping(membership_row) if membership_row is not None else {}
    existing_plan_id = str(membership.get("plan_id") or "")
    existing_entitlement = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    if existing_entitlement is None and _membership_active(membership) and existing_plan_id:
        existing_plan_row = conn.execute(
            select(membership_plans_table).where(membership_plans_table.c.id == existing_plan_id)
        ).fetchone()
        if existing_plan_row is not None:
            _set_month_plan_in_conn(conn, user_id, period, _plan_from_row(existing_plan_row), timestamp)
    _ensure_monthly_entitlement(conn, user_id, period, timestamp)
    conn.execute(
        update(user_monthly_entitlements_table)
        .where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
        .values(
            plan_id=plan["id"],
            transcription_minutes_total=user_monthly_entitlements_table.c.transcription_minutes_total
            + int(plan["transcription_minutes"]),
            knowledge_qa_total=user_monthly_entitlements_table.c.knowledge_qa_total + int(plan["qa"]),
            updated_at=timestamp,
        )
    )


def _apply_paid_addon_in_conn(conn, user_id: str, transcription_minutes: int, timestamp: str) -> None:
    minutes = max(0, int(transcription_minutes or 0))
    if minutes <= 0:
        return
    period = current_period_month()
    _ensure_monthly_entitlement(conn, user_id, period, timestamp)
    conn.execute(
        update(user_monthly_entitlements_table)
        .where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
        .values(
            transcription_minutes_extra=user_monthly_entitlements_table.c.transcription_minutes_extra + minutes,
            transcription_minutes_total=user_monthly_entitlements_table.c.transcription_minutes_total + minutes,
            updated_at=timestamp,
        )
    )


def _membership_for_user(conn, user_id: str) -> dict[str, Any]:
    row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    return _mapping(row) if row is not None else {}


def _membership_has_active_plan(membership: dict[str, Any]) -> bool:
    return bool(_membership_active(membership) and str(membership.get("plan_id") or "").strip())


def _user_has_active_plan(conn, user_id: str) -> bool:
    return _membership_has_active_plan(_membership_for_user(conn, user_id))


def _ensure_trial_entitlement_in_conn(conn, user_id: str, timestamp: str) -> None:
    row = conn.execute(select(user_trial_entitlements_table).where(user_trial_entitlements_table.c.user_id == user_id)).fetchone()
    if row is not None:
        return
    conn.execute(
        insert(user_trial_entitlements_table).values(
            user_id=user_id,
            transcription_minutes_total=DEFAULT_TRIAL_TRANSCRIPTION_MINUTES,
            knowledge_qa_total=DEFAULT_TRIAL_KNOWLEDGE_QA,
            transcription_minutes_used=0,
            knowledge_qa_used=0,
            created_at=timestamp,
            updated_at=timestamp,
        )
    )


def _add_user_quota_in_conn(conn, user_id: str, period: str, transcription_minutes: int, knowledge_qa: int, timestamp: str) -> None:
    transcription_minutes = max(0, int(transcription_minutes or 0))
    knowledge_qa = max(0, int(knowledge_qa or 0))
    if transcription_minutes <= 0 and knowledge_qa <= 0:
        return
    if _user_has_active_plan(conn, user_id):
        _ensure_monthly_entitlement(conn, user_id, period, timestamp)
        conn.execute(
            update(user_monthly_entitlements_table)
            .where(
                (user_monthly_entitlements_table.c.user_id == user_id)
                & (user_monthly_entitlements_table.c.period_month == period)
            )
            .values(
                transcription_minutes_extra=user_monthly_entitlements_table.c.transcription_minutes_extra + transcription_minutes,
                knowledge_qa_extra=user_monthly_entitlements_table.c.knowledge_qa_extra + knowledge_qa,
                transcription_minutes_total=user_monthly_entitlements_table.c.transcription_minutes_total + transcription_minutes,
                knowledge_qa_total=user_monthly_entitlements_table.c.knowledge_qa_total + knowledge_qa,
                updated_at=timestamp,
            )
        )
        return
    _ensure_trial_entitlement_in_conn(conn, user_id, timestamp)
    conn.execute(
        update(user_trial_entitlements_table)
        .where(user_trial_entitlements_table.c.user_id == user_id)
        .values(
            transcription_minutes_total=user_trial_entitlements_table.c.transcription_minutes_total + transcription_minutes,
            knowledge_qa_total=user_trial_entitlements_table.c.knowledge_qa_total + knowledge_qa,
            updated_at=timestamp,
        )
    )


def _clear_month_plan_in_conn(conn, user_id: str, period: str, timestamp: str) -> None:
    _ensure_monthly_entitlement(conn, user_id, period, timestamp)
    row = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    data = _mapping(row) if row is not None else {}
    used_minutes = max(0, int(data.get("transcription_minutes_used") or 0))
    used_qa = max(0, int(data.get("knowledge_qa_used") or 0))
    conn.execute(
        update(user_monthly_entitlements_table)
        .where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
        .values(
            plan_id=None,
            transcription_minutes_total=used_minutes,
            knowledge_qa_total=used_qa,
            transcription_minutes_extra=0,
            knowledge_qa_extra=0,
            updated_at=timestamp,
        )
    )


def _extend_membership(conn, user_id: str, days: int, timestamp: str, plan_id: str | None = None) -> None:
    row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    expires_at = None if row is None else row._mapping.get("expires_at")
    base = _parse_date(expires_at) or date.today()
    if base < date.today():
        base = date.today()
    new_expiry = (base + timedelta(days=days)).isoformat()
    values = {
        "member_status": "active",
        "expires_at": _normalize_date_to_iso(new_expiry),
        "source": "admin",
        "updated_at": timestamp,
    }
    if plan_id is not None:
        values["plan_id"] = plan_id
    if row is None:
        conn.execute(insert(user_memberships_table).values(user_id=user_id, starts_at=timestamp, created_at=timestamp, **values))
    else:
        conn.execute(update(user_memberships_table).where(user_memberships_table.c.user_id == user_id).values(**values))


def _refresh_membership_from_today(conn, user_id: str, days: int, timestamp: str, plan_id: str | None = None) -> None:
    row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    new_expiry = (date.today() + timedelta(days=days)).isoformat()
    values = {
        "member_status": "active",
        "expires_at": _normalize_date_to_iso(new_expiry),
        "source": "payment",
        "updated_at": timestamp,
    }
    if plan_id is not None:
        values["plan_id"] = plan_id
    if row is None:
        conn.execute(insert(user_memberships_table).values(user_id=user_id, starts_at=timestamp, created_at=timestamp, **values))
    else:
        conn.execute(update(user_memberships_table).where(user_memberships_table.c.user_id == user_id).values(**values))


def _deactivate_membership(conn, user_id: str, timestamp: str) -> None:
    row = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    values = {
        "plan_id": None,
        "member_status": "none",
        "expires_at": None,
        "source": "admin",
        "updated_at": timestamp,
    }
    if row is None:
        conn.execute(
            insert(user_memberships_table).values(
                user_id=user_id,
                starts_at=None,
                created_at=timestamp,
                **values,
            )
        )
    else:
        conn.execute(update(user_memberships_table).where(user_memberships_table.c.user_id == user_id).values(**values))


def _insert_change_record(
    conn,
    admin_id: str | None,
    user_id: str | None,
    entity_type: str,
    entity_id: str | None,
    action_type: str,
    before_value: str | None,
    after_value: str | None,
    note: str | None,
    created_at: str,
) -> None:
    conn.execute(
        insert(admin_change_records_table).values(
            id=_new_id("change"),
            admin_id=admin_id,
            user_id=user_id,
            entity_type=entity_type,
            entity_id=entity_id,
            action_type=action_type,
            before_value=before_value,
            after_value=after_value,
            note=note,
            created_at=created_at,
        )
    )


def _announcement_target_count(conn, audience: str) -> int:
    if audience == "all":
        return int(conn.execute(select(func.count()).select_from(users_table)).scalar() or 0)
    user_ids = [str(row._mapping["id"]) for row in conn.execute(select(users_table.c.id)).fetchall()]
    if not user_ids:
        return 0
    memberships = {
        str(row._mapping["user_id"]): _mapping(row)
        for row in conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id.in_(user_ids))).fetchall()
    }
    count = sum(1 for user_id in user_ids if _membership_active(memberships.get(user_id) or {}))
    return count if audience == "members" else len(user_ids) - count


def _user_brief_for_record(conn, user_id: str) -> str:
    membership = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id == user_id)).fetchone()
    member = _mapping(membership) if membership is not None else {}
    return f"{'会员' if _membership_active(member) else '无会员'} / {_date_label(member.get('expires_at')) if member.get('expires_at') else '-'}"


def _current_user_plan_name(conn, user_id: str, period: str) -> str:
    entitlement = conn.execute(
        select(user_monthly_entitlements_table).where(
            (user_monthly_entitlements_table.c.user_id == user_id)
            & (user_monthly_entitlements_table.c.period_month == period)
        )
    ).fetchone()
    plan_id = entitlement._mapping.get("plan_id") if entitlement is not None else None
    if not plan_id:
        return "-"
    plan = conn.execute(select(membership_plans_table.c.name).where(membership_plans_table.c.id == plan_id)).fetchone()
    return str(plan._mapping["name"]) if plan is not None else str(plan_id)


def _membership_active(membership: dict[str, Any]) -> bool:
    expires = _parse_date(membership.get("expires_at"))
    if expires is not None:
        return expires >= date.today()
    return membership.get("member_status") == "active"


def _build_user_views(conn, user_id: str | None = None) -> list[dict[str, Any]]:
    user_rows = conn.execute(
        select(users_table).where(users_table.c.id == user_id).order_by(users_table.c.created_at.desc()) if user_id else select(users_table).order_by(users_table.c.created_at.desc())
    ).fetchall()
    users = [_mapping(row) for row in user_rows]
    return [_user_view(user, _build_user_context(conn, users)) for user in users]


def _build_user_detail(conn, user: dict[str, Any]) -> dict[str, Any]:
    context = _build_user_context(conn, [user])
    user_view = _user_view(user, context)
    product_data = {"plans_by_id": _plans_by_id_in_conn(conn), "addons_by_id": _addons_by_id_in_conn(conn)}
    changes = [
        _change_view(_mapping(row), {})
        for row in conn.execute(
            select(admin_change_records_table).where(admin_change_records_table.c.user_id == user["id"]).order_by(admin_change_records_table.c.created_at.desc())
        ).fetchall()
    ]
    orders = [
        _order_view(_mapping(row), product_data)
        for row in conn.execute(
            select(orders_table).where(orders_table.c.user_id == user["id"]).order_by(orders_table.c.created_at.desc())
        ).fetchall()
    ]
    return {"user": user_view, "changes": changes, "orders": orders}


def _build_user_context(conn, users: list[dict[str, Any]]) -> dict[str, Any]:
    user_ids = [str(user["id"]) for user in users]
    plans = [_plan_from_row(row) for row in conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()]
    states = {}
    memberships = {}
    entitlements = []
    trial_entitlements = {}
    paid_amounts = {}
    if user_ids:
        period = current_period_month()
        state_rows = conn.execute(select(user_admin_states_table).where(user_admin_states_table.c.user_id.in_(user_ids))).fetchall()
        membership_rows = conn.execute(select(user_memberships_table).where(user_memberships_table.c.user_id.in_(user_ids))).fetchall()
        entitlement_rows = conn.execute(
            select(user_monthly_entitlements_table).where(
                (user_monthly_entitlements_table.c.user_id.in_(user_ids))
                & (user_monthly_entitlements_table.c.period_month == period)
            )
        ).fetchall()
        trial_rows = conn.execute(select(user_trial_entitlements_table).where(user_trial_entitlements_table.c.user_id.in_(user_ids))).fetchall()
        paid_rows = conn.execute(
            select(
                orders_table.c.user_id,
                func.sum(orders_table.c.amount_cents).label("paid_cents"),
            )
            .where((orders_table.c.user_id.in_(user_ids)) & (orders_table.c.status == "paid"))
            .group_by(orders_table.c.user_id)
        ).fetchall()
        states = {row._mapping["user_id"]: _mapping(row) for row in state_rows}
        memberships = {row._mapping["user_id"]: _mapping(row) for row in membership_rows}
        entitlements = [_mapping(row) for row in entitlement_rows]
        trial_entitlements = {row._mapping["user_id"]: _mapping(row) for row in trial_rows}
        paid_amounts = {str(row._mapping["user_id"]): int(row._mapping["paid_cents"] or 0) / 100 for row in paid_rows}
    return {
        "plans": plans,
        "plans_by_id": {plan["id"]: plan for plan in plans},
        "states": states,
        "memberships": memberships,
        "entitlements": entitlements,
        "entitlements_by_user_period": {(item["user_id"], item["period_month"]): item for item in entitlements},
        "trial_entitlements": trial_entitlements,
        "paid_amounts": paid_amounts,
        "period": current_period_month(),
    }


def _plans_by_id_in_conn(conn) -> dict[str, dict[str, Any]]:
    plans = [_plan_from_row(row) for row in conn.execute(select(membership_plans_table).order_by(membership_plans_table.c.sort_order.asc())).fetchall()]
    return {plan["id"]: plan for plan in plans}


def _addons_by_id_in_conn(conn) -> dict[str, dict[str, Any]]:
    addons = [_addon_from_row(row) for row in conn.execute(select(membership_addons_table).order_by(membership_addons_table.c.sort_order.asc())).fetchall()]
    return {addon["id"]: addon for addon in addons}


def _date_in_range(value: str, start: str, end: str) -> bool:
    if start and value < start:
        return False
    if end and value > end:
        return False
    return True


def _parse_date(value: Any) -> date | None:
    if not value:
        return None
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value)
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).date()
    except ValueError:
        try:
            return date.fromisoformat(text[:10])
        except ValueError:
            return None


def _normalize_date_to_iso(value: Any) -> str | None:
    parsed = _parse_date(value)
    if parsed is None:
        return None
    return datetime.combine(parsed, datetime.min.time(), tzinfo=UTC).isoformat()


def _normalize_datetime_to_iso(value: Any) -> str | None:
    if not value:
        return None
    if isinstance(value, datetime):
        parsed = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
        return parsed.astimezone(UTC).isoformat()
    text = str(value).strip()
    if not text:
        return None
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        try:
            parsed = datetime.combine(date.fromisoformat(text[:10]), datetime.min.time(), tzinfo=UTC)
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC).isoformat()


def _date_label(value: Any) -> str:
    parsed = _parse_date(value)
    return parsed.isoformat() if parsed is not None else "-"


def _datetime_label(value: Any) -> str:
    if not value:
        return "-"
    try:
        parsed = datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return str(value)
    return parsed.astimezone(UTC).strftime("%Y-%m-%d %H:%M")


def _minutes_to_hours(minutes: int) -> float | int:
    hours = minutes / 60
    return int(hours) if hours.is_integer() else round(hours, 1)


def _minutes_to_hours_text(minutes: int) -> str:
    return f"{_minutes_to_hours(minutes)}小时"


def _money_text(cents: int) -> str:
    return f"¥{round(int(cents) / 100, 2)}"


def _plan_change_text(plan: dict[str, Any]) -> str:
    return f"{plan['name']} / {_money_text(plan['price_cents'])} / {_minutes_to_hours_text(plan['transcription_minutes'])} / 问答 {plan['qa']}次 / {'启用' if plan['enabled'] else '停用'}"


def _addon_change_text(addon: dict[str, Any]) -> str:
    unit_label = "小时" if addon.get("unit") == "hour" else str(addon.get("unit") or "")
    return f"{addon['name']} / {_money_text(addon['price_cents'])}/{unit_label} / {'启用' if addon['enabled'] else '停用'}"


def _announcement_record_text(item: dict[str, Any]) -> str:
    return f"{item.get('title') or ''} / {ANNOUNCEMENT_STATUS_LABELS.get(str(item.get('status')), str(item.get('status') or ''))}"


def _grant_content_text(member_days: int, transcription_minutes: int, knowledge_qa: int) -> str:
    parts = []
    if member_days:
        parts.append(f"会员 +{member_days}天")
    if transcription_minutes:
        parts.append(f"转写 +{_minutes_to_hours_text(transcription_minutes)}")
    if knowledge_qa:
        parts.append(f"问答 +{knowledge_qa}次")
    return "，".join(parts) if parts else "-"


def _order_status_value(value: str) -> str:
    clean = str(value or "").strip()
    return ORDER_STATUS_VALUES.get(clean, clean if clean in ORDER_STATUS_LABELS else "pending")


def _channel_value(value: str) -> str:
    clean = str(value or "").strip()
    return CHANNEL_VALUES.get(clean, clean if clean in CHANNEL_LABELS else "manual")


def _announcement_status_value(value: str) -> str:
    clean = str(value or "").strip()
    return ANNOUNCEMENT_STATUS_VALUES.get(clean, clean if clean in ANNOUNCEMENT_STATUS_LABELS else "draft")


def _audience_value(value: str) -> str:
    clean = str(value or "").strip()
    return AUDIENCE_VALUES.get(clean, clean if clean in AUDIENCE_LABELS else "all")


def _default_data_stages() -> list[dict[str, str]]:
    today = date.today()
    current_start = today.replace(day=1)
    previous_last = current_start - timedelta(days=1)
    previous_start = previous_last.replace(day=1)
    return [
        {
            "id": f"{current_start.isoformat()}_{today.isoformat()}",
            "name": f"{current_start.isoformat()} 至 {today.isoformat()}",
            "start": current_start.isoformat(),
            "end": today.isoformat(),
        },
        {
            "id": previous_start.strftime("%Y-%m"),
            "name": f"{previous_start.strftime('%Y-%m')} 完整月",
            "start": previous_start.isoformat(),
            "end": previous_last.isoformat(),
        },
    ]
