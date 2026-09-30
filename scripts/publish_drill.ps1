# MyLunarCore 发布演练 / 压测冒烟
# 用法: 在仓库根目录执行  .\scripts\publish_drill.ps1
# 依赖: Maven；可选 -SkipTests 仅编译
#
# 演练覆盖清单:
#   [1] 编译 (clean compile)
#   [2] 测试编译 (test-compile)
#   [3] 单元/集成冒烟 (默认 Surefire 子集):
#       - PublishDrillTest         热更加载 / 中心路由 / 匹配队列重建 / 配置原子写
#       - HotReloadCoordinatorTest 配置热更
#       - CenterServerTest         中心路由切换
#       - MatchmakingServiceTest   匹配队列
#       - ConfigFileServiceAtomicWriteTest 配置原子写与备份
#   建议另跑（未默认纳入以控时）:
#       - ProtocolCompat / CmdIdUniqueness
#       - IAP 验签模拟 (mock 路径)
#       - Testcontainers 集成（若本地有 Docker）
#       - scripts/load/k6_baseline.js 压测校准告警阈值

param(
    [switch]$SkipTests,
    [switch]$Extended,
    [string]$SurefireTests = "PublishDrillTest,HotReloadCoordinatorTest,CenterServerTest,MatchmakingServiceTest,ConfigFileServiceAtomicWriteTest"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

Write-Host "Publish drill checklist:" -ForegroundColor DarkCyan
Write-Host "  - compile + test-compile"
Write-Host "  - hot-reload / center routing / match drain / config atomic write"
Write-Host "  - (optional -Extended) CmdIdUniqueness + ProtocolCompat + ProductionHardeningFlow"

Write-Host "==> [1/3] clean compile" -ForegroundColor Cyan
mvn -q clean compile "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "compile failed" }

Write-Host "==> [2/3] test-compile" -ForegroundColor Cyan
mvn -q test-compile "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "test-compile failed" }

if ($SkipTests) {
    Write-Host "SkipTests set; drill compile-only OK" -ForegroundColor Yellow
    exit 0
}

$tests = $SurefireTests
if ($Extended) {
    $tests = "$SurefireTests,CmdIdUniquenessTest,ProtocolCompatServiceTest,ProductionHardeningFlowTest"
}

Write-Host "==> [3/3] surefire drill: $tests" -ForegroundColor Cyan
mvn -q surefire:test "-Dtest=$tests"
if ($LASTEXITCODE -ne 0) { throw "publish drill tests failed" }

Write-Host "Publish drill PASSED" -ForegroundColor Green
Write-Host "Next: k6 baseline on target hardware -> tune alerts / Bucket4j / pools" -ForegroundColor DarkGray
