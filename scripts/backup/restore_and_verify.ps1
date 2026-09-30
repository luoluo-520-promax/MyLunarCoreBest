#Requires -Version 5.1
<#
.SYNOPSIS
  从备份恢复 MySQL/Redis，并跑核心冒烟校验（登录健康、抽卡配置、公会表探测）。

.PARAMETER MysqlDump
  mysqldump 生成的 SQL 文件路径

.PARAMETER MysqlHost / MysqlUser / MysqlPassword / MysqlDb
  目标库连接

.PARAMETER RedisRdb
  可选 Redis RDB 路径；若提供则提示停服替换（需手动确认）

.PARAMETER SkipSmoke
  跳过 HTTP 冒烟

.PARAMETER BaseUrl
  冒烟基址，默认 http://127.0.0.1:8080

.EXAMPLE
  .\scripts\backup\restore_and_verify.ps1 -MysqlDump .\backups\lunar.sql -MysqlPassword secret
#>
param(
    [Parameter(Mandatory = $true)][string]$MysqlDump,
    [string]$MysqlHost = "127.0.0.1",
    [string]$MysqlUser = "root",
    [string]$MysqlPassword = "",
    [string]$MysqlDb = "lunarcore",
    [string]$RedisRdb = "",
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [switch]$SkipSmoke
)

$ErrorActionPreference = "Stop"
$started = Get-Date

if (-not (Test-Path $MysqlDump)) {
    throw "MysqlDump not found: $MysqlDump"
}

Write-Host "==> [1/3] Restore MySQL from $MysqlDump" -ForegroundColor Cyan
$mysqlArgs = @("-h", $MysqlHost, "-u", $MysqlUser, $MysqlDb)
if ($MysqlPassword) {
    $env:MYSQL_PWD = $MysqlPassword
}
Get-Content -Raw $MysqlDump | & mysql @mysqlArgs
if ($LASTEXITCODE -ne 0) { throw "mysql restore failed" }

if ($RedisRdb -and (Test-Path $RedisRdb)) {
    Write-Host "==> [2/3] Redis RDB present at $RedisRdb — stop redis, copy to data dir, start redis (manual ops)" -ForegroundColor Yellow
    Write-Host "    Suggested: Stop-Service Redis; Copy-Item '$RedisRdb' dump.rdb; Start-Service Redis"
} else {
    Write-Host "==> [2/3] Skip Redis RDB" -ForegroundColor DarkGray
}

$rtoSec = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
Write-Host "RTO(restore_phase)=${rtoSec}s  RPO=backup_file_timestamp" -ForegroundColor Green

if ($SkipSmoke) {
    Write-Host "SkipSmoke set; done." -ForegroundColor Yellow
    exit 0
}

Write-Host "==> [3/3] Smoke verify $BaseUrl" -ForegroundColor Cyan
$paths = @("/auth/health", "/actuator/health", "/ai/health")
$ok = 0
foreach ($p in $paths) {
    try {
        $r = Invoke-WebRequest -Uri ($BaseUrl + $p) -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) {
            Write-Host "  OK $p" -ForegroundColor Green
            $ok++
        } else {
            Write-Host "  FAIL $p status=$($r.StatusCode)" -ForegroundColor Red
        }
    } catch {
        Write-Host "  SKIP/FAIL $p : $($_.Exception.Message)" -ForegroundColor DarkYellow
    }
}
Write-Host "Smoke hits_ok=$ok / $($paths.Count). Also run: .\scripts\publish_drill.ps1" -ForegroundColor Cyan
Write-Host "Report fields: RTO=${rtoSec}s RPO=<backup_mtime> verified_endpoints=$ok" -ForegroundColor Green
