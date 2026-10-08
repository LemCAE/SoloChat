package app.solochat.core.llm

import kotlinx.serialization.Serializable

/**
 * 错误分类。
 *
 * 为什么要分类而不是直接抛字符串异常：用户看到"请求失败"毫无价值，
 * 而"Key 错了 / 地址写错了 / 模型名不存在 / 超时"这四类**处理方式完全不同**，
 * 也决定了要不要重试（[retryable]）。
 * 分类口径移植自 QQ-agent 的 `src/llm.js`（MIT, Copyright (c) 2026 Kondius）。
 */
@Serializable
enum class LlmErrorKind {
    /** 没有配置，或配置不完整。 */
    NOT_CONFIGURED,

    /** 401 / 403：Key 无效或没权限。**不要重试**。 */
    AUTH,

    /** 404：地址路径不对，或模型名不存在。**不要重试**。 */
    NOT_FOUND,

    /** 400：请求体被拒（模型不支持该参数、上下文超长等）。**不要重试**。 */
    BAD_REQUEST,

    /** 429：限流。**要重试**（带退避）。 */
    RATE_LIMIT,

    /** 5xx：服务端问题。**要重试**。 */
    SERVER,

    /** 连接不上（DNS / 拒绝 / TLS）。**要重试**一次，多半是地址写错或断网。 */
    CONNECT,

    /** 读超时。**要重试**。 */
    TIMEOUT,

    /** 用户主动取消。不重试、不报错给用户。 */
    CANCELED,

    /** 返回体不是合法 SSE 或结构不符。多半是网关不兼容。 */
    PROTOCOL,

    UNKNOWN;

    val retryable: Boolean
        get() = when (this) {
            RATE_LIMIT, SERVER, CONNECT, TIMEOUT, UNKNOWN -> true
            else -> false
        }
}

/**
 * 一个请求生命周期内可能出现的非正常结束。
 * 用 sealed class 是为了让调用方必须显式处理每一种（编译期强制）。
 */
@Serializable
sealed class LlmError {
    abstract val kind: LlmErrorKind
    abstract val message: String

    /** 服务端原文摘录，便于排查而不至于刷屏日志。 */
    abstract val detail: String

    data class Failed(
        override val kind: LlmErrorKind,
        override val message: String,
        override val detail: String = "",
        val httpStatus: Int? = null
    ) : LlmError()

    data class Canceled(override val message: String = "已取消") : LlmError() {
        override val kind: LlmErrorKind = LlmErrorKind.CANCELED
        override val detail: String = ""
    }

    /** 给用户看的修复建议。UI 直接展示，不要自己拼。 */
    fun advice(): String = when (kind) {
        LlmErrorKind.NOT_CONFIGURED -> "还没配置接口地址和 Key，去设置里填一下。"
        LlmErrorKind.AUTH -> "Key 被拒了。检查 Key 是否正确、有没有该模型的权限。"
        LlmErrorKind.NOT_FOUND -> "地址或模型名不对。确认 Base URL 是否带 /v1，模型名是否在列表里。"
        LlmErrorKind.BAD_REQUEST -> "请求被拒。常见原因：模型不支持当前参数，或上下文太长需要压缩。"
        LlmErrorKind.RATE_LIMIT -> "被限流了，稍等一下会自动重试。"
        LlmErrorKind.SERVER -> "对方服务端出错了，会自动重试几次。"
        LlmErrorKind.CONNECT -> "连不上。检查网络，以及地址是否写错（要 http:// 或 https:// 开头）。"
        LlmErrorKind.TIMEOUT -> "超时了。网络慢或模型排队，会自动重试。"
        LlmErrorKind.CANCELED -> "已取消。"
        LlmErrorKind.PROTOCOL -> "返回格式不对。可能是网关不兼容 OpenAI 的流式协议。"
        LlmErrorKind.UNKNOWN -> "出了点问题，看日志里的详情。"
    }
}

/**
 * 按 HTTP 状态码分类。移植自 QQ-agent 的 `isRetryableError` 思路，
 * 但改成"分类 + 是否重试"两件事分开，重试策略由调用方决定。
 */
fun classifyHttpStatus(status: Int, bodyExcerpt: String = ""): LlmErrorKind = when (status) {
    400 -> LlmErrorKind.BAD_REQUEST
    401, 403 -> LlmErrorKind.AUTH
    404 -> LlmErrorKind.NOT_FOUND
    408 -> LlmErrorKind.TIMEOUT
    429 -> LlmErrorKind.RATE_LIMIT
    in 500..599 -> LlmErrorKind.SERVER
    else -> if (bodyExcerpt.contains("model", ignoreCase = true) &&
        bodyExcerpt.contains("not", ignoreCase = true)
    ) {
        LlmErrorKind.NOT_FOUND
    } else {
        LlmErrorKind.UNKNOWN
    }
}
