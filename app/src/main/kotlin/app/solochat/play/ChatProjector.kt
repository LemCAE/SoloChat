package app.solochat.play

import app.solochat.core.model.ChatMode
import app.solochat.core.model.Event
import app.solochat.core.model.EventKind
import app.solochat.core.model.Message
import app.solochat.core.model.MessageRole
import app.solochat.core.model.UserUtterance

/**
 * 聊天界面上的一个条目。
 *
 * 为什么要从「事件 + 消息」**投影**出这个模型，而不是让界面直接读 Event：
 * 界面需要的是"怎么摆"，而 Event 表达的是"发生了什么"。两者会分叉 ——
 * 例如三条 `say` 要归成一组（同组不重复头像/时间戳），
 * 而 `remember` 根本不该出现在界面上。把这层投影单独写出来，
 * 渲染代码就只剩纯排版，也才好断言。
 */
sealed class ChatItem {

    /** 我的消息。 */
    data class Mine(
        val id: Long,
        val text: String,
        val at: Long,
        /** 与上一条同组（同一个人、间隔短）时不重复显示头像与时间戳。 */
        val sameGroup: Boolean
    ) : ChatItem()

    /** 对方的消息气泡。 */
    data class Theirs(
        val id: Long,
        val text: String,
        val at: Long,
        val speaker: String,
        val sameGroup: Boolean
    ) : ChatItem()

    /** 动作描写（`act`）。面对面模式的主力。 */
    data class Action(
        val id: Long,
        val text: String,
        val speaker: String,
        val sameGroup: Boolean
    ) : ChatItem()

    /** 环境旁白（`scene`）。 */
    data class Narration(val id: Long, val text: String) : ChatItem()

    /**
     * **我**的动作描写（面对面模式里我写自己做了什么、感觉到什么）。
     *
     * 为什么和 [Action] 分开：那个是**对方**的动作（居中斜体、带角色名），
     * 这个是**我**的（靠右、不带名字）。混成一种就分不清谁做了什么 ——
     * 而"谁做的"正是面对面模式里最要紧的信息。
     *
     * 我的环境描写（`scene`）**不单独开一类**：环境是无人称的，谁描述都一样，
     * 所以直接复用 [Narration]。
     */
    data class MyAction(
        val id: Long,
        val text: String,
        val sameGroup: Boolean
    ) : ChatItem()

    /** 系统分隔行（模式切换、打断等）。 */
    data class Divider(val id: Long, val text: String) : ChatItem()

    /**
     * 正在演出的占位条目。
     *
     * 它和落盘的条目分开表示，是因为它**还没成为历史**：正在打字、还没有内容、
     * 被打断后会被撤掉。混进历史里会导致"重进页面看到半句话"这种脏数据。
     */
    data class Live(
        val key: String,
        /**
         * 对应的事件 id。
         *
         * 存在的唯一理由是**交接时能认出自己**：一条事件落定之后，它的活气泡必须让位。
         * 靠正文比对做不到 —— 同一个「嗯」在一轮里出现两次是合法的，
         * 「玩过一点」更是会跨轮重现（真机日志里连着一轮都出现过），比错了就会
         * 把新的一条误判成"已经落定"，于是它整条不显示。
         */
        val eventId: Long,
        val kind: EventKind,
        val text: String,
        val speaker: String,
        /**
         * 交付时刻，用于气泡旁的时间戳。
         *
         * 活气泡也要显示时间戳：否则气泡在"落定"那一刻会突然多出一个时间戳、
         * 把整行高度顶一下，看起来像闪了一下。
         */
        val at: Long = 0
        // 逐字出现的进度**不在这里**。
        //
        // 曾经有一个 `visibleChars` 字段，由引擎在切条时置 0，名义上用来告诉界面
        // "从头开始画"。但它从来没被读过 —— 界面那边用 `LaunchedEffect(live.key)`
        // 自己就把进度归零了，两条路径同时存在只会让人以为其中之一在起作用。
        // 真正防止"先冒全文、再重播"的是 **key 的变化**（见 ChatScreen），
        // 所以这里只留 key，不再放一份会误导人的进度副本。
    ) : ChatItem()
}

/**
 * 把历史事件与消息投影成界面条目。
 *
 * @param groupingWindowMs 归组的最大间隔：超过这个间隔就是新的一组（要显示时间戳）
 */
object ChatProjector {

    const val GROUP_WINDOW_MS = 3 * 60_000L

    /**
     * 时间线条目 —— 投影过程中的中间形态。
     *
     * 为什么要先转成"带时间戳的统一列表"再排序：第一版是"先把用户消息全放进去，
     * 再把事件按时间插进去"，结果时间线是错的（用户的两条会挤在一起，哪怕中间
     * 隔着对方的三条）。改成合并排序之后，语义就只剩一条：**按时间戳排列**。
     */
    private data class Slot(
        val at: Long,
        val order: Int,
        val item: ChatItem
    )

    fun project(events: List<Event>, messages: List<Message>): List<ChatItem> =
        projectVisible(events, messages).items

    /**
     * 投影的同时，把"这些条目各自对应哪条事件"也交出来。
     *
     * 为什么要多这一个返回值：正在播出的那条气泡（[ChatItem.Live]）和已落定的条目
     * 必须**只有一个在场**。判断"这条是不是已经在历史里了"只能靠事件 id ——
     * 拿正文去比会误判（同一个「嗯」可以合法地出现两次），所以交接逻辑需要 id。
     */
    fun projectVisible(
        events: List<Event>,
        messages: List<Message>
    ): Projection {
        val slots = mutableListOf<Slot>()
        /** 本次投影里出现了哪些**事件**（只收事件类条目，不含用户消息与分隔行）。 */
        val eventIds = mutableSetOf<Long>()

        // 用户消息 —— 按 [UserUtterance] 的标记决定画成哪一种。
        //
        // 用户在面对面里可以"说话 / 做动作 / 描环境"，三者在库里是同一个
        // `MessageRole.USER`，只靠 `Message.text` 的前后缀区分（见 UserUtterance 的注释：
        // 助手侧本来就是这么存带标记的文本的）。
        messages.filter { it.role == MessageRole.USER }.forEachIndexed { index, m ->
            val (kind, body) = UserUtterance.decode(m.text)
            val item: ChatItem = when (kind) {
                EventKind.ACT -> ChatItem.MyAction(id = m.id, text = body, sameGroup = false)
                EventKind.SCENE -> ChatItem.Narration(id = m.id, text = body)
                else -> ChatItem.Mine(id = m.id, text = body, at = m.createdAt, sameGroup = false)
            }
            slots += Slot(at = m.createdAt, order = index, item = item)
        }

        // 系统事实（如模式切换）→ 分隔行。它们的 at 用 createdAt。
        messages.filter { it.role == MessageRole.SYSTEM }.forEachIndexed { index, m ->
            slots += Slot(
                at = m.createdAt,
                order = index,
                item = ChatItem.Divider(id = m.id, text = m.text)
            )
        }

        // 事件 —— **只有"出口过"的才画**。
        //
        // ⚠️ 这里曾经只判断 `kind.isVisible`，代价是一个很难看的真机现象：
        // 助手事件在**模型返回后、播出前**就落盘了（`delivery = PENDING`），
        // 于是它们被当成历史条目画了出来 —— 屏幕上先一次性冒出两条带时间戳的气泡，
        // 紧接着活气泡又从第一条开始播动画，第二条再把它顶掉。
        // 用户描述的就是这一幕，而我在真机上抓不到它（dump 一次要 2.5 秒，
        // 那一幕只存在于两帧之间），最后是在 SessionControllerTest 里录帧录出来的。
        //
        // 判据用 `delivery.spoken`（说过 / 说了一半）而不是 `deliveredAt != null`：
        // 后者是**引擎字段**，谁顺手填一下就能让一条没出口的事件混进历史，
        // 而 `delivery` 是**领域状态**，只有真正播出过才会变 —— 拿它当门禁才拦得住。
        events.filter { it.kind.isVisible && it.delivery.spoken }.forEachIndexed { index, e ->
            val text = e.displayText ?: return@forEachIndexed
            val whenAt = e.deliveredAt ?: e.createdAt
            val item: ChatItem = when (e.kind) {
                EventKind.SAY -> ChatItem.Theirs(
                    id = e.id,
                    text = text,
                    at = whenAt,
                    speaker = e.speaker,
                    sameGroup = false
                )

                EventKind.ACT -> ChatItem.Action(
                    id = e.id,
                    text = text,
                    speaker = e.speaker,
                    sameGroup = false
                )

                EventKind.SCENE -> ChatItem.Narration(id = e.id, text = text)

                else -> return@forEachIndexed
            }
            eventIds += e.id
            slots += Slot(at = whenAt, order = index, item = item)
        }

        // 稳定排序：时间戳相同时用 order 保证顺序确定（否则同一毫秒内的两条可能每次渲染都换位置）
        val ordered = slots.sortedWith(compareBy({ it.at }, { it.order })).map { it.item }

        return Projection(items = regroup(ordered), eventIds = eventIds)
    }

    /**
     * 一次投影的产物。
     *
     * @param items 界面要画的条目
     * @param eventIds 这些条目里包含了哪些事件（交接活气泡时用来判断"这条已经落定了"）
     */
    data class Projection(
        val items: List<ChatItem>,
        val eventIds: Set<Long>
    )

    /**
     * 标出"与上一条同组"。
     *
     * 为什么需要它：真社交软件里，同一个人连发的几条不会重复显示头像和时间戳。
     * 判据是"同一个人 + 间隔在窗口内"。
     *
     * ⚠️ 第一版这里踩了 `null == null` 的坑：对方没有说话者时（旧数据、异常数据）
     * 两个 null 被判成"同一个人"，于是**第一条消息就被标成同组**，
     * 结果是整段对话都没有时间戳。所以这里显式要求说话者非空。
     */
    private fun regroup(items: List<ChatItem>): List<ChatItem> {
        var previousSpeaker: String? = null
        var previousAt = 0L

        return items.map { item ->
            val speaker: String? = when (item) {
                is ChatItem.Theirs -> item.speaker
                is ChatItem.Action -> item.speaker
                is ChatItem.Mine -> "\u0000me"      // 用不可能与真名冲突的哨兵
                is ChatItem.MyAction -> "\u0000me"  // 我的动作和我的话是同一个人
                is ChatItem.Narration -> null
                is ChatItem.Divider -> null
                is ChatItem.Live -> null
            }
            val at = when (item) {
                is ChatItem.Theirs -> item.at
                is ChatItem.Mine -> item.at
                else -> previousAt
            }

            val sameGroup = speaker != null &&
                speaker.isNotBlank() &&
                speaker == previousSpeaker &&
                previousSpeaker?.isNotBlank() == true &&
                (at - previousAt) <= GROUP_WINDOW_MS

            if (speaker != null && speaker.isNotBlank()) {
                previousSpeaker = speaker
                previousAt = at
            } else {
                // 旁白/分隔行会打断分组：它们之后的那条应当重新显示头像与时间戳
                previousSpeaker = null
            }

            when (item) {
                is ChatItem.Mine -> item.copy(sameGroup = sameGroup)
                is ChatItem.Theirs -> item.copy(sameGroup = sameGroup)
                is ChatItem.Action -> item.copy(sameGroup = sameGroup)
                is ChatItem.MyAction -> item.copy(sameGroup = sameGroup)
                else -> item
            }
        }
    }

    /**
     * 模式分隔行。
     *
     * 为什么值得单独显示：模式切换是这个应用最独特的一件事（见 docs/01-decisions.md D7），
     * 一条居中的"—— 你们见面了 ——"让时间轴上有明确的断点，
     * 否则用户会以为界面渲染出错了。
     */
    fun dividerFor(mode: ChatMode): String = when (mode) {
        ChatMode.SCENE -> "—— 见面了 ——"
        ChatMode.CHAT -> "—— 回到消息里 ——"
    }
}
