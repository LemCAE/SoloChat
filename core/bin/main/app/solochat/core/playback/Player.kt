package app.solochat.core.playback

import app.solochat.core.model.ChatMode
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.ModelPrice
import app.solochat.core.model.Usage
import app.solochat.core.model.estimateCost
import app.solochat.core.protocol.Pacing
import app.solochat.core.protocol.PacingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 界面需要看到的状态（[Player.State] 之外的部分）。
 */
data class PlaybackStatus(
    /** `say` 的正文，界面据此渲染气泡（逐个字出现由界面自己做）。 */
    val bubble: String? = null,

    /**
     * 气泡所属的轮次。界面用它把"同一轮的多条"归成一组
     * （组内不重复显示头像与时间戳）。
     */
    val turnId: Long = 0,

    val mode: ChatMode = ChatMode.CHAT,
    val kind: EventKind? = null,
    val speaker: String = ""
)

/**
 * 播放结果 —— 一场演出结束时的事实汇总。
 *
 * 刻意不叫 `Outcome`：它和 [Player.State] 一起被上层频繁使用，
 * 短名字容易与业务里其他"outcome"撞名（第一版就在同一个文件里踩过这个坑）。
 */
data class PlaybackOutcome(
    val events: List<Event>,
    val delivered: List<Event>,
    val canceled: List<Event>,
    val interrupted: Event?,
    val modeProposal: ChatMode?,
    val usage: Usage?,
    val cost: Double,
    val rough: Boolean
)

/** 播放过程中的一步：先进入某个状态，等一段（虚拟）时间，再推进。 */
data class SequenceStep(
    val state: Player.State,
    val waitMs: Long
)

/** 一整场演出的计划：状态序列 + 结束时的结果。 */
data class PlaybackPlan(
    val states: List<SequenceStep>,
    val outcome: PlaybackOutcome
)

/**
 * 播放引擎 —— 把"已落盘的事件脚本"按节奏变成屏幕上的气泡。
 *
 * 这是全项目最容易写错的地方（见 docs/01-decisions.md D4），所以它只有一件事要做：
 * **维护五个播出状态之间的转移，并且永不混淆"说过"和"没说过"**。
 *
 * 与界面/动画的分工：
 * - 本类负责**时序与状态**，不负责动画。逐字出现是渲染层的事（不需要被测试）。
 * - 时间不进本类：计划里的 [SequenceStep.waitMs] 由调用方交给虚拟/真实时钟去等。
 *
 * 为什么先算出整条状态序列、而不是边播边回调：
 * 播放必然有"中途被打断"这个分支。把它变成**纯数据的计划**，就能对打断发生在
 * 任意一个时刻做穷举测试（见 PlayerTest 的中断矩阵），而不必写时序相关的 flaky 测试。
 */
object Player {

    /** 现在该画什么。 */
    sealed class State {
        /** 空闲：没有正在进行的演出。 */
        data object Idle : State()

        /** 阅读延迟：对方刚看到消息，还没开始打字（界面显示"对方正在输入"更自然）。 */
        data class Reading(val eventId: Long) : State()

        /** 正在打字：界面显示"正在输入…"。 */
        data class Typing(val eventId: Long) : State()

        /** 正在播出内容。 */
        data class Delivering(val status: PlaybackStatus) : State()

        /** 两条之间的间隔。 */
        data class Gap(val eventId: Long) : State()

        /** 演出结束。 */
        data class Done(val events: List<Event>) : State()
    }

    /**
     * 计算整场演出的状态序列。**纯计算，不做任何等待、不碰存储**。
     *
     * @param readDelayMs "看完消息"的延迟（由 [Pacing.readDelay] 决定）
     */
    fun plan(
        events: List<Event>,
        pacing: Pacing,
        readDelayMs: Long,
        config: PacingConfig = PacingConfig()
    ): PlaybackPlan {
        val steps = mutableListOf<SequenceStep>()
        val visible = events.filter { it.kind.isVisible && it.displayText != null }

        val emptyOutcome = PlaybackOutcome(
            events = events,
            delivered = emptyList(),
            canceled = emptyList(),
            interrupted = null,
            modeProposal = null,
            usage = null,
            cost = 0.0,
            rough = true
        )

        // 一条要播的都没有（纯 wait / remember）：不演出。这不是错误，是正常的一轮。
        if (visible.isEmpty()) return PlaybackPlan(states = emptyList(), outcome = emptyOutcome)

        if (readDelayMs > 0) {
            steps += SequenceStep(State.Reading(visible.first().id), readDelayMs)
        }

        var first = true
        for (event in visible) {
            val pace = pacing.pace(
                text = event.displayText.orEmpty(),
                gapHintMs = event.gapHintMs,
                typingHintMs = event.typingHintMs,
                // 面对面走的是**另一套节奏**：不算打字时间、动作与旁白忽略字数。
                // 见 `Pacing.scenePace` —— 那里解释了为什么同一个公式套两种模式是错的。
                sceneKind = event.kind.takeIf { event.mode == ChatMode.SCENE }
            )

            // 第一条之前已经有"阅读延迟"，不再叠加间隔 —— 否则对方像卡了两次
            if (!first && pace.gapMs > 0) {
                steps += SequenceStep(State.Gap(event.id), pace.gapMs)
            }

            if (pace.typingMs > 0) {
                steps += SequenceStep(
                    State.Typing(event.id),
                    // 打字时长不能超过上限，否则界面会长时间空转
                    pace.typingMs.coerceAtMost(config.typingMaxMs)
                )
            }

            steps += SequenceStep(
                State.Delivering(
                    PlaybackStatus(
                        bubble = event.displayText,
                        turnId = event.turnId,
                        mode = event.mode,
                        kind = event.kind,
                        speaker = event.speaker
                    )
                ),
                0
            )
            first = false
        }

        return PlaybackPlan(
            states = steps,
            outcome = emptyOutcome.copy(delivered = visible)
        )
    }

    /**
     * 给结果补上记账信息与模式提议。
     * 模式提议由上层传入 —— 要不要真的切模式是**策略**，不该由播放器定。
     */
    fun finish(
        outcome: PlaybackOutcome,
        modeProposal: ChatMode?,
        usage: Usage?,
        price: ModelPrice?
    ): PlaybackOutcome {
        val cost = if (usage != null && price != null) estimateCost(usage, price) else null
        return outcome.copy(
            modeProposal = modeProposal,
            usage = usage,
            cost = cost?.money ?: 0.0,
            rough = cost?.rough ?: true
        )
    }

    /**
     * 中断：把"还没播完"的那部分标出来。
     *
     * 语义（这是整个设计里最要紧的一条）：
     * - 已经播出的 → 就是说过了，**不回滚**（像真人发了就发了）
     * - 正在播的那条 → "说了一半"，下一轮上下文要带上
     * - 后面没播的 → **当没说过，绝不进上下文**，否则模型会以为自己说过
     */
    fun interrupt(
        outcome: PlaybackOutcome,
        playedCount: Int,
        playingIndex: Int?
    ): PlaybackOutcome {
        val delivered = outcome.delivered.take(playedCount.coerceAtLeast(0))
        val interrupted = playingIndex?.let { outcome.delivered.getOrNull(it) }
        // 取消的是"在被打断那条之后"的全部；如果没在播，就是 playedCount 之后的全部
        val cancelFrom = when {
            playingIndex != null -> playingIndex + 1
            else -> playedCount
        }
        val canceled = outcome.delivered.drop(cancelFrom.coerceAtLeast(0))
        return outcome.copy(
            delivered = delivered,
            interrupted = interrupted,
            canceled = canceled
        )
    }
}

/**
 * 演出之间的互斥与取消。
 *
 * 为什么需要它：用户可以在任何时刻插话，而插话必须**立刻**取消尚未播出的部分。
 * 不用互斥保护的话，会出现"作废正在遍历列表时列表被推出新事件"这类竞态 ——
 * 表现是偶发的"消息重复出现"或"少了一条"，极难复现。
 */
class PlaybackGate {
    private val mutex = Mutex()
    private var generation = 0

    /** 开始一场新演出，作废之前所有的。返回本场代次。 */
    suspend fun newGeneration(): Int = mutex.withLock {
        generation += 1
        generation
    }

    /** 作废当前演出（用户插话时调用）。 */
    suspend fun invalidate(): Int = mutex.withLock {
        generation += 1
        generation
    }

    /** 本场是否仍然有效（用户没插话、没开新一轮）。 */
    suspend fun isCurrent(token: Int): Boolean = mutex.withLock { token == generation }

    /** 在锁内跑一段逻辑，保证与 [invalidate] 互斥。 */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/** 播放被用户插话打断。属于正常路径，不该当成错误上报给用户。 */
class PlaybackInterruptedException : CancellationException("播放被用户插话打断")
