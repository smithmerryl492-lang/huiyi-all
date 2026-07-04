from __future__ import annotations

from fastapi import HTTPException


SERVER_MAINTENANCE_MESSAGE = "服务器维护中，请稍后重试"
AI_USER_MESSAGE = "智能处理暂时失败，请稍后重试"
ASR_USER_MESSAGE = "语音识别暂时失败，请稍后重试"
TASK_USER_MESSAGE = "处理失败，请稍后重试"
SYNC_USER_MESSAGE = "同步失败，请稍后重试"


def user_message_from_exception(exc: BaseException, default: str = TASK_USER_MESSAGE) -> str:
    if isinstance(exc, HTTPException):
        return user_message_from_text(str(exc.detail or ""), status_code=exc.status_code, default=default)
    return user_message_from_text(str(exc), default=default)


def user_message_from_text(text: str, status_code: int | None = None, default: str = TASK_USER_MESSAGE) -> str:
    clean = str(text or "").strip()
    lower = clean.lower()
    if status_code == 401:
        return "登录已过期，请重新登录"
    if status_code == 403:
        if "冻结" in clean:
            return "该账户状态异常已经被冻结"
        return "当前账号无权进行此操作"
    if status_code == 404:
        if "音频" in clean:
            return "音频文件暂不可用，请重新同步后再试"
        if "文件" in clean:
            return "文件不存在或已被删除"
        return "数据不存在或已被删除"
    if status_code == 408:
        return "请求超时，请稍后重试"
    if status_code and status_code >= 500:
        if is_safe_business_message(clean):
            return clean
        if "ASR" in clean or "语音" in clean:
            return ASR_USER_MESSAGE
        if "AI" in clean or "模型" in clean or "LLM" in clean or "向量" in clean:
            return AI_USER_MESSAGE
        return SERVER_MAINTENANCE_MESSAGE
    if "ASR" in clean or "语音识别" in clean:
        return ASR_USER_MESSAGE
    if "AI" in clean or "模型" in clean or "LLM" in clean or "向量" in clean:
        return AI_USER_MESSAGE
    if "服务器临时文件不存在" in clean:
        return "文件暂不可用，请重新上传后再试"
    if "云端音频文件不存在" in clean:
        return "音频文件暂不可用，请重新同步后再试"
    if any(
        marker in lower
        for marker in (
            "failed to connect",
            "connection refused",
            "no route to host",
            "connection reset",
            "unexpected end of stream",
            "end of stream",
            "stream was reset",
            "okhttp",
            "address@",
        )
    ):
        return SERVER_MAINTENANCE_MESSAGE
    if any(marker in lower for marker in ("timed out", "timeout", "read timed out")):
        return "请求超时，请稍后重试"
    if is_safe_business_message(clean):
        return clean
    return default


def is_safe_business_message(text: str) -> bool:
    if not text or len(text) > 80:
        return False
    lower = text.lower()
    unsafe_markers = (
        "http://",
        "https://",
        "traceback",
        "exception",
        "okhttp",
        "address@",
        "unexpected end of stream",
        "end of stream",
        "stream was reset",
        "failed to connect",
        "connection refused",
        "internal server error",
        "bad gateway",
    )
    if any(marker in lower for marker in unsafe_markers):
        return False
    business_markers = (
        "请输入",
        "请先",
        "请稍后",
        "不能为空",
        "不支持",
        "不足",
        "不存在",
        "无权",
        "无效",
        "已过期",
        "已失效",
        "已达上限",
        "过于频繁",
        "验证码",
        "文件超过",
        "文件为空",
        "声纹样本",
        "样本质量",
        "任务已终止",
        "任务已失败",
        "转写内容不能为空",
    )
    return any(marker in text for marker in business_markers)
