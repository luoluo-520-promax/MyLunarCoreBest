# 扫描日志输出点：要求业务代码使用 %maskMsg / SensitiveDataMasker，禁止裸打 password/token
$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot ".."
$javaRoot = Join-Path $root "src\main\java"

$hits = Select-String -Path (Join-Path $javaRoot "**\*.java") -Pattern 'log\.(info|warn|error|debug)\([^)]*(password|token|secret|idCard|身份证)' -AllMatches -ErrorAction SilentlyContinue

$maskLayout = Select-String -Path (Join-Path $root "src\main\resources\logback-spring.xml") -Pattern "maskMsg|MaskingMessageConverter" -ErrorAction SilentlyContinue
if (-not $maskLayout) {
    Write-Host "FAIL: logback-spring.xml missing MaskingMessageConverter / %maskMsg" -ForegroundColor Red
    exit 1
}

if ($hits) {
    Write-Host "FAIL: possible unmasked sensitive log sites:" -ForegroundColor Red
    $hits | Select-Object -First 30 | ForEach-Object { Write-Host $_.Path ":" $_.LineNumber ":" $_.Line.Trim() }
    exit 1
}

Write-Host "OK: log masking layout present; no obvious password/token log patterns" -ForegroundColor Green
exit 0
