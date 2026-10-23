package app.solochat.core.model

import app.solochat.core.llm.LlmDelta
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable enum class TokenSource { RETURNED, ESTIMATED, UNKNOWN, LEGACY }
@Serializable data class TokenAmount(val value: Int? = null, val source: TokenSource = TokenSource.UNKNOWN) {
    init { require(value == null || value >= 0) }
}
@Serializable enum class CallKind { CHAT, SUMMARY }
@Serializable enum class CallStatus { STARTED, SUCCESS, SILENT, FAILED, CANCELED, INTERRUPTED }

/** 每个实际请求一条；价格在发起前冻结，旧版 usage 不回填来源或价格。 */
@Serializable data class CallRecord(
    val id: String,
    val operationId: String = id,
    val conversationId: String,
    val model: String,
    val kind: CallKind = CallKind.CHAT,
    val status: CallStatus = CallStatus.STARTED,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val input: TokenAmount = TokenAmount(),
    val output: TokenAmount = TokenAmount(),
    val cached: TokenAmount = TokenAmount(),
    val reasoning: TokenAmount = TokenAmount(),
    val price: ModelPrice,
    val inputCharacters: Int,
    val outputCharacters: Int = 0,
    val outputIncomplete: Boolean = false,
    /** 当前兼容协议的 completion_tokens 包括推理明细，不重复相加。 */
    val billingBasis: String = "OUTPUT_INCLUDES_REASONING",
    val failureKind: String = "",
    val contextMessageIds: List<Long> = emptyList(),
    val stablePrefixChars: Int = 0,
    val contextEstimate: Int = 0,
    /** 接口实际返回的 usage 分片；旧记录缺失时保持空，不伪造回填。 */
    val usageFrames: List<String> = emptyList()
)

/** 保留早先分片的有效字段，后续缺字段的分片不能把它们抹成零。 */
fun mergeUsage(previous: LlmDelta.Finished?, next: LlmDelta.Finished) = LlmDelta.Finished(
    next.promptTokens ?: previous?.promptTokens,
    next.completionTokens ?: previous?.completionTokens,
    next.cachedTokens ?: previous?.cachedTokens,
    next.reasoningTokens ?: previous?.reasoningTokens,
    (previous?.usageFrames.orEmpty() + next.usageFrames).distinct()
)

data class MeasuredUsage(val input: TokenAmount, val output: TokenAmount, val cached: TokenAmount, val reasoning: TokenAmount)
fun measuredUsage(returned: LlmDelta.Finished?, inputEstimate: Int, receivedOutputEstimate: Int,
                  outputAvailable: Boolean): MeasuredUsage {
    fun amount(actual: Int?, fallback: Int? = null) = when {
        actual != null && actual >= 0 -> TokenAmount(actual, TokenSource.RETURNED)
        fallback != null -> TokenAmount(fallback, TokenSource.ESTIMATED)
        else -> TokenAmount()
    }
    val input = amount(returned?.promptTokens, inputEstimate)
    val output = amount(returned?.completionTokens, receivedOutputEstimate.takeIf { outputAvailable })
    // 不把畸形的缓存分布当作可信精确数值。
    val cached = amount(returned?.cachedTokens?.takeIf { it <= (input.value ?: Int.MAX_VALUE) })
    return MeasuredUsage(input, output, cached, amount(returned?.reasoningTokens))
}

/** 兼容旧控制器事件与 usage 表；精度以 call_record 为准，不能用这些零值冒充已知。 */
fun CallRecord.toLegacyUsage() = Usage(input.value ?: 0, output.value ?: 0, cached.value ?: 0,
    reasoning.value ?: 0, estimated = input.source != TokenSource.RETURNED || output.source != TokenSource.RETURNED)

@Serializable data class RecordedCost(val low: Double? = null, val high: Double? = null,
    val approximate: Boolean = true, val note: String = "")

/** 缓存未知且有优惠时给范围；缺输入或输出时费用未知，绝不以零补齐完整金额。 */
fun recordedCost(call: CallRecord): RecordedCost {
    val price = call.price.forInput(call.input)
    if (!price.known) return RecordedCost(note = price.source.ifBlank { "调用时未配置价格" })
    if (price.currency != "CNY") return RecordedCost(note = "币种 ${price.currency} 需单独汇总")
    val input = call.input.value
    val output = call.output.value
    if (input == null || output == null) return RecordedCost(note = "用量不完整，费用未知")
    val cachedRate = if (price.cachedPriceKnown) price.cachedPerM else price.inputPerM
    val cached = call.cached.value
    val outputCost = output / 1_000_000.0 * price.outputPerM
    val approximate = call.input.source != TokenSource.RETURNED || call.output.source != TokenSource.RETURNED || call.outputIncomplete
    if (cached == null && cachedRate != price.inputPerM) {
        return RecordedCost((input * min(cachedRate, price.inputPerM)) / 1_000_000 + outputCost,
            (input * max(cachedRate, price.inputPerM)) / 1_000_000 + outputCost, true,
            "缓存分布未知" + if (call.outputIncomplete) "；输出不完整" else "")
    }
    val hit = (cached ?: 0).coerceIn(0, input)
    val money = ((input - hit) * price.inputPerM + hit * cachedRate) / 1_000_000 + outputCost
    return RecordedCost(money, money, approximate,
        if (call.outputIncomplete) "输出不完整，仅按收到的部分估算" else if (approximate) "含本地 token 估算" else "按返回用量与冻结单价估算")
}

data class UsageTotals(val calls: Int, val input: Long, val output: Long,
    val missingInput: Int, val missingOutput: Int, val legacyCalls: Int,
    val costLow: Double, val costHigh: Double, val missingCost: Int)

/** legacy 必须是没有关联 call_record 的原行，防止同一次调用被重复汇总。 */
fun summarizeCalls(calls: List<CallRecord>, legacy: List<Usage>): UsageTotals {
    val costs = calls.map(::recordedCost)
    return UsageTotals(calls.size + legacy.size,
        calls.sumOf { (it.input.value ?: 0).toLong() } + legacy.sumOf { it.promptTokens.toLong() },
        calls.sumOf { (it.output.value ?: 0).toLong() } + legacy.sumOf { it.completionTokens.toLong() },
        calls.count { it.input.value == null }, calls.count { it.output.value == null }, legacy.size,
        costs.sumOf { it.low ?: 0.0 }, costs.sumOf { it.high ?: 0.0 }, costs.count { it.low == null } + legacy.size)
}
