# 运维日常巡检与故障 SOP

## 日常巡检（开服后每 4h / 高峰前）

| 项 | 命令 / 指标 | 正常阈值（初值，需 k6 校准） |
|----|-------------|------------------------------|
| DB 连接 | `SHOW STATUS LIKE 'Threads_connected'` / Hikari `active` | &lt; `maximum-pool-size`×0.8 |
| Redis 内存 | `INFO memory` → `used_memory_human` | &lt; maxmemory×0.75 |
| JVM GC | `/actuator/prometheus` → `jvm_gc_pause_seconds` | P99 &lt; 200ms |
| 登录失败率 | Grafana「登录成功/失败」 | 5m 失败率 &lt; 20% |
| 匹配队列 | `lunarcore_match_queue_depth` | Warning&gt;200 / Critical&gt;500 |
| Zone 负载 | `lunarcore_zone_load_ratio` | Warning&gt;0.85 / Critical&gt;0.95 |
| AI sidecar | `/v1/ai/health` 与网关 fallback 次数 | fallback 突增→查 sidecar |

## 故障处理 SOP（摘要）

1. **Redis 宕机**：钱包锁回退本地（单节点仍安全）；多节点立即切只读/停充值；按 `docs/multi-node-failover.md` MN-3 恢复。
2. **DB 超时**：降级非核心写（聊天/埋点）；检查 Hikari 耗尽与慢 SQL。
3. **IAP 异常**：确认 `mylunarcore.iap.mock-verify=false`；渠道沙箱见 `docs/iap-sandbox.md`。
4. **热更失败**：协调器已回滚内存指针；用 `/admin/config-rollback.html` 或 `POST /api/admin/ops/import/rollback` 恢复文件。
5. **协议不兼容（retcode=9）**：客户端强制更新；勿下调 `PROTOCOL_WIRE_VERSION`。

## 密钥轮换

| 密钥 | 环境变量 | 备注 |
|------|----------|------|
| DB | `DB_PASSWORD` | 与 Spring datasource 同步 |
| Internal Token | `INTERNAL_API_TOKEN` | 网关/AI sidecar 共用 |
| JWT（微服务） | `AUTH_JWT_SECRET` / `GATEWAY_JWT_SECRET` | 脚手架，正式接 IdP |
| 管理后台 Session | Servlet Session + Cookie Secure | 生产已开 httpOnly/secure |
| KCP/HMAC 会话钥 | 登录动态下发 `session_crypto_key` | 非硬编码；主密钥勿入库 |
| 管理后台 IP | `ADMIN_IP_WHITELIST` | prod 必填 |

建议接入 Vault / 云 KMS；轮换后滚动重启节点。
