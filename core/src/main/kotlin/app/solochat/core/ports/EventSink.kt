package app.solochat.core.ports

import app.solochat.core.model.DeliveryState

/**
 * 播出状态的写入端。
 *
 * 为什么单独一个接口、而不是塞进 [StoragePort]：这两个操作的调用者完全不同 ——
 * `StoragePort` 是 Agent 装配上下文时读的东西（只读为主），
 * 而这里是**播放器**在播出过程中翻状态的地方，要求"逐条、幂等、能撤销"。
 *
 * 为什么必须抽出来：第一版 [app.solochat.play.SessionController] 直接依赖具体的
 * `RoomStore`，于是它无法在纯 JVM 测试里构造 —— 而它恰好承载着最容易出错、
 * 也最需要回归测试的逻辑（防抖与落盘的原子性、播出与抢占）。
 * 实测代价很大：一个"用户消息成对出现"的 bug 只能靠看截图发现。
 */
interface EventSink {

    /**
     * 标记为"已经说出去了"。
     *
     * ⚠️ 必须在**播出的那一刻**调用，不能等整场演出结束再统一补 ——
     * 界面是拿 `deliveredAt` 排序的，统一补会让同一轮的多条共用一个时间戳，
     * 于是它们的时间戳全都一样、在屏幕上同时落定（用户会以为是一起蹦出来的）。
     */
    suspend fun markDelivered(id: Long, at: Long, gapMs: Int = 0, typingMs: Int = 0)

    /**
     * 标记一条事件"说了一半"就被打断（下一轮上下文要带上这件事）。
     *
     * ⚠️ `at` 必须是**它开始播出的那一刻**，不能传"打断发生的那一刻"。
     *
     * 这一条踩过：原来所有被切断的事件都统一盖当前时间，于是那条**用户早就看到**
     * 的气泡会被重新戳上打断时间，在时间轴上往后跳（真机实测：16:34:27 播出的那条
     * 被盖成了 16:34:31）。用户看到的是"我已经看到过的那句话跑到下面去了"。
     */
    suspend fun markInterrupted(id: Long, at: Long)

    /** 标记一条事件被取消（当没说过）。 */
    suspend fun setDelivery(id: Long, delivery: DeliveryState)
}
