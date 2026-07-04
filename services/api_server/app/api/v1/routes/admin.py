from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from app import admin_repositories
from app.services import alipay
from app.services.admin_auth import create_admin_access_token, require_current_admin
from app.services.passwords import verify_password


router = APIRouter()


class AdminLoginRequest(BaseModel):
    username: str
    password: str


class PlanUpdateRequest(BaseModel):
    price: float | None = None
    price_cents: int | None = None
    hours: float | None = None
    transcription_minutes: int | None = None
    qa: int = Field(default=0, ge=0)
    enabled: bool = True


class AddonUpdateRequest(BaseModel):
    price: float | None = None
    price_cents: int | None = None
    enabled: bool = True


class FreezeRequest(BaseModel):
    frozen: bool
    reason: str = ""
    note: str = ""


class GiftBenefitRequest(BaseModel):
    member_days: int = 0
    hours: float = 0
    transcription_minutes: int | None = None
    qa: int = 0
    note: str = ""


class MonthPlanRequest(BaseModel):
    plan_id: str
    period_month: str | None = None


class ManualOrderRequest(BaseModel):
    user_id: str
    plan_id: str
    amount: float | None = None
    amount_cents: int | None = None
    status: str = "paid"
    channel: str = "manual"
    paid_at: str | None = None
    note: str = ""


class DispatchRequest(BaseModel):
    audienceMode: str = "currentPlan"
    planId: str | None = None
    startDate: str | None = None
    endDate: str | None = None
    beforeDate: str | None = None
    statusFilter: str = "正常"
    keyword: str = ""
    sort: str = "purchaseDesc"
    userIds: list[str] = Field(default_factory=list)
    type: str = "benefit"
    memberDays: int = 0
    hours: float = 0
    transcriptionMinutes: int | None = None
    knowledgeQa: int = 0
    reason: str = ""


class AnnouncementRequest(BaseModel):
    id: str | None = None
    title: str
    content: str
    audience: str = "全部用户"
    status: str = "草稿"
    expireAt: str | None = None
    publishAt: str | None = None
    pinned: bool = False
    readCount: int = 0


@router.post("/auth/login")
def admin_login(request: AdminLoginRequest) -> dict[str, Any]:
    admin = admin_repositories.get_admin_by_username(request.username)
    if admin is None:
        raise HTTPException(status_code=401, detail="后台账号或密码错误")
    if not bool(admin.get("active")):
        raise HTTPException(status_code=403, detail="后台账号已停用")
    if not verify_password(request.password, str(admin.get("password_hash") or "")):
        raise HTTPException(status_code=401, detail="后台账号或密码错误")
    admin_repositories.mark_admin_login(str(admin["id"]))
    return {
        "admin": _admin_payload(admin),
        "access_token": create_admin_access_token(str(admin["id"]), str(admin["username"])),
        "token_type": "bearer",
    }


@router.get("/auth/me")
def admin_me(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return {"admin": _admin_payload(admin)}


@router.get("/bootstrap")
def bootstrap(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.admin_bootstrap_payload()


@router.get("/runtime-config")
def runtime_config(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return {"payment": alipay.payment_config_diagnostics()}


@router.get("/plans")
def list_plans(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_plan_views()
    return {"items": items, "total": len(items)}


@router.patch("/plans/{plan_id}")
def update_plan(plan_id: str, request: PlanUpdateRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    price_cents = request.price_cents if request.price_cents is not None else int(round(float(request.price or 0) * 100))
    minutes = request.transcription_minutes if request.transcription_minutes is not None else int(round(float(request.hours or 0) * 60))
    return {"item": admin_repositories.update_plan(plan_id, price_cents, minutes, request.qa, request.enabled, str(admin["id"]))}


@router.get("/addons")
def list_addons(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_addon_views()
    return {"items": items, "total": len(items)}


@router.patch("/addons/{addon_id}")
def update_addon(addon_id: str, request: AddonUpdateRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    price_cents = request.price_cents if request.price_cents is not None else int(round(float(request.price or 0) * 100))
    return {"item": admin_repositories.update_addon(addon_id, price_cents, request.enabled, str(admin["id"]))}


@router.get("/users")
def list_users(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_users_for_admin()
    return {"items": items, "total": len(items)}


@router.get("/users/{user_id}")
def get_user(user_id: str, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.get_user_detail(user_id)


@router.post("/users/{user_id}/freeze")
def freeze_user(user_id: str, request: FreezeRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.set_user_frozen(user_id, request.frozen, request.reason, request.note, str(admin["id"]))


@router.post("/users/{user_id}/gift")
def gift_user(user_id: str, request: GiftBenefitRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    minutes = request.transcription_minutes if request.transcription_minutes is not None else int(round(float(request.hours or 0) * 60))
    return admin_repositories.gift_user_benefit(user_id, request.member_days, minutes, request.qa, request.note, str(admin["id"]))


@router.patch("/users/{user_id}/month-plan")
def change_user_month_plan(user_id: str, request: MonthPlanRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.change_user_month_plan(user_id, request.plan_id, str(admin["id"]), request.period_month)


@router.get("/orders")
def list_orders(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_orders()
    return {"items": items, "total": len(items)}


@router.post("/orders")
def create_order(request: ManualOrderRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    amount_cents = request.amount_cents if request.amount_cents is not None else int(round(float(request.amount or 0) * 100))
    return admin_repositories.create_manual_order(
        request.user_id,
        request.plan_id,
        amount_cents,
        request.status,
        request.channel,
        request.paid_at,
        request.note,
        str(admin["id"]),
    )


@router.post("/dispatch/preview")
def preview_dispatch(request: DispatchRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    payload = _dispatch_payload(request)
    return admin_repositories.preview_dispatch(payload)


@router.post("/dispatch/grants")
def create_dispatch(request: DispatchRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    payload = _dispatch_payload(request)
    return admin_repositories.create_grant_batch(payload, str(admin["id"]))


@router.get("/dispatch/grants")
def list_dispatch(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_grant_batches()
    return {"items": items, "total": len(items)}


@router.get("/announcements")
def list_announcements(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_announcements()
    return {"items": items, "total": len(items)}


@router.post("/announcements")
def save_announcement(request: AnnouncementRequest, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.save_announcement(request.model_dump(), str(admin["id"]))


@router.post("/announcements/{announcement_id}/toggle")
def toggle_announcement(announcement_id: str, admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    return admin_repositories.toggle_announcement(announcement_id, str(admin["id"]))


@router.get("/changes")
def list_changes(admin: dict[str, Any] = Depends(require_current_admin)) -> dict[str, Any]:
    items = admin_repositories.list_change_records()
    return {"items": items, "total": len(items)}


def _admin_payload(admin: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": admin["id"],
        "username": admin["username"],
        "displayName": admin.get("display_name") or admin["username"],
        "lastLoginAt": admin.get("last_login_at"),
    }


def _dispatch_payload(request: DispatchRequest) -> dict[str, Any]:
    data = request.model_dump()
    data["transcriptionMinutes"] = (
        request.transcriptionMinutes
        if request.transcriptionMinutes is not None
        else int(round(float(request.hours or 0) * 60))
    )
    return data
