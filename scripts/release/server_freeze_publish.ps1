# 大版本发布：Server Freeze → 确认落盘 → 杀进程重启
# 用法: pwsh scripts/release/server_freeze_publish.ps1 -EtaMinutes 5 -Reason "2.0 deploy"

param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$AdminUser = "admin",
    [string]$AdminPass = "admin",
    [int]$EtaMinutes = 5,
    [string]$Reason = "version_update",
    [switch]$SkipEscalate,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Write-Host "== login admin =="
Invoke-RestMethod -Uri "$BaseUrl/api/admin/login" -Method POST -WebSession $session `
    -ContentType "application/json" -Body (@{ username = $AdminUser; password = $AdminPass } | ConvertTo-Json) | Out-Null

Write-Host "== wallet snapshot (rollback baseline) =="
$snap = Invoke-RestMethod -Uri "$BaseUrl/api/admin/ops/wallet/snapshot" -Method POST -WebSession $session `
    -ContentType "application/json" -Body (@{ label = ("pre-" + (Get-Date -Format "yyyyMMdd-HHmmss")) } | ConvertTo-Json)
$snap | ConvertTo-Json -Depth 4

Write-Host "== server freeze =="
$freezeBody = @{ reason = $Reason; etaMinutes = $EtaMinutes } | ConvertTo-Json
$freeze = Invoke-RestMethod -Uri "$BaseUrl/api/admin/ops/freeze" -Method POST -WebSession $session `
    -ContentType "application/json" -Body $freezeBody
$freeze | ConvertTo-Json -Depth 4

if (-not $SkipEscalate) {
    Write-Host "== escalate to maintenance (reject login) =="
    Invoke-RestMethod -Uri "$BaseUrl/api/admin/ops/freeze/escalate" -Method POST -WebSession $session | ConvertTo-Json -Depth 4
}

if ($DryRun) {
    Write-Host "DryRun: skip process kill. Confirm battlesSynced/dirtyCleared then stop JVM manually."
    exit 0
}

Write-Host "Freeze complete. GracefulShutdownCoordinator will sync-save on JVM exit. Stop the process now."
