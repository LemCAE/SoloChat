package app.solochat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.solochat.MainActivity
import app.solochat.R
import app.solochat.SoloChatApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻服务。
 *
 * 存在的理由是**主动消息要可靠**（见 docs/01-decisions.md D2）：
 * Android 的 Doze 与后台限制会掐掉普通定时器，而"她两小时没等到你回就发一句"
 * 必须落在某个稳定运行的东西上。前景服务 + 常驻通知是唯一不需要 ROOT 的办法
 * （RikkaHub 用的是同一个做法）。
 *
 * 第一版它只做两件事：保持进程常驻、按节拍检查有没有会话该主动开口。
 * 真正的主动消息生成留到后面的阶段（现在检查出来也只是通知，不会自动发消息）——
 * 因为"什么时候该主动"是个需要反复调的策略问题，不该和常驻机制绑在一起交付。
 *
 * 关于 Android 14+：Manifest 里已经声明 `foregroundServiceType="dataSync"`，
 * 这是阶段 1 就必须定的一件事（事后补会牵动权限与发布流程）。
 */
class AgentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    private val app: SoloChatApp get() = application as SoloChatApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("保持常驻，以便及时回你消息"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ticker == null) startTicker()
        return START_STICKY
    }

    /**
     * 心跳。
     *
     * 节拍取 60 秒：主动消息的判断不需要更细的粒度，而这个频率对电量的影响可以忽略。
     * 用 `delay` 而不是 `AlarmManager`：我们已经在服务里了，没必要再叠一套调度 ——
     * 叠上去只会多一处会被 ROM 掐掉的机制。
     */
    private fun startTicker() {
        ticker = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                runCatching { checkProactive() }
            }
        }
    }

    /**
     * 检查有没有会话"该主动开口了"。
     *
     * 第一版只做到"判断 + 更新通知文案"，不自动生成消息。这个边界是刻意的：
     * 主动打断用户是**有代价**的（注意力、可信度），要先把判断逻辑调准。
     */
    private suspend fun checkProactive() {
        val now = System.currentTimeMillis()
        val conversations = runCatching { app.store.listConversations() }.getOrNull() ?: return
        for (conversation in conversations) {
            if (now < conversation.proactiveCooldownUntil) continue
            val recent = app.store.recentMessages(conversation.id, limit = 4)
            val last = recent.lastOrNull() ?: continue
            val idle = now - last.createdAt
            if (idle < PROACTIVE_IDLE_MS) continue

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    "${conversation.title} 已经 ${idle / 60_000} 分钟没动静了",
                    conversation.id
                )
            )
            return
        }
    }

    private fun buildNotification(text: String, conversationId: String? = null): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_legacy)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // 用 LOW：常驻通知只该是一条安静的"还在运行"，不该响也不该弹
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "solochat.keepalive"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_MS = 60_000L

        /** 静默多久算"该主动开口了"。30 分钟。 */
        private const val PROACTIVE_IDLE_MS = 30 * 60_000L

        const val ACTION_STOP = "app.solochat.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
