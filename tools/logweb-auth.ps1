# 日志网页 / 测试网关的密码解析 —— 只在这一处实现，别的脚本 dot-source 它。
#
# 为什么要有这个文件：密码曾经**硬编码**在脚本参数默认值里（`$Password = "<真实密码>"`），
# 也写进过文档正文。那是本机的真实凭据，不该出现在仓库里 —— 仓库可能被同步、备份、
# 贴给别人看。现在从下面三处按顺序取，**代码里一个字符都不留**：
#
#   1. `-Password` 显式传参（最高优先级）
#   2. 环境变量 `SOLOCHAT_LOG_PASSWORD`
#   3. `secrets.properties` 里的 `logweb.password=...`（.gitignore 已忽略这个文件）
#   4. `.tmp/logweb-password.txt`（.tmp 也在 .gitignore 里；一行，末尾换行无所谓）
#
# 都取不到就报错并print怎么配 —— 宁可让人多配一步，也不要静默用错密码
# （用错密码的表现是"接口返回一句鉴权失败"，很容易被当成服务没起来）。
function Resolve-LogWebPassword {
    param([string]$Password = "")

    if ($Password) { return $Password.Trim() }

    if ($env:SOLOCHAT_LOG_PASSWORD) { return $env:SOLOCHAT_LOG_PASSWORD.Trim() }

    $root = Split-Path -Parent $PSScriptRoot
    $secrets = Join-Path $root 'secrets.properties'
    if (Test-Path $secrets) {
        $line = Get-Content $secrets | Where-Object { $_ -match '^\s*logweb\.password\s*=' } | Select-Object -First 1
        if ($line) { return ($line -replace '^\s*logweb\.password\s*=', '').Trim() }
    }

    $file = Join-Path $root '.tmp/logweb-password.txt'
    if (Test-Path $file) { return (Get-Content $file -Raw).Trim() }

    throw @"
取不到日志网页密码。任选一种：
  1) 传参：        pwsh tools/<脚本>.ps1 -Password '<密码>'
  2) 环境变量：    `$env:SOLOCHAT_LOG_PASSWORD = '<密码>'
  3) secrets.properties 里写一行：  logweb.password=<密码>
  4) 或把它写进 .tmp/logweb-password.txt
（密码在 App 的「设置 → 排查 → 日志网页」里可以改；这三个位置都在 .gitignore 里）
"@
}
