# 引导 Gradle wrapper：本机没有全局 gradle，所以先下一份发行版，
# 用它自带的 wrapper 任务生成项目里的 gradlew / gradlew.bat / gradle-wrapper.jar。
#
# 为什么不用 curl.exe：这台机器上 curl 的 schannel 后端报
# SEC_E_NO_CREDENTIALS（"安全程序包没有可用的凭证"），任何 https 都下不了。
# 改用 .NET 的 TLS 栈（Invoke-WebRequest / HttpClient），它能正常握手。
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\JDK21'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle"

$work = 'D:\Project\QQBot\SoloChat\.bootstrap'
$zip = Join-Path $work 'gradle-8.9-bin.zip'
$extractDir = Join-Path $work 'dist'

# 发行版来源：主站优先，失败退到国内镜像（两处都是官方/镜像的同一份文件）
$sources = @(
    'https://services.gradle.org/distributions/gradle-8.9-bin.zip',
    'https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip',
    'https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.9-bin.zip',
    'https://downloads.gradle.org/distributions/gradle-8.9-bin.zip'
)

New-Item -ItemType Directory -Force -Path $work | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-Zip($url, $dest) {
    Write-Host "[bootstrap] 尝试下载：$url"
    try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromMinutes(10)
        $resp = $client.GetAsync($url, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
        if (-not $resp.IsSuccessStatusCode) {
            Write-Host "[bootstrap]   HTTP $([int]$resp.StatusCode)，换下一个源"
            return $false
        }
        $src = $resp.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $fs = [IO.File]::Create($dest)
        try { $src.CopyTo($fs) } finally { $fs.Dispose(); $src.Dispose() }
        $sw.Stop()
        $mb = [math]::Round((Get-Item $dest).Length / 1MB, 1)
        Write-Host "[bootstrap]   完成：$mb MB，用时 $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
        return $true
    } catch {
        Write-Host "[bootstrap]   失败：$($_.Exception.Message)"
        return $false
    }
}

if (-not (Test-Path $zip)) {
    $ok = $false
    foreach ($url in $sources) {
        if (Get-Zip $url $zip) { $ok = $true; break }
    }
    if (-not $ok) { throw "所有下载源都失败了，检查网络或代理设置" }
} else {
    Write-Host "[bootstrap] 已有压缩包，跳过下载"
}

if (-not (Test-Path (Join-Path $extractDir 'gradle-8.9'))) {
    Write-Host "[bootstrap] 解压 ..."
    Expand-Archive -Path $zip -DestinationPath $extractDir -Force
}

$gradleBat = Join-Path $extractDir 'gradle-8.9\bin\gradle.bat'
if (-not (Test-Path $gradleBat)) { throw "没找到 $gradleBat" }

# ---- Gradle 全局镜像：只写进 GRADLE_USER_HOME，不污染项目仓库 ----
$initDir = Join-Path $env:GRADLE_USER_HOME 'init.d'
New-Item -ItemType Directory -Force -Path $initDir | Out-Null
$initFile = Join-Path $initDir 'solochat-mirrors.gradle'
$initBody = @'
// 由 SoloChat/tools/bootstrap-wrapper.ps1 生成。
// 作用：把依赖仓库指向国内镜像，避免首次构建解析依赖时长时间卡住。
// 这是**机器级**配置，不在项目仓库里，换机器重新跑一次引导脚本即可。
allprojects {
    buildscript {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/public' }
            maven { url 'https://maven.aliyun.com/repository/google' }
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
        }
    }
    repositories {
        maven { url 'https://maven.aliyun.com/repository/public' }
        maven { url 'https://maven.aliyun.com/repository/google' }
    }
}
settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
            maven { url 'https://maven.aliyun.com/repository/public' }
            gradlePluginPortal()
            google()
            mavenCentral()
        }
    }
}
'@
Set-Content -Path $initFile -Value $initBody -Encoding UTF8
Write-Host "[bootstrap] 已写入镜像初始化脚本：$initFile"

Write-Host "[bootstrap] 生成 wrapper ..."
& $gradleBat wrapper --gradle-version 8.9 --distribution-type bin --no-daemon `
    -p 'D:\Project\QQBot\SoloChat'
if ($LASTEXITCODE -ne 0) { throw "wrapper 任务失败，退出码 $LASTEXITCODE" }

Write-Host "[bootstrap] 完成，产物："
Get-ChildItem 'D:\Project\QQBot\SoloChat' -Filter 'gradlew*' | Select-Object -ExpandProperty Name
Get-ChildItem 'D:\Project\QQBot\SoloChat\gradle\wrapper' -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name
