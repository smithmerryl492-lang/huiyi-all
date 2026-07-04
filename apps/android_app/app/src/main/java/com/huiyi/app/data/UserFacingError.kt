package com.huiyi.app.data

import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

private const val DEFAULT_FALLBACK_MESSAGE = "操作失败，请稍后重试"
private const val SERVER_MAINTENANCE_MESSAGE = "服务器维护中，请稍后重试"
private const val NETWORK_MESSAGE = "网络连接失败，请检查网络后重试"
private const val TIMEOUT_MESSAGE = "请求超时，请稍后重试"
private const val AI_MESSAGE = "智能处理暂时失败，请稍后重试"
private const val ASR_MESSAGE = "语音识别暂时失败，请稍后重试"

private val ipAddressPattern = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b""")
private val urlPattern = Regex("""(?i)\b(?:https?|ws)://\S+""")
private val exceptionPattern = Regex("""\b(?:java|kotlin|urllib|fastapi|pydantic|socket|ssl)\.[\w.]+|[A-Za-z_][\w.]*Exception\b""")
private val httpStatusPattern = Regex("""\bHTTP\s*(\d{3})\b""", RegexOption.IGNORE_CASE)

private val infrastructureMarkers = listOf(
    "unexpected end of stream",
    "end of stream",
    "stream was reset",
    "okhttp",
    "com.android",
    "Address@",
    "Failed to connect",
    "Connection refused",
    "Connection reset",
    "No route to host",
    "Network is unreachable",
    "ECONNREFUSED",
    "ENETUNREACH",
    "EHOSTUNREACH",
    "connect timed out",
    "Read timed out",
    "timeout",
    "Timed out",
    "SSLHandshake",
    "Unable to resolve host",
    "UnknownHost",
    "Traceback",
    "stacktrace",
    "Internal Server Error",
    "Bad Gateway",
    "Service Unavailable",
    "Gateway Timeout"
)

fun Throwable.userFacingMessage(
    fallback: String = DEFAULT_FALLBACK_MESSAGE,
    networkAvailable: Boolean? = null
): String {
    return when (this) {
        is HuixiaoApiException -> userFacingApiMessage(this, fallback)
        is UnknownHostException -> NETWORK_MESSAGE
        is ConnectException, is NoRouteToHostException -> if (networkAvailable == false) NETWORK_MESSAGE else SERVER_MAINTENANCE_MESSAGE
        is SSLException -> SERVER_MAINTENANCE_MESSAGE
        is SocketTimeoutException -> TIMEOUT_MESSAGE
        is FileNotFoundException -> "文件不存在，请重新选择"
        is JSONException -> "服务器返回数据异常，请稍后重试"
        is IOException -> message.orEmpty().userFacingErrorText(NETWORK_MESSAGE, networkAvailable)
        else -> message.orEmpty().userFacingErrorText(fallback, networkAvailable)
    }
}

fun String.userFacingDisplayText(
    fallback: String = DEFAULT_FALLBACK_MESSAGE,
    networkAvailable: Boolean? = null
): String {
    val clean = trim()
    if (clean.isBlank()) return fallback
    if (clean == "未知错误") return fallback
    val colonIndex = clean.indexOf('：')
    if (colonIndex in 1 until clean.lastIndex) {
        val prefix = clean.substring(0, colonIndex).trim()
        val body = clean.substring(colonIndex + 1).trim()
        if (prefix.isInternalServicePrefix()) {
            return clean.userFacingCore(fallback, networkAvailable) ?: fallback
        }
        val mapped = body.userFacingCore(null, networkAvailable)
        if (mapped != null && mapped != body) {
            return "$prefix：$mapped"
        }
        if (body.isSensitiveDeveloperError()) {
            return "$prefix：$fallback"
        }
        if (body.isSafeBusinessMessage()) {
            return clean
        }
    }
    clean.userFacingCore(null, networkAvailable)?.let { mapped ->
        if (mapped != clean) return mapped
    }
    return if (clean.isSensitiveDeveloperError()) fallback else clean
}

internal fun String.userFacingApiDetail(statusCode: Int, fallback: String = DEFAULT_FALLBACK_MESSAGE): String {
    val clean = trim()
    return when (statusCode) {
        401 -> if (clean.isSafeLoginAuthMessage()) clean else "登录已过期，请重新登录"
        403 -> if (clean.contains("冻结")) "该账户状态异常已经被冻结" else "当前账号无权进行此操作"
        404 -> when {
            clean == "ACCOUNT_NOT_REGISTERED" -> "该手机号未注册，请先注册"
            clean.contains("音频") -> "音频文件暂不可用，请重新同步后再试"
            clean.contains("文件") -> "文件不存在或已被删除"
            clean.isSafeBusinessMessage() -> clean
            else -> "数据不存在或已被删除"
        }
        408 -> TIMEOUT_MESSAGE
        402 -> if (clean.contains("额度")) clean else "额度已耗尽，请充值后继续享受权益"
        409, 422, 429 -> clean.userFacingErrorText(fallback)
        in 500..599 -> clean.userFacingCore(SERVER_MAINTENANCE_MESSAGE) ?: SERVER_MAINTENANCE_MESSAGE
        else -> clean.userFacingErrorText(fallback)
    }
}

private fun userFacingApiMessage(error: HuixiaoApiException, fallback: String): String {
    val raw = error.rawMessage.orEmpty().ifBlank { error.message.orEmpty() }
    return raw.userFacingApiDetail(error.statusCode, fallback)
}

private fun String.userFacingErrorText(fallback: String, networkAvailable: Boolean? = null): String {
    val clean = trim()
    if (clean.isBlank() || clean == "未知错误") return fallback
    return clean.userFacingCore(null, networkAvailable) ?: fallback
}

private fun String.userFacingCore(fallback: String?, networkAvailable: Boolean? = null): String? {
    val clean = trim()
    if (clean.isBlank()) return fallback
    val lower = clean.lowercase()
    if (networkAvailable == false && clean.isNetworkConnectivityErrorText()) {
        return NETWORK_MESSAGE
    }
    return when {
        clean == "ACCOUNT_ALREADY_REGISTERED" -> "该手机号已注册，请直接登录"
        clean == "ACCOUNT_NOT_REGISTERED" -> "该手机号未注册，请先注册"
        clean.contains("登录已失效") || clean.contains("登录已过期") || clean.contains("登录凭证") -> "登录已过期，请重新登录"
        clean.contains("请先登录") -> "请先登录"
        clean.contains("音频") && clean.contains("不存在") -> "音频文件暂不可用，请重新同步后再试"
        clean.contains("本地文件不存在") || clean.contains("采样音频不存在") -> "本地文件不存在，请重新选择文件"
        clean.contains("服务器临时文件不存在") -> "文件暂不可用，请重新上传后再试"
        clean.contains("ASR") || clean.contains("语音识别服务") -> ASR_MESSAGE
        clean.contains("AI ") || clean.contains("AI服务") || clean.contains("AI 服务") || clean.contains("模型服务") || clean.contains("LLM") || clean.contains("向量服务") -> AI_MESSAGE
        lower.contains("failed to connect") ||
            lower.contains("connection refused") ||
            lower.contains("connection reset") ||
            lower.contains("unexpected end of stream") ||
            lower.contains("end of stream") ||
            lower.contains("stream was reset") ||
            lower.contains("no route to host") ||
            lower.contains("network is unreachable") ||
            lower.contains("econnrefused") ||
            lower.contains("enetunreach") ||
            lower.contains("ehostunreach") -> SERVER_MAINTENANCE_MESSAGE
        lower.contains("unable to resolve host") || lower.contains("unknownhost") -> NETWORK_MESSAGE
        lower.contains("read timed out") || lower.contains("timeout") || lower.contains("timed out") -> TIMEOUT_MESSAGE
        httpStatusCodeOrNull()?.let { it >= 500 } == true -> SERVER_MAINTENANCE_MESSAGE
        isSensitiveDeveloperError() -> fallback
        isSafeBusinessMessage() -> clean
        else -> fallback
    }
}

private fun String.isNetworkConnectivityErrorText(): Boolean {
    val clean = trim()
    val lower = clean.lowercase()
    if (clean == SERVER_MAINTENANCE_MESSAGE) return true
    return lower.contains("failed to connect") ||
        lower.contains("connection refused") ||
        lower.contains("connection reset") ||
        lower.contains("no route to host") ||
        lower.contains("network is unreachable") ||
        lower.contains("unable to resolve host") ||
        lower.contains("unknownhost") ||
        lower.contains("econnrefused") ||
        lower.contains("enetunreach") ||
        lower.contains("ehostunreach")
}

private fun String.httpStatusCodeOrNull(): Int? {
    return httpStatusPattern.find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun String.isSensitiveDeveloperError(): Boolean {
    if (ipAddressPattern.containsMatchIn(this)) return true
    if (urlPattern.containsMatchIn(this)) return true
    if (exceptionPattern.containsMatchIn(this)) return true
    return infrastructureMarkers.any { marker -> contains(marker, ignoreCase = true) }
}

private fun String.isSafeLoginAuthMessage(): Boolean {
    val clean = trim()
    if (clean.isBlank() || clean.length > 80) return false
    if (isSensitiveDeveloperError()) return false
    return clean == "手机号或密码错误" ||
        clean == "验证码错误" ||
        clean == "验证码错误或已过期"
}

private fun String.isInternalServicePrefix(): Boolean {
    return contains("ASR") ||
        contains("AI ") ||
        contains("AI服务") ||
        contains("AI 服务") ||
        contains("模型服务") ||
        contains("LLM") ||
        contains("向量服务")
}

private fun String.isSafeBusinessMessage(): Boolean {
    val clean = trim()
    if (clean.isBlank() || clean.length > 80) return false
    if (isSensitiveDeveloperError()) return false
    if (clean.any { it in listOf('{', '}', '[', ']', '<', '>') }) return false
    val businessMarkers = listOf(
        "请输入",
        "请先",
        "请稍后",
        "请直接",
        "请使用",
        "不能为空",
        "必须",
        "不能",
        "不足",
        "不支持",
        "不一致",
        "不存在",
        "无权",
        "无效",
        "尚未",
        "已过期",
        "已失效",
        "已绑定",
        "已注册",
        "已达上限",
        "额度",
        "过于频繁",
        "验证码",
        "手机号",
        "账号",
        "用户",
        "密码",
        "需为",
        "需同时",
        "文件超过",
        "文件为空",
        "声纹样本",
        "样本质量",
        "任务已终止",
        "任务已失败",
        "时间冲突",
        "格式不正确",
        "处理时间较长"
    )
    return businessMarkers.any { clean.contains(it) }
}
