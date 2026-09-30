#Requires -Version 5.1
<#
.SYNOPSIS
  mysqldump 包装：导出热库到 backups/ 目录。
#>
param(
    [string]$MysqlHost = "127.0.0.1",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "",
    [string]$MysqlDb = "lunarcore",
    [string]$OutDir = "backups"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path (Join-Path $Root $OutDir))) {
    New-Item -ItemType Directory -Path (Join-Path $Root $OutDir) | Out-Null
}
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$out = Join-Path $Root "$OutDir\${MysqlDb}_$stamp.sql"
if ($MysqlPassword) { $env:MYSQL_PWD = $MysqlPassword }
& mysqldump -h $MysqlHost -u $MysqlUser --single-transaction --routines --triggers $MysqlDb -r $out
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed" }
Write-Host "Wrote $out" -ForegroundColor Green
Get-Item $out | Select-Object FullName, Length, LastWriteTime
