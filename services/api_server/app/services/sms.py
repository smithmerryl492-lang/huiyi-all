from __future__ import annotations

import hashlib
import hmac
import re
import secrets
from datetime import UTC, datetime, timedelta

from fastapi import HTTPException

from app import repositories
from app.core.config import settings
from app.schemas import SmsCodeScene


def normalize_phone(raw_phone: str) -> str:
    clean = re.sub(r"[^\d+]", "", raw_phone.strip())
    if clean.startswith("+86"):
        digits = clean[3:]
    elif clean.startswith("86") and len(clean) == 13:
        digits = clean[2:]
    else:
        digits = clean
    if not re.fullmatch(r"1\d{10}", digits):
        raise HTTPException(status_code=422, detail="请输入有效的中国大陆手机号")
    return f"+86{digits}"


def send_sms_code(raw_phone: str, scene: SmsCodeScene, request_ip: str | None = None) -> dict[str, int | str]:
    phone_e164 = normalize_phone(raw_phone)
    scene_value = scene.value
    _ensure_scene_allowed(phone_e164, scene)
    _enforce_send_limits(phone_e164, request_ip)
    code = _dev_or_random_code()
    expires_at = datetime.now(UTC) + timedelta(seconds=settings.sms_code_ttl_seconds)
    code_id = repositories.create_sms_verification_code(
        phone_e164,
        scene_value,
        _hash_code(phone_e164, scene_value, code),
        expires_at.isoformat(),
        request_ip,
    )
    try:
        if not settings.sms_dev_code:
            _send_with_tencent(phone_e164, scene, code)
    except Exception:
        repositories.consume_sms_verification_code(code_id)
        raise
    return {
        "phone": phone_e164,
        "expires_in": settings.sms_code_ttl_seconds,
        "resend_after": settings.sms_resend_cooldown_seconds,
    }


def _ensure_scene_allowed(phone_e164: str, scene: SmsCodeScene) -> None:
    if scene == SmsCodeScene.change_phone:
        return
    user = repositories.get_user_by_phone(phone_e164)
    if scene == SmsCodeScene.register:
        if user is not None:
            raise HTTPException(status_code=409, detail="ACCOUNT_ALREADY_REGISTERED")
        return
    if scene in {SmsCodeScene.login, SmsCodeScene.change_password} and user is None:
        raise HTTPException(status_code=404, detail="ACCOUNT_NOT_REGISTERED")


def verify_sms_code(raw_phone: str, scene: SmsCodeScene, raw_code: str) -> str:
    phone_e164 = normalize_phone(raw_phone)
    code = re.sub(r"\D", "", raw_code.strip())
    if len(code) != settings.sms_code_length:
        raise HTTPException(status_code=401, detail="验证码错误")
    row = repositories.get_latest_sms_verification_code(phone_e164, scene.value)
    if row is None:
        raise HTTPException(status_code=401, detail="验证码错误或已过期")
    if int(row.get("attempts") or 0) >= settings.sms_max_attempts:
        raise HTTPException(status_code=429, detail="验证码错误次数过多，请重新获取")
    if _parse_iso(row["expires_at"]) <= datetime.now(UTC):
        raise HTTPException(status_code=401, detail="验证码错误或已过期")
    expected = _hash_code(phone_e164, scene.value, code)
    if not hmac.compare_digest(expected, row["code_hash"]):
        repositories.increment_sms_verification_attempts(row["id"])
        raise HTTPException(status_code=401, detail="验证码错误")
    repositories.consume_sms_verification_code(row["id"])
    return phone_e164


def _enforce_send_limits(phone_e164: str, request_ip: str | None) -> None:
    now = datetime.now(UTC)
    cooldown_since = (now - timedelta(seconds=settings.sms_resend_cooldown_seconds)).isoformat()
    if repositories.count_sms_verification_codes(phone_e164=phone_e164, since_iso=cooldown_since) > 0:
        raise HTTPException(status_code=429, detail="验证码发送过于频繁，请稍后再试")
    day_since = (now - timedelta(days=1)).isoformat()
    if repositories.count_sms_verification_codes(phone_e164=phone_e164, since_iso=day_since) >= settings.sms_phone_daily_limit:
        raise HTTPException(status_code=429, detail="今日验证码发送次数已达上限")
    if request_ip and repositories.count_sms_verification_codes(request_ip=request_ip, since_iso=day_since) >= settings.sms_ip_daily_limit:
        raise HTTPException(status_code=429, detail="当前网络验证码发送次数已达上限")


def _send_with_tencent(phone_e164: str, scene: SmsCodeScene, code: str) -> None:
    if not all(
        [
            settings.tencent_sms_secret_id,
            settings.tencent_sms_secret_key,
            settings.tencent_sms_sdk_app_id,
            settings.tencent_sms_sign_name,
        ]
    ):
        raise HTTPException(status_code=500, detail="短信服务未配置")
    template_id = _template_id(scene)
    if not template_id:
        raise HTTPException(status_code=500, detail="短信模板未配置")

    from tencentcloud.common import credential
    from tencentcloud.common.exception.tencent_cloud_sdk_exception import TencentCloudSDKException
    from tencentcloud.common.profile.client_profile import ClientProfile
    from tencentcloud.common.profile.http_profile import HttpProfile
    from tencentcloud.sms.v20210111 import models, sms_client

    try:
        cred = credential.Credential(settings.tencent_sms_secret_id, settings.tencent_sms_secret_key)
        http_profile = HttpProfile()
        http_profile.endpoint = "sms.tencentcloudapi.com"
        client_profile = ClientProfile()
        client_profile.httpProfile = http_profile
        client = sms_client.SmsClient(cred, settings.tencent_sms_region, client_profile)

        request = models.SendSmsRequest()
        request.PhoneNumberSet = [phone_e164]
        request.SmsSdkAppId = settings.tencent_sms_sdk_app_id
        request.SignName = settings.tencent_sms_sign_name
        request.TemplateId = template_id
        request.TemplateParamSet = _template_params(code)
        response = client.SendSms(request)
    except TencentCloudSDKException as exc:
        raise HTTPException(status_code=502, detail=f"短信发送失败：{exc.get_code()}") from exc

    statuses = getattr(response, "SendStatusSet", None) or []
    status = statuses[0] if statuses else None
    status_code = getattr(status, "Code", "")
    if status_code and status_code.lower() != "ok":
        detail = getattr(status, "Message", "") or status_code
        raise HTTPException(status_code=502, detail=f"短信发送失败：{detail}")


def _template_id(scene: SmsCodeScene) -> str:
    if scene == SmsCodeScene.change_phone:
        return settings.sms_template_change_phone_id
    if scene == SmsCodeScene.change_password:
        return settings.sms_template_change_password_id
    return settings.sms_template_login_id


def _template_params(code: str) -> list[str]:
    if settings.sms_template_param_mode == "code_ttl":
        minutes = max(1, settings.sms_code_ttl_seconds // 60)
        return [code, str(minutes)]
    return [code]


def _dev_or_random_code() -> str:
    if settings.sms_dev_code:
        return settings.sms_dev_code
    upper = 10 ** settings.sms_code_length
    return str(secrets.randbelow(upper)).zfill(settings.sms_code_length)


def _hash_code(phone_e164: str, scene: str, code: str) -> str:
    message = f"{phone_e164}:{scene}:{code}".encode("utf-8")
    return hmac.new(settings.auth_token_secret.encode("utf-8"), message, hashlib.sha256).hexdigest()


def _parse_iso(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)
