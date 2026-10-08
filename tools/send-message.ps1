param(
    [Parameter(Mandatory = $true)][string]$Text,
    [switch]$KeepKeyboard
)

# 发一条消息。
#
# ⚠️ **每次都要重新查坐标**：点过输入框之后输入法会弹出来，布局整体上移，
# 写死的坐标就点到别的地方去了 —— 这是我反复踩的坑（点了半天"发送"没反应）。
# 所以流程是：查输入框 → 点它 → 输入 → **重新查发送键** → 点它。

$adb = "D:\AndroidSDK\platform-tools\adb.exe"

function Get-Nodes {
    $xml = & $adb shell "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" | Out-String
    $list = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
        $n = $m.Value
        $list += [pscustomobject]@{
            text   = [regex]::Match($n, 'text="([^"]*)"').Groups[1].Value
            desc   = [regex]::Match($n, 'content-desc="([^"]*)"').Groups[1].Value
            cls    = [regex]::Match($n, 'class="([^"]*)"').Groups[1].Value
            bounds = [regex]::Match($n, 'bounds="([^"]*)"').Groups[1].Value
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

$edit = Get-Nodes | Where-Object { $_.cls -like "*EditText*" } | Select-Object -First 1
if (-not $edit) { throw "找不到输入框（是不是不在聊天页？）" }
$c = Get-Center $edit.bounds
& $adb shell "input tap $($c[0]) $($c[1])" | Out-Null
Start-Sleep -Milliseconds 900

& $adb shell "input text '$Text'" | Out-Null
Start-Sleep -Milliseconds 700

# 输入法已经弹出来了，**重新查**发送键的位置
$send = Get-Nodes | Where-Object { $_.desc -eq "发送" } | Select-Object -First 1
if (-not $send) { throw "找不到发送键" }
$s = Get-Center $send.bounds
& $adb shell "input tap $($s[0]) $($s[1])" | Out-Null
Write-Output "已发送「$Text」@ $(Get-Date -Format 'HH:mm:ss')（发送键在 $($send.bounds)）"

if (-not $KeepKeyboard) {
    Start-Sleep -Milliseconds 400
    & $adb shell "input keyevent 111" | Out-Null   # ESC：收起键盘，避免挡住后面的操作
}
