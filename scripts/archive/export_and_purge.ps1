# 归档迁移：将过期热表行导出到 SQL 文件后删除（需先开启归档策略）
# 用法（仓库根目录，PowerShell）:
#   .\scripts\archive\export_and_purge.ps1 -GachaDays 180 -BattleDays 90
# 恢复: 对导出的 .sql 执行 mysql < archive_....sql

param(
    [int]$GachaDays = 180,
    [int]$BattleDays = 90,
    [string]$MysqlHost = "127.0.0.1",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "123456",
    [string]$Database = "lunarcore",
    [string]$OutDir = "archive_exports"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $Root
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outFile = Join-Path $OutDir "archive_$stamp.sql"

Write-Host "Exporting cold rows to $outFile ..."

$exportSql = @"
SELECT * FROM gacha_draw_history WHERE created_at < DATE_SUB(NOW(), INTERVAL $GachaDays DAY);
SELECT * FROM biz_replay_guard WHERE created_at < DATE_SUB(NOW(), INTERVAL $BattleDays DAY);
SELECT * FROM local_tx_log WHERE status IN ('COMMITTED','COMPENSATED') AND created_at < DATE_SUB(NOW(), INTERVAL $GachaDays DAY);
"@

$exportSql | Out-File -Encoding utf8 (Join-Path $OutDir "archive_query_$stamp.sql")

# 实际导出依赖本机 mysql 客户端；无客户端时仅写出查询脚本供 DBA 执行
$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($null -eq $mysql) {
    Write-Host "mysql client not found; wrote query scripts under $OutDir. Run manually then purge via app DataArchiveJob." -ForegroundColor Yellow
    exit 0
}

& mysql -h $MysqlHost -u $MysqlUser "-p$MysqlPassword" $Database -e $exportSql | Out-File -Encoding utf8 $outFile

Write-Host "Purge is performed by enabling lunarcore.data-retention.archive-job-enabled=true (DataArchiveJob)."
Write-Host "Restore: import the saved dump into a cold schema, e.g. lunarcore_cold."
Write-Host "Done."
