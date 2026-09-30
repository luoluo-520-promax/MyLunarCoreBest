param(
    [int]$SinceHours = 24,
    [string]$JdbcUrl = $env:DB_URL,
    [string]$DbUser = $env:DB_USER,
    [string]$DbPassword = $env:DB_PASSWORD
)

<#
.SYNOPSIS
  IAP 对账辅助：导出近期防重放键与钱包流水，人工/脚本比对渠道账单。
  依赖本机 mysql 客户端；渠道侧 CSV 可另附 -ChannelCsv。
#>

$ErrorActionPreference = "Stop"
if (-not $JdbcUrl) {
    Write-Host "Set DB_URL / -JdbcUrl (mysql://host:3306/db)" -ForegroundColor Yellow
    exit 2
}

# 从 jdbc:mysql://host:3306/db 解析
$hostPart = $JdbcUrl -replace '^jdbc:mysql://', '' -replace '\?.*$', ''
$dbName = ($hostPart -split '/')[1]
$server = ($hostPart -split '/')[0]

$since = (Get-Date).ToUniversalTime().AddHours(-$SinceHours).ToString("o")
Write-Host "Reconcile window since $since (UTC)"

$sqlReplay = @"
SELECT guard_key, biz_type, player_id, created_at
FROM biz_replay_guard
WHERE biz_type='iap' AND created_at >= '$since'
ORDER BY created_at DESC
LIMIT 5000;
"@

$sqlLedger = @"
SELECT player_id, currency_id, delta, reason, created_at
FROM wallet_ledger
WHERE reason LIKE 'iap%' AND created_at >= '$since'
ORDER BY created_at DESC
LIMIT 5000;
"@

$outDir = Join-Path $PSScriptRoot "..\..\logs\iap-reconcile"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$replayOut = Join-Path $outDir ("replay_{0:yyyyMMdd_HHmm}.tsv" -f (Get-Date))
$ledgerOut = Join-Path $outDir ("ledger_{0:yyyyMMdd_HHmm}.tsv" -f (Get-Date))

$mysqlArgs = @("-h", ($server -split ':')[0], "-P", ($(if ($server -match ':') { ($server -split ':')[1] } else { "3306" })),
    "-u", $DbUser, "-p$DbPassword", $dbName, "-e", $sqlReplay, "--batch", "--raw")
& mysql @mysqlArgs | Out-File -Encoding utf8 $replayOut

$mysqlArgs[-3] = $sqlLedger
& mysql @mysqlArgs | Out-File -Encoding utf8 $ledgerOut

Write-Host "Wrote $replayOut"
Write-Host "Wrote $ledgerOut"
Write-Host "Compare with App Store / Play Console export; duplicate guard_key or missing ledger rows need investigation."
