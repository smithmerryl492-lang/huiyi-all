from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app import admin_repositories
from app.core.config import settings
from app.services import alipay, apple_iap
from app.services.auth import require_current_user_id


router = APIRouter()
logger = logging.getLogger(__name__)


class CreateAlipayOrderRequest(BaseModel):
    plan_id: str


class CreateAlipayAddonOrderRequest(BaseModel):
    addon_id: str
    quantity: int = 1


class ConfirmAppleTransactionRequest(BaseModel):
    product_id: str
    transaction_id: str
    original_transaction_id: str | None = None
    environment: str | None = None
    purchase_date_ms: int | None = None
    signed_transaction_info: str


def _payment_payload(order: dict[str, Any], subject: str, body: str) -> dict[str, Any]:
    mode = alipay.normalized_payment_mode()
    payload: dict[str, Any] = {
        "order": order,
        "payment_enabled": True,
        "payment_mode": mode,
        "order_string": "",
        "pay_url": "",
    }
    if mode == "app":
        payload["order_string"] = alipay.build_app_pay_order_string(
            out_trade_no=str(order["id"]),
            subject=subject,
            total_amount_cents=int(order["amountCents"]),
            body=body,
        )
    else:
        payload["pay_url"] = alipay.build_wap_pay_url(
            out_trade_no=str(order["id"]),
            subject=subject,
            total_amount_cents=int(order["amountCents"]),
            body=body,
        )
    return payload


@router.post("/alipay/orders")
def create_alipay_order(request: CreateAlipayOrderRequest, user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    started = time.perf_counter()
    alipay.require_payment_config()
    order = admin_repositories.create_alipay_plan_order(user_id, request.plan_id)
    payload = _payment_payload(order, f"鲲穹会纪{order['planName']}会员", f"{order['planName']}会员套餐")
    _log_slow_payment("create_plan_order", started, order.get("id"))
    return payload


@router.post("/alipay/addon-orders")
def create_alipay_addon_order(request: CreateAlipayAddonOrderRequest, user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    started = time.perf_counter()
    alipay.require_payment_config()
    order = admin_repositories.create_alipay_addon_order(user_id, request.addon_id, request.quantity)
    payload = _payment_payload(order, f"鲲穹会纪{order['productName']}", f"{order['productName']}，转写{order['transcriptionMinutes']}分钟")
    _log_slow_payment("create_addon_order", started, order.get("id"))
    return payload


@router.get("/orders")
def list_my_orders(user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    return {"items": admin_repositories.list_user_payment_orders(user_id)}


@router.get("/orders/{order_id}")
def get_order(order_id: str, user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    order = admin_repositories.get_order(order_id)
    if str(order.get("userId")) != user_id:
        return {"order": None}
    return {"order": order}


@router.post("/alipay/orders/{order_id}/sync")
def sync_alipay_order(order_id: str, user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    started = time.perf_counter()
    order = admin_repositories.get_order(order_id)
    if str(order.get("userId")) != user_id:
        return {"order": None}
    if str(order.get("status") or "") == "支付成功":
        _log_slow_payment("sync_order_local_paid", started, order_id)
        return {"order": order, "trade_status": "TRADE_SUCCESS"}
    payload = alipay.query_trade(order_id)
    trade_status = payload.get("trade_status") or payload.get("sub_code") or ""
    if trade_status in {"TRADE_SUCCESS", "TRADE_FINISHED"}:
        order = admin_repositories.mark_alipay_order_paid(
            order_id,
            str(payload.get("trade_no") or ""),
            alipay.yuan_to_cents(payload.get("total_amount") or "0"),
            payload.get("send_pay_date") or payload.get("gmt_payment"),
        )
    elif trade_status in {"WAIT_BUYER_PAY", "TRADE_CLOSED", "ACQ.TRADE_NOT_EXIST"}:
        order = admin_repositories.get_order(order_id)
    _log_slow_payment("sync_order_query_alipay", started, order_id)
    return {"order": order, "trade_status": trade_status}


@router.post("/apple/transactions/confirm")
def confirm_apple_transaction(request: ConfirmAppleTransactionRequest, user_id: str = Depends(require_current_user_id)) -> dict[str, Any]:
    if not settings.apple_iap_enabled:
        raise HTTPException(status_code=503, detail="iOS 内购暂未开通")
    verified = apple_iap.verify_transaction(
        transaction_id=request.transaction_id,
        expected_product_id=request.product_id,
        client_signed_transaction_info=request.signed_transaction_info,
    )
    order = admin_repositories.confirm_apple_transaction(
        user_id=user_id,
        product_id=verified["product_id"],
        transaction_id=verified["transaction_id"],
        original_transaction_id=verified["original_transaction_id"],
        environment=verified["environment"],
        purchase_date_ms=verified["purchase_date_ms"],
        signed_transaction_info=verified["signed_transaction_info"],
    )
    return {"order": order}


def _log_slow_payment(action: str, started: float, order_id: object | None = None) -> None:
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    if elapsed_ms >= 800:
        logger.warning("payment %s slow: %sms order=%s", action, elapsed_ms, order_id or "-")


@router.post("/alipay/notify")
async def alipay_notify(request: Request) -> str:
    form = await request.form()
    params = {str(key): str(value) for key, value in form.items()}
    if not alipay.verify_notify_params(params):
        return "failure"
    if not alipay.notify_app_id_matches(params):
        return "failure"
    trade_status = params.get("trade_status") or ""
    if trade_status not in {"TRADE_SUCCESS", "TRADE_FINISHED"}:
        return "success"
    out_trade_no = params.get("out_trade_no") or ""
    trade_no = params.get("trade_no") or ""
    total_amount = params.get("total_amount") or params.get("receipt_amount") or "0"
    gmt_payment = params.get("gmt_payment")
    try:
        admin_repositories.mark_alipay_order_paid(out_trade_no, trade_no, alipay.yuan_to_cents(total_amount), gmt_payment)
    except HTTPException:
        return "failure"
    return "success"


@router.get("/alipay/return", response_class=HTMLResponse)
def alipay_return() -> str:
    return """<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>支付结果确认中</title>
  <style>
    body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#f6f8fb;color:#162033}
    main{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:28px}
    section{width:100%;max-width:360px;text-align:center}
    h1{font-size:22px;margin:0 0 10px}
    p{font-size:15px;line-height:1.7;color:#5b6678;margin:0 0 22px}
    .hint{display:inline-flex;align-items:center;justify-content:center;height:44px;border-radius:8px;background:#eef5ff;color:#1677ff;font-size:15px;padding:0 22px}
  </style>
</head>
<body>
  <main>
    <section>
      <h1>支付结果确认中</h1>
      <p>请手动回到鲲穹会纪，系统会自动确认订单状态并发放会员权益。</p>
      <div class="hint">可以关闭此页面</div>
    </section>
  </main>
</body>
</html>"""
