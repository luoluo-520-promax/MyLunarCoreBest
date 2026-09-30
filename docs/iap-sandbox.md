# IAP 沙盒、证书轮换与对账

生产 profile 强制 `mylunarcore.iap.mock-verify=false`。本地与联调可开 Mock；上架前必须走渠道沙盒。启动时若配置了真实渠道且 `channel-health-check.fail-fast=true`，会 ping 沙盒/OAuth，失败则 **fail-fast**。

## 通用流程

1. 客户端 `CREATE_IAP_ORDER` → 服务端落单（pending）。
2. 渠道完成支付 → 客户端带 receipt 调 `CONFIRM_IAP_ORDER`。
3. 服务端渠道验签 → Redis TTL 防重放 + DB `biz_replay_guard` → 分布式锁发货 → 订单完成。

相关配置：

```properties
mylunarcore.iap.mock-verify=false
mylunarcore.iap.replay-ttl-seconds=604800
mylunarcore.iap.channel-health-check.enabled=true
mylunarcore.iap.channel-health-check.fail-fast=true
lunarcore.redis.enabled=true
lunarcore.redis.redisson-enabled=true
```

## Apple App Store（Sandbox）

```properties
mylunarcore.iap.apple.enabled=true
mylunarcore.iap.apple.shared-secret=${APPLE_IAP_SHARED_SECRET}
mylunarcore.iap.apple.bundle-id=com.example.mylunarcore
mylunarcore.iap.apple.use-sandbox=true
```

### 证书 / Shared Secret 轮换

1. App Store Connect → App → App Information → App-Specific Shared Secret 生成新密钥。
2. 将新密钥写入 K8s Secret / Vault：`IAP_APPLE_SHARED_SECRET`（滚动更新，双密钥并行窗口建议 ≥24h）。
3. 旧密钥在确认无在途订单后作废。
4. 轮换后观察：`apple_status_*` 错误率、健康检查 ping、发货成功率。

### Apple 错误码映射

| status / message | 含义 | 服务端 message | 客户端建议 |
|------------------|------|----------------|------------|
| 0 | 成功 | ok | 展示发货结果 |
| 21002 | 收据损坏 | apple_status_21002 | 重新拉起支付 |
| 21003 | 鉴权失败 | apple_status_21003 | 检查 Shared Secret |
| 21007 | 沙盒收据打生产 | 自动回退沙盒 | — |
| 21008 | 生产收据打沙盒 | 自动回退生产 | — |
| bundle mismatch | BundleId 不符 | apple_bundle_mismatch | 检查包名 |
| sku mismatch | 商品不匹配 | apple_sku_mismatch | 对齐 SKU |
| replay_rejected | 重放 | replay_rejected | 提示已到账 |

## Google Play（License Testers）

```properties
mylunarcore.iap.google.enabled=true
mylunarcore.iap.google.package-name=com.example.mylunarcore
mylunarcore.iap.google.service-account-json=${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON}
```

收据可为纯 `purchaseToken`，或 JSON：`{"purchaseToken":"...","productId":"sku"}`。

### 服务账号轮换

1. GCP IAM 创建新服务账号，授予 Play Console「查看财务数据 / 管理订单」。
2. 下载 JSON → 注入 `IAP_GOOGLE_SERVICE_ACCOUNT_JSON`（整段 JSON 字符串）。
3. 滚动发布；旧账号在无流量后删除 key。
4. 健康检查：OAuth token 换取成功即视为渠道可达。

### Google 错误码映射

| message | 含义 | 客户端建议 |
|---------|------|------------|
| google_purchase_not_found | Token 无效 | 重新支付 |
| google_auth_forbidden | 权限不足 | 联系运维轮换 SA |
| google_purchase_state_* | 未购买/已取消 | 提示支付状态 |
| google_http_* | API HTTP 错误 | 退避重试 |
| replay_rejected | 重放 | 提示已到账 |

## 本地 Mock（仅非 prod）

```properties
mylunarcore.iap.mock-verify=true
mylunarcore.iap.channel-health-check.fail-fast=false
```

## 对账脚本

见 `scripts/iap/reconcile_iap.ps1`：

```powershell
# 对比渠道侧订单与本地 biz_replay_guard / wallet_ledger
.\scripts\iap\reconcile_iap.ps1 -SinceHours 24 -JdbcUrl "jdbc:mysql://..."
```

输出：缺失发货、重复 channelTx、金额不一致清单。建议每日 cron + 告警。

## 验收清单

- [ ] 沙盒首购成功发货
- [ ] 同一票据二次 confirm 被防重放拒绝且不重复发货
- [ ] Redis 锁下并发 confirm 仅一发货
- [ ] 渠道证书轮换后健康检查通过
- [ ] 指标 `lunarcore.iap.replay_rejected` 可观测
