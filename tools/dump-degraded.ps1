# 把设备上"曾经解析失败"的模型输出拉到本机，做真机语料回归。
#
# 为什么需要它：解析失败在界面上长得像一条正常消息（一条气泡里全是原始 JSON），
# 日志里也只是一条 assistant 消息 —— 只有消息表能一眼筛出来（见 02-handoff §4 第 9 条）。
# 拉下来之后由 core 的 RealOutputCorpusTest 逐条喂给解析器：
# **"曾经降级"的每一份都应该能被新的解析层救回来**，救不回来就是新发现的形态。
#
# 用法：
#   pwsh tools/dump-degraded.ps1
#   pwsh tools/build.ps1 :core:test
param(
    [string]$Password = "tez071216",
    [string]$Base = "http://127.0.0.1:8787",
    [string]$OutDir = "",
    [int]$Limit = 400
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutDir) { $OutDir = Join-Path $root '.tmp\degraded' }

function Get-Health {
    try { Invoke-RestMethod -Uri "$Base/api/health" -TimeoutSec 5 | Out-Null; return $true }
    catch { return $false }
}

# 手机 IP 变了 / 只连了 USB 时，用端口转发（别去猜 IP）
if (-not (Get-Health)) {
    Write-Host "[dump] $Base 不通，试端口转发 adb forward tcp:8787 tcp:8787"
    adb forward tcp:8787 tcp:8787 | Out-Null
    Start-Sleep -Milliseconds 500
    if (-not (Get-Health)) { throw "日志服务不通：$Base。先在手机上打开「设置 → 排查 → 日志网页」。" }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Get-ChildItem $OutDir -Filter '*.txt' -File -ErrorAction SilentlyContinue | Remove-Item -Force

$convs = (Invoke-RestMethod -Uri "$Base/api/conversations?t=$Password" -TimeoutSec 30).conversations
$total = 0
foreach ($conv in $convs) {
    $msgs = Invoke-RestMethod -Uri "$Base/api/messages?t=$Password&id=$($conv.id)&limit=$Limit" -TimeoutSec 300
    # 判据：assistant 消息的正文以 `{` 开头 —— 那正是"整段原文被当成一条消息发出去"的样子
    $bad = @($msgs.items | Where-Object { $_.role -eq 'assistant' -and $_.text.TrimStart().StartsWith('{') })
    Write-Host ("[dump] {0,-10} {1,-6} 消息 {2,4}  降级 {3,3}" -f $conv.title, $conv.mode, $msgs.count, $bad.Count)
    foreach ($b in $bad) {
        $name = "{0}_{1}.txt" -f $conv.id.Substring(0, 8), $b.id
        [System.IO.File]::WriteAllText(
            (Join-Path $OutDir $name), $b.text, (New-Object System.Text.UTF8Encoding($false)))
        $total++
    }
}
Write-Host "[dump] 共 $total 份 → $OutDir"
Write-Host "[dump] 接着跑：pwsh tools/build.ps1 :core:test"
