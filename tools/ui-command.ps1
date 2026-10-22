# 与手机界面共用运行实例的调试入口。脚本不创建/删除会话，不修改模型配置。
param(
    [Parameter(Mandatory = $true)][string]$ConversationId,
    [ValidateSet('state', 'send', 'push', 'system', 'mode')][string]$Action = 'state',
    [string]$Text = '',
    [ValidateSet('', 'chat', 'scene')][string]$Mode = '',
    [string]$RequestId = [guid]::NewGuid().ToString(),
    [string]$BaseUrl = 'http://127.0.0.1:8787',
    [string]$Password = ''
)

$ErrorActionPreference = 'Stop'
$headers = @{}
# 本机回环无需密码；连接 LAN 时使用已有凭据解析方式，不将密码写到 URL 或输出。
if ($Password -or ([uri]$BaseUrl).Host -notin @('127.0.0.1', 'localhost', '::1')) {
    . (Join-Path $PSScriptRoot 'logweb-auth.ps1')
    $headers['X-Log-Token'] = Resolve-LogWebPassword -Password $Password
}
$base = $BaseUrl.TrimEnd('/')
if ($Action -eq 'state') {
    $result = Invoke-RestMethod -Uri "$base/api/ui/state?id=$([uri]::EscapeDataString($ConversationId))" -Headers $headers
} else {
    $body = @{ conversationId = $ConversationId; requestId = $RequestId; action = $Action; text = $Text; mode = $Mode } | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Uri "$base/api/ui/command" -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
}
$result | ConvertTo-Json -Depth 8
