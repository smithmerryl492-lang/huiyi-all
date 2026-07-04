from datetime import UTC, datetime

from fastapi import APIRouter, Depends, HTTPException, Request

from app import repositories
from app.core.config import settings
from app.schemas import (
    LoginResponse,
    MessageResponse,
    PhoneChangeRequest,
    PhoneChangeVerifyRequest,
    PhoneChangeVerifyResponse,
    PasswordChangeRequest,
    PasswordLoginRequest,
    PasswordRegisterRequest,
    PasswordResetRequest,
    PasswordSetRequest,
    SmsCodeRequest,
    SmsCodeResponse,
    SmsCodeScene,
    SmsLoginRequest,
)
from app.services.auth import create_access_token, create_phone_change_token, require_current_user_id, verify_phone_change_token
from app.services.passwords import hash_password, verify_password
from app.services.sms import normalize_phone, send_sms_code, verify_sms_code


router = APIRouter()
PHONE_CHANGE_VERIFY_TOKEN_TTL_SECONDS = 10 * 60


@router.post("/sms/send-code", response_model=SmsCodeResponse)
def send_code(request: SmsCodeRequest, http_request: Request) -> SmsCodeResponse:
    result = send_sms_code(request.phone, request.scene, http_request.client.host if http_request.client else None)
    scene_label = {
        SmsCodeScene.login: "登录",
        SmsCodeScene.register: "注册",
        SmsCodeScene.change_password: "验证",
        SmsCodeScene.change_phone: "验证",
    }.get(request.scene, "验证")
    return SmsCodeResponse(
        message=f"{scene_label}验证码已发送",
        expires_in=int(result["expires_in"]),
        resend_after=int(result["resend_after"]),
    )


@router.post("/sms/login", response_model=LoginResponse)
def sms_login(request: SmsLoginRequest) -> LoginResponse:
    normalized_phone = normalize_phone(request.phone)
    user = repositories.get_user_by_phone(normalized_phone)
    if user is None:
        raise HTTPException(status_code=404, detail="ACCOUNT_NOT_REGISTERED", headers={"X-Huixiao-Auth-State": "registration_required"})
    phone_e164 = verify_sms_code(request.phone, SmsCodeScene.login, request.code)
    updated = repositories.upsert_user_by_phone(phone_e164)
    return _login_response(updated)


@router.post("/phone/change", response_model=LoginResponse)
def change_phone(request: PhoneChangeRequest, user_id: str = Depends(require_current_user_id)) -> LoginResponse:
    user = repositories.get_user_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    old_phone_e164 = normalize_phone(request.old_phone)
    current_phone = str(user.get("phone_e164") or "")
    if current_phone != old_phone_e164:
        raise HTTPException(status_code=409, detail="原手机号与当前账号不一致")
    new_phone_e164 = normalize_phone(request.new_phone)
    if new_phone_e164 == old_phone_e164:
        raise HTTPException(status_code=409, detail="新手机号不能与当前手机号相同")
    existing = repositories.get_user_by_phone(new_phone_e164)
    if existing is not None and str(existing.get("id") or existing.get("user_id") or "") != user_id:
        raise HTTPException(status_code=409, detail="该手机号已绑定其他账号")
    if request.old_verification_token:
        verify_phone_change_token(request.old_verification_token, user_id, old_phone_e164)
    else:
        verify_sms_code(request.old_phone, SmsCodeScene.change_phone, request.old_code)
    verify_sms_code(request.new_phone, SmsCodeScene.change_phone, request.new_code)
    updated = repositories.update_user_phone(user_id, new_phone_e164)
    return _login_response(_user_response_dict(updated))


@router.post("/phone/change/verify-current", response_model=PhoneChangeVerifyResponse)
def verify_current_phone_for_change(
    request: PhoneChangeVerifyRequest,
    user_id: str = Depends(require_current_user_id),
) -> PhoneChangeVerifyResponse:
    user = repositories.get_user_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    old_phone_e164 = normalize_phone(request.old_phone)
    current_phone = str(user.get("phone_e164") or "")
    if current_phone != old_phone_e164:
        raise HTTPException(status_code=409, detail="原手机号与当前账号不一致")
    verified_phone = verify_sms_code(request.old_phone, SmsCodeScene.change_phone, request.old_code)
    return PhoneChangeVerifyResponse(
        message="当前手机号已验证",
        verification_token=create_phone_change_token(user_id, verified_phone, PHONE_CHANGE_VERIFY_TOKEN_TTL_SECONDS),
        expires_in=PHONE_CHANGE_VERIFY_TOKEN_TTL_SECONDS,
    )


@router.post("/password/register", response_model=LoginResponse)
def password_register(request: PasswordRegisterRequest) -> LoginResponse:
    password_hash = hash_password(request.password)
    phone_e164 = normalize_phone(request.phone)
    existing = repositories.get_user_by_phone(phone_e164)
    if existing is not None and existing.get("password_hash"):
        raise HTTPException(status_code=409, detail="该手机号已注册，请直接登录或找回密码")
    phone_e164 = verify_sms_code(request.phone, SmsCodeScene.register, request.code)
    user = repositories.upsert_user_by_phone(phone_e164)
    updated = repositories.set_user_password(str(user["user_id"]), password_hash)
    return _login_response(_user_response_dict(updated))


@router.post("/password/login", response_model=LoginResponse)
def password_login(request: PasswordLoginRequest) -> LoginResponse:
    phone_e164 = normalize_phone(request.phone)
    user = repositories.get_user_by_phone(phone_e164)
    if user is None or not user.get("password_hash"):
        raise HTTPException(status_code=401, detail="手机号或密码错误")
    _ensure_password_not_locked(user)
    if not verify_password(request.password, str(user.get("password_hash") or "")):
        updated = repositories.record_password_login_failure(str(user["id"]))
        _raise_password_failure(updated)
    updated = repositories.mark_password_login_success(str(user["id"]))
    return _login_response(_user_response_dict(updated))


@router.post("/password/set", response_model=MessageResponse)
def set_password(request: PasswordSetRequest, user_id: str = Depends(require_current_user_id)) -> MessageResponse:
    user = repositories.get_user_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if user.get("password_hash"):
        raise HTTPException(status_code=409, detail="该账号已设置密码，请使用修改密码")
    repositories.set_user_password(user_id, hash_password(request.password))
    return MessageResponse(message="密码已设置")


@router.post("/password/change", response_model=MessageResponse)
def change_password(request: PasswordChangeRequest, user_id: str = Depends(require_current_user_id)) -> MessageResponse:
    user = repositories.get_user_by_id(user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if not user.get("password_hash"):
        raise HTTPException(status_code=409, detail="该账号尚未设置密码")
    _ensure_password_not_locked(user)
    if not verify_password(request.old_password, str(user.get("password_hash") or "")):
        updated = repositories.record_password_login_failure(user_id)
        _raise_password_failure(updated)
    repositories.set_user_password(user_id, hash_password(request.new_password))
    return MessageResponse(message="密码已修改")


@router.post("/password/reset", response_model=LoginResponse)
def reset_password(request: PasswordResetRequest) -> LoginResponse:
    password_hash = hash_password(request.password)
    phone_e164 = normalize_phone(request.phone)
    user = repositories.get_user_by_phone(phone_e164)
    if user is None:
        raise HTTPException(status_code=404, detail="账号不存在，请先注册")
    phone_e164 = verify_sms_code(request.phone, SmsCodeScene.change_password, request.code)
    updated = repositories.set_user_password(str(user["id"]), password_hash)
    repositories.mark_password_login_success(str(user["id"]))
    return _login_response(_user_response_dict(updated))


def _login_response(user: dict[str, object]) -> LoginResponse:
    user_id = str(user["user_id"])
    phone = str(user.get("phone_e164") or "")
    return LoginResponse(
        user_id=user_id,
        username=str(user["username"]),
        display_name=str(user["display_name"]),
        phone=phone,
        access_token=create_access_token(user_id, phone),
        expires_in=settings.auth_token_ttl_seconds,
    )


def _user_response_dict(user: dict[str, object]) -> dict[str, object]:
    return {
        "user_id": user["id"],
        "username": user["username"],
        "display_name": user["display_name"],
        "phone_e164": user.get("phone_e164"),
    }


def _ensure_password_not_locked(user: dict[str, object]) -> None:
    locked_until = _parse_iso(str(user.get("password_locked_until") or ""))
    if locked_until is not None and locked_until > datetime.now(UTC):
        raise HTTPException(status_code=429, detail="密码错误次数过多，请稍后再试")


def _raise_password_failure(user: dict[str, object]) -> None:
    locked_until = _parse_iso(str(user.get("password_locked_until") or ""))
    if locked_until is not None and locked_until > datetime.now(UTC):
        raise HTTPException(status_code=429, detail="密码错误次数过多，请 15 分钟后再试")
    raise HTTPException(status_code=401, detail="手机号或密码错误")


def _parse_iso(value: str) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)
