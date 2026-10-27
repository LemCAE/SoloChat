package app.solochat.test

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** instrumentation 不启动 SoloChatApp，避免测试进程触发正式数据库的启动收尾逻辑。 */
class IsolatedAndroidJUnitRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}
