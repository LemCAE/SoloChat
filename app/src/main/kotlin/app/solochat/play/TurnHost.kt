package app.solochat.play

import app.solochat.core.agent.AgentDeps
import app.solochat.core.agent.ChatConfig
import app.solochat.core.protocol.PacingConfig

/**
 * [SessionController] 对外部世界的全部依赖。
 *
 * 为什么要有这个接口，而不是直接持有 `SoloChatApp`：直接依赖 Application 会让
 * [SessionController] **无法在纯 JVM 测试里构造**，而这个类恰好承载着最容易出错、
 * 也最需要回归测试的逻辑（防抖与落盘的原子性、播放与抢占）。
 * 实测代价很大：第一版因为这一点，一个"同一批消息被投两遍"的 bug 只能靠人肉看截图发现。
 */
interface TurnHost {
    val deps: AgentDeps

    /** 每次都要读最新值：用户在设置页改完立刻生效。 */
    fun config(): ChatConfig

    /** 节奏参数（每字耗时、打字上限等）。 */
    fun pacingConfig(): PacingConfig

    /** 让宿主在空闲时重算"用户连发节奏"的学习结果。 */
    fun refreshRhythm()
}
