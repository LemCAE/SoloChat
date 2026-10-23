package app.solochat.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import app.solochat.core.update.*
import java.io.File
import java.io.IOException
import java.util.Base64

internal data class ApkInspection(val candidate: ApkIdentity?, val compatibility: ApkCompatibility)

/** 每次交给系统前重读文件和已安装身份，不把上一次检查当成永久许可。 */
internal class ApkInstaller(private val context: Context) {
    @Suppress("DEPRECATION")
    fun inspect(file: File, expectedSize: Long): ApkInspection {
        if (!file.isFile || file.length() != expectedSize) return ApkInspection(null,
            ApkCompatibility(false, "安装包已丢失或大小改变，请重新下载。"))
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return try {
            val installed = pm.getPackageInfo(context.packageName, flags).identity()
            val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)?.identity()
            ApkInspection(candidate, checkApkCompatibility(installed, candidate, Build.VERSION.SDK_INT))
        } catch (_: Exception) {
            ApkInspection(null, ApkCompatibility(false, "无法读取安装包或当前安装信息，请重新下载后重试。"))
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installIntent(file: File, expectedSize: Long): Intent {
        val inspection = inspect(file, expectedSize)
        if (!inspection.compatibility.allowed) throw IOException(inspection.compatibility.message)
        if (!canInstall()) throw IOException("请先允许 SoloChat 安装应用，再返回点击安装。")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("APK", uri) }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): ApkIdentity {
        val certificates = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
        return ApkIdentity(packageName, if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong(),
            versionName.orEmpty(), certificates.orEmpty().map { Base64.getEncoder().encodeToString(it.toByteArray()) }.toSet(),
            applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)
    }
}
