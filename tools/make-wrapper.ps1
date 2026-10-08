# 用下载好的归档生成 Gradle wrapper。
#
# 前一步（tools/download-gradle.mjs）已经保证归档存在且大小合理 —— 之所以拆成两步，
# 是因为下载必须走 Node（这台机器的 PowerShell TLS 出不去），而解压与跑任务用 PowerShell 更顺。
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\JDK21'
# GRADLE_USER_HOME 指向工作区内，而不是 ~/.gradle。
#
# 原因：本机的文件沙箱不允许写用户主目录（实测 "Access to the path
# 'C:\Users\tenge\.gradle' is denied"）。放在项目里还有一个好处 ——
# 依赖缓存与构建产物全在一起，删掉目录即彻底清理，不会污染系统。
# 代价是换机器要重新下载依赖，这对单机开发可以接受。
$env:GRADLE_USER_HOME = 'D:\Project\QQBot\SoloChat\.gradle-home'
$root = 'D:\Project\QQBot\SoloChat'
$work = Join-Path $root '.bootstrap'
$zip = Join-Path $work 'gradle-8.9-bin.zip'
$extractDir = Join-Path $work 'dist'

if (-not (Test-Path $zip)) { throw "缺少归档：$zip（先跑 node tools/download-gradle.mjs）" }
$size = (Get-Item $zip).Length
if ($size -lt 80MB) { throw "归档偏小（$size 字节），疑似下载不完整" }

if (-not (Test-Path (Join-Path $extractDir 'gradle-8.9'))) {
    Write-Host "[wrapper] 解压 $([math]::Round($size/1MB,1)) MB ..."
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    Expand-Archive -Path $zip -DestinationPath $extractDir -Force
}

$gradleBat = Join-Path $extractDir 'gradle-8.9\bin\gradle.bat'
if (-not (Test-Path $gradleBat)) { throw "没找到 $gradleBat" }

# ---- 机器级 init 脚本：清掉历史遗留 ----
#
# 这个项目**不需要**任何机器级 init 脚本：依赖镜像写在 settings.gradle.kts 里
# （项目自洽，换机器克隆下来就是对的）。之前两个版本分别尝试过"注入仓库"
# 和"关掉文件监视"，前者与 FAIL_ON_PROJECT_REPOS 冲突，后者在 8.9 上属性名不对
# （StartParameter 没有 isFileSystemWatchingEnabled）。所以这里只做清理。
$initDir = Join-Path $env:GRADLE_USER_HOME 'init.d'
foreach ($stale in @('solochat-mirrors.gradle', 'solochat-machine.gradle')) {
    $path = Join-Path $initDir $stale
    if (Test-Path $path) {
        Remove-Item $path -Force
        Write-Host "[wrapper] 已清理遗留 init 脚本：$path"
    }
}

Write-Host "[wrapper] 生成 wrapper ..."
# 指定 --gradle-distribution-url 而不是 --gradle-version：
# 后者会让 wrapper 任务去**探测** services.gradle.org 是否可达，
# 而这台机器的 Java/DNS 探测在 TLS 阶段就超时（脚本卡 80 秒然后失败）。
# 指到腾讯镜像的同一份归档即可（sha256 已核对为官方值
# d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab）。
#
# 后果：gradle-wrapper.properties 里会写镜像地址。这对国内网络反而是优点；
# 想换回官方源就改那一行，wrapper 的行为与版本完全一致。
$distUrl = 'https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip'
# --no-watch-fs：这台机器上 Gradle 的 native watcher 起不来
# （"Couldn't open current thread, error = 5"），加了它就不会每次刷一段栈。
& $gradleBat wrapper --gradle-distribution-url $distUrl --distribution-type bin --no-daemon `
    --no-watch-fs -p $root
if ($LASTEXITCODE -ne 0) { throw "wrapper 任务失败，退出码 $LASTEXITCODE" }

Write-Host "[wrapper] 产物："
Get-ChildItem $root -Filter 'gradlew*' | ForEach-Object { '  ' + $_.Name }
Get-ChildItem (Join-Path $root 'gradle\wrapper') -ErrorAction SilentlyContinue |
    ForEach-Object { '  gradle/wrapper/' + $_.Name + '  ' + $_.Length + ' bytes' }
