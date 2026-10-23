package app.solochat.core.update

/** 由 Android 读取真实包信息；版本标签不参与覆盖判断。证书集合只含当前签名者。 */
data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String,
    val signers: Set<String>, val minSdk: Int = 26)

data class ApkCompatibility(val allowed: Boolean, val message: String)

fun checkApkCompatibility(installed: ApkIdentity, candidate: ApkIdentity?, deviceSdk: Int): ApkCompatibility {
    fun blocked(message: String) = ApkCompatibility(false, message)
    if (installed.packageName != "app.solochat") return blocked(
        "当前为独立调试安装，资料与正式版分开。请先导出备份，再在正式版手动恢复副本；不能直接覆盖迁移。")
    if (candidate == null) return blocked("无法读取安装包或签名，请重新下载。")
    if (candidate.packageName != installed.packageName) return blocked("安装包包名不一致，不能覆盖当前应用。")
    if (candidate.versionCode < installed.versionCode) return blocked("安装包版本号低于当前安装，不支持降级。")
    if (candidate.minSdk > deviceSdk) return blocked("安装包要求更高的 Android 版本，当前设备无法安装。")
    // 首版保守要求当前签名者完全一致；不自行推断证书轮换可兼容。
    if (installed.signers.isEmpty() || candidate.signers.isEmpty()) return blocked("无法确认安装包签名，不能继续安装。")
    if (candidate.signers != installed.signers) return blocked("安装包签名与当前安装不一致，不能覆盖；请勿卸载或清数据。")
    return ApkCompatibility(true, if (candidate.versionCode == installed.versionCode)
        "包名、版本号和当前签名者检查通过，可交给系统确认同版本覆盖。" else
        "包名、版本号和当前签名者检查通过，可交给系统确认升级。")
}
