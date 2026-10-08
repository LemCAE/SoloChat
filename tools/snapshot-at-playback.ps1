param(
    [Parameter(Mandatory = $true)][string]$Text,
    [string]$Password = "tez071216",
    [string]$Base = "http://192.168.5.6:8787",
    [int]$TimeoutSec = 60
)

# 在"第 2 条开始播出"的那一刻打一份屏幕快照。
#
# 为什么要盯着日志而不是定时采样：播出的里程碑只出现在日志里，而 `uiautomator dump`
# 一次要 ~0.5s。定时采样很难正好落在那几秒的窗口里（我试了两轮都错过），
# 盯着日志则能在里程碑出现后**立刻**打快照。
#
# 顺带解决一个更根本的问题：这台机器上 `screenrecord` 完全不可用
# （SELinux 不让 shell 写 /sdcard 与 /data/local/tmp，exec-out 流式也拿不到），
# 所以"截图"这件事只能退化成"打一份屏幕状态快照"。

$adb = "D:\AndroidSDK\platform-tools\adb.exe"

function Get-Logs {
    return Invoke-RestMethod -Uri "$Base/api/logs?t=$Password&since=0" -TimeoutSec 10
}

function Get-Nodes {
    $xml = & $adb shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" | Out-String
    $list = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $n = $m.Value
        $list += [pscustomobject]@{
            desc   = [regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value
            cls    = [regex]::Match($n, 'class="([^"]*)"').Groups[1].Value
            bounds = [regex]::Match($n, 'bounds="([^"]*)"').Groups[1].Value
            text   = [regex]::Match($n, 'text="([^"]*)"').Groups[1].Value
        }
    }
    return $list
}

function Get-Center([string]$b) {
    $m = [regex]::Match($b, '\[(\d+),(\d+)\]\[(\d+),(\d+)\]')
    return @(
        [int](([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2),
        [int](([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2)
    )
}

# 先确保在聊天页：如果看不到输入框，就点进第一个会话
$edit = Get-Nodes | Where-Object { $_.cls -like "*EditText*" } | Select-Object -First 1
if (-not $edit) {
    Write-Output "不在聊天页，点进第一个会话…"
    $conv = Get-Nodes | Where-Object { $_.text -like "新的对话*" -or $_.text -like "测试会话*" } |
        Select-Object -First 1
    if (-not $conv) { throw "会话列表里没有可点的会话" }
    $cc = Get-Center $conv.bounds
    & $adb shell "input tap $($cc[0]) $($cc[1])" | Out-Null
    Start-Sleep -Seconds 2
    $edit = Get-Nodes | Where-Object { $_.cls -like "*EditText*" } | Select-Object -First 1
}
if (-not $edit) { throw "找不到输入框" }

# 点输入框、输入、重新查发送键（键盘会改变布局）、发送
$c = Get-Center $edit.bounds
& $adb shell "input tap $($c[0]) $($c[1])" | Out-Null
Start-Sleep -Milliseconds 800
& $adb shell "input text '$Text'" | Out-Null
Start-Sleep -Milliseconds 600
$send = Get-Nodes | Where-Object { $_.desc -eq "发送" } | Select-Object -First 1
$s = Get-Center $send.bounds
& $adb shell "input tap $($s[0]) $($s[1])" | Out-Null
Write-Output "已发送「$Text」@ $(Get-Date -Format 'HH:mm:ss.fff')"

# 盯着日志，等"第 2 条播出"这个里程碑
#
# 判据：**只有本次发送之后**出现的里程碑才算（一开始忘了加时间过滤，
# 于是它盯着上一次会话的旧日志反复触发 —— 白白跑了三轮）。
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$sentAt = (Get-Date).AddSeconds(-1)
$dumped = 0
$seenSecond = $false
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec -and $dumped -lt 3) {
    $logs = Get-Logs
    $milestones = $logs.entries |
        Where-Object { $_.text -like "*状态=播出*" -and [datetime]$_.at -gt $sentAt } |
        Select-Object -Last 4

    $second = $milestones | Where-Object { $_.text -like "*第 2/* 条*" } | Select-Object -Last 1
    if ($second -and -not $seenSecond) {
        $seenSecond = $true
        # 里程碑刚出现，立刻打快照：这时第 1 条应当**已经在历史里**，
        # 第 2 条正在播（或刚播完）。如果历史里没有第 1 条，就是"逐条覆盖"。
        & $adb shell "input tap 400 250" | Out-Null
        $dumped++
        Write-Output "  快照 @ $(Get-Date -Format 'HH:mm:ss.fff')  （第 2 条出现于 $($second.clock)）"
    }
    Start-Sleep -Milliseconds 120
}
if ($dumped -eq 0) {
    Write-Output "  模型这一轮只产出了 1 条（没有"第 2 条"这个里程碑），换一句话再试"
}

Start-Sleep -Seconds 6
$logs = Get-Logs
Write-Output "`n===== 快照 ====="
$logs.entries | Where-Object { $_.text -like "*屏幕快照*" } | Select-Object -Last 3 | ForEach-Object {
    "--- $($_.clock)"
    ($_.text -split "`n" | Where-Object { $_ -like "历史*" -or $_ -like "正在播*" -or $_ -like "待处理*" }) |
        ForEach-Object {
            if ($_ -like "历史*") {
                $body = $_ -replace "^历史\(items\): ", ""
                $n = ($body -split "：")[0]
                $tail = ($body -split " / " | Select-Object -Last 3) -join " | "
                "  历史 $n  尾3条=[$tail]"
            } else { "  $_" }
        }
}
