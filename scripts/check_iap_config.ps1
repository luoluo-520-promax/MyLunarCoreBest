# IAP 渠道配置检查：校验密钥格式/必填项，失败 exit 1 供 CI/启动钩子报警
param(
    [string]$Profile = "prod"
)

$ErrorActionPreference = "Stop"
$failed = @()

function Require-Env([string]$name, [int]$minLen = 8) {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrWhiteSpace($v) -or $v.Length -lt $minLen) {
        $script:failed += "missing/weak env: $name"
    }
}

if ($Profile -eq "prod") {
    Require-Env "APPLE_IAP_SHARED_SECRET" 16
    Require-Env "GOOGLE_PLAY_PACKAGE_NAME" 3
    # 服务账号 JSON 路径或内容
    $sa = $env:GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
    $saPath = $env:GOOGLE_PLAY_SERVICE_ACCOUNT_PATH
    if ([string]::IsNullOrWhiteSpace($sa) -and [string]::IsNullOrWhiteSpace($saPath)) {
        $failed += "missing GOOGLE_PLAY_SERVICE_ACCOUNT_JSON or PATH"
    } elseif (-not [string]::IsNullOrWhiteSpace($saPath) -and -not (Test-Path $saPath)) {
        $failed += "service account file not found: $saPath"
    } elseif (-not [string]::IsNullOrWhiteSpace($sa) -and $sa -notmatch '"type"\s*:\s*"service_account"') {
        $failed += "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON does not look like a service account"
    }
    Require-Env "INTERNAL_API_TOKEN" 24
}

if ($failed.Count -gt 0) {
    Write-Host "IAP config check FAILED:" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host " - $_" }
    exit 1
}

Write-Host "IAP config check OK (profile=$Profile)" -ForegroundColor Green
exit 0
