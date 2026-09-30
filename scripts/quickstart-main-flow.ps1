# 5 分钟跑通主流程（健康检查 + 配置 dry-run 占位）
# 前置：本机已启动 MyLunarCore（dev profile），默认 HTTP 8080
$ErrorActionPreference = "Stop"
$base = $env:LUNAR_HTTP_BASE
if (-not $base) { $base = "http://127.0.0.1:8080" }

Write-Host "== 1. Actuator health ==" -ForegroundColor Cyan
try {
  Invoke-RestMethod "$base/actuator/health" | ConvertTo-Json -Compress
} catch {
  Write-Host "健康检查失败：请先启动应用。$_" -ForegroundColor Red
  exit 1
}

Write-Host "== 2. 提示：游戏协议走 Netty/KCP（默认 9000），需客户端 wire_version>=2 ==" -ForegroundColor Yellow
Write-Host "登录成功后保存 session_token 与 session_crypto_key（AES-GCM/HMAC）。"
Write-Host "公会战 CmdId 984-989 / 家园 850-857 / 对话 860-868 已接线，可与客户端联调。"
Write-Host "配置回滚页: $base/admin/config-rollback.html"
Write-Host "压测基线: scripts/load/k6_baseline.js ；多节点演练: docs/multi-node-failover.md"
Write-Host "Done." -ForegroundColor Green
