# 冷库恢复示例：将 archive_exports 中的导出重新导入到冷库 schema
# 用法:
#   .\scripts\archive\restore_cold.ps1 -SqlFile archive_exports\archive_xxx.sql -ColdDatabase lunarcore_cold

param(
    [Parameter(Mandatory = $true)][string]$SqlFile,
    [string]$MysqlHost = "127.0.0.1",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "123456",
    [string]$ColdDatabase = "lunarcore_cold"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $SqlFile)) { throw "SqlFile not found: $SqlFile" }

$mysql = Get-Command mysql -ErrorAction SilentlyContinue
if ($null -eq $mysql) { throw "mysql client required" }

& mysql -h $MysqlHost -u $MysqlUser "-p$MysqlPassword" -e "CREATE DATABASE IF NOT EXISTS `$ColdDatabase DEFAULT CHARSET utf8mb4;"
Get-Content $SqlFile -Raw | & mysql -h $MysqlHost -u $MysqlUser "-p$MysqlPassword" $ColdDatabase
Write-Host "Restored into $ColdDatabase"
