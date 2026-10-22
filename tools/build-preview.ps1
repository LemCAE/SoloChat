# 本机同签名预览：使用发行包身份，不安装历史 .debug 变体。
# 签名配置放在被忽略的本机文件中，字段与 CI 的 SOLOCHAT_KEYSTORE_* 环境变量一致。
# VersionCode 显式指定为当前安装版本码，避免本机默认值 1 导致覆盖失败或抢占后续云端版本。
param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 2100000000)]
    [int]$VersionCode,
    [string]$VersionName = '0.1.0-local',
    [string]$SigningFile = '.tmp/release-signing/signing.json'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$configPath = if ([IO.Path]::IsPathRooted($SigningFile)) { $SigningFile } else { Join-Path $root $SigningFile }
if (-not (Test-Path -LiteralPath $configPath)) { throw '缺少本机发布签名配置，请先配置被忽略的签名文件。' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$names = @('SOLOCHAT_KEYSTORE_PATH', 'SOLOCHAT_KEYSTORE_PASSWORD', 'SOLOCHAT_KEY_ALIAS', 'SOLOCHAT_KEY_PASSWORD')
foreach ($name in $names) {
    if ([string]::IsNullOrWhiteSpace($config.$name)) { throw "签名配置缺少字段：$name" }
}
if (-not (Test-Path -LiteralPath $config.SOLOCHAT_KEYSTORE_PATH)) { throw '发布签名文件不存在。' }
$previous = @{}
foreach ($name in ($names + @('SOLOCHAT_VERSION_CODE', 'SOLOCHAT_VERSION_NAME'))) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $config.$name, 'Process') }
    $env:SOLOCHAT_VERSION_CODE = $VersionCode.ToString()
    $env:SOLOCHAT_VERSION_NAME = $VersionName
    & (Join-Path $PSScriptRoot 'build.ps1') ':app:assembleRelease'
    $buildExit = $LASTEXITCODE
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
exit $buildExit
