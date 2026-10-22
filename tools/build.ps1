# SoloChat 本机构建入口。
#
# 为什么需要它：这台机器上有四处环境约束，手敲 gradlew 每次都会踩：
#
# 1. **JAVA_HOME** 必须是 JDK 21（本机也只有它）。
# 2. **GRADLE_USER_HOME** 必须在工作区内 —— 沙箱不允许写 C:\Users\tenge\.gradle。
# 3. **ANDROID_PREFS_ROOT** 也必须在工作区内。AGP 的 debug 签名密钥固定在
#    `$ANDROID_PREFS_ROOT/.android/debug.keystore`（没设时才回退 user.home），
#    而 `-Duser.home=...` 经 Gradle 守护进程会丢失 —— 实测无效，只有这个环境变量管用。
# 4. **sdk.dir** 写在 local.properties 里（脚本会自动补，见文末）。
#
# 用 Android Studio 打开时不用本脚本，但你得自己在 IDE 的 Gradle JDK 里选 JDK 21，
# 并把 ANDROID_PREFS_ROOT 加进环境变量，否则会碰到同样的签名错误。
#
# 用法：
#   pwsh tools/build.ps1                    # 编译 debug APK
#   pwsh tools/build.ps1 :core:test         # 只跑纯 Kotlin 单元测试
#   pwsh tools/build.ps1 test               # 跑所有单测
#   pwsh tools/build.ps1 clean              # 清理
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @(':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$jdk = if ($env:CI -eq 'true') { $env:JAVA_HOME } else { 'D:\JDK21' }
if (-not (Test-Path $jdk)) {
    throw "找不到 JDK：$jdk。改本脚本顶部的 `$jdk，或装一个 JDK 21。"
}
$env:JAVA_HOME = $jdk

$env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'
$env:ANDROID_PREFS_ROOT = Join-Path $root '.userhome'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $env:ANDROID_PREFS_ROOT | Out-Null

if (-not (Test-Path (Join-Path $root 'local.properties'))) {
    # SDK 路径按机器而异；统一用正斜杠，避免 Java properties 吞掉 Windows 反斜杠。
    $sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
           elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
           else { 'D:\AndroidSDK' }
    Set-Content -Path (Join-Path $root 'local.properties') -Value "sdk.dir=$($sdk.Replace('\', '/'))" -Encoding UTF8
    Write-Host "[build] 已写入 local.properties（sdk.dir=$sdk）"
}

if (-not (Test-Path (Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'))) {
    throw "缺少 gradle wrapper。先跑：pwsh tools/download-gradle.mjs && pwsh tools/make-wrapper.ps1"
}

Write-Host "[build] 任务：$($Tasks -join ' ')"
& (Join-Path $root 'gradlew.bat') @Tasks --no-daemon --console=plain
$code = $LASTEXITCODE

if ($code -eq 0 -and ($Tasks -contains ':app:assembleDebug')) {
    $apk = Get-ChildItem (Join-Path $root 'app\build\outputs\apk\debug') -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        Write-Host ""
        Write-Host "[build] 产物：$($apk.FullName)  ($([math]::Round($apk.Length/1MB,1)) MB)"
        Write-Host "[build] 此 debug 包仅供编译检查/明确要求的隔离安装；默认实机预览请使用 tools/build-preview.ps1 的同签名 release 包。"
    }
}

exit $code
