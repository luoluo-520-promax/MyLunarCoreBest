# 生产加固落地说明（2026-08，含整改增量）

本文对应上线前必做项与中长期能力的**代码落地基线**。压测参考结论见 `docs/load-test-baseline.md`；完整 SLA 须在目标硬件重跑 `scripts/load/k6_baseline.js` 后回填。

## 0. 架构红线

- **生产仅运行单体主工程**；`microservices/` 为实验脚手架，auth/player 演示桩不可当 IdP。
- 拆分顺序：Admin/热更 → **匹配大厅** → **世界聊天** → 排行榜 → AI sidecar → 账号；**勿先拆**战斗/场景/钱包写路径（箱庭回合制 CPU 密集但无高频位置同步，单体可扛）。
- 产品路线为崩铁式分区遇敌；`lunarcore.world.cell-handoff-enabled` / `realtime-combat-enabled` **默认关**。

## 1. 稳定性

| 项 | 落地 |
|----|------|
| 业务指标 | `BusinessMetrics`：登录/抽卡/战斗/匹配 + Zone 负载 + P50/P95/P99 |
| Grafana | `deploy/grafana` + Compose `grafana`（:3000） |
| 告警规则 | `deploy/prometheus/rules/business-alerts.yml`（阈值初值，须 k6 校准） |
| 优雅停机 | `GracefulShutdownCoordinator` |
| 日志脱敏/结构 | `SensitiveDataMasker` + logback `%maskMsg` + MDC `traceId` |
| TraceId 透传 | `NetTraceContext` → `X-Trace-Id` → `ai-assist-service` / Admin |
| 压测 | `scripts/load/k6_baseline.js` + `docs/load-test-baseline.md` |
| VirtualThreads | `lunarcore.netty.virtual-threads-enabled`（业务侧可选） |
| 多节点 | `docs/multi-node-failover.md` |

**建议阈值（初值，需压测校准）**

- 登录失败率 > 20%（5m）告警
- 抽卡异常率 > 5%（5m）告警
- Zone 默认 `lunarcore.zone.max-players=300`（可按 ZoneTick 耗时自适应下调）
- 匹配队列超时默认 60s
- 参考机心跳 CCU / 匹配 QPS 见 `docs/load-test-baseline.md`

## 2. 数据一致性

| 项 | 落地 |
|----|------|
| 本地事务日志 | 表 `local_tx_log` + `LocalTxLogService` |
| 防重放 | 表 `biz_replay_guard` |
| 钱包分布式锁 | `DistributedLockService`；推荐 `lunarcore.redis.redisson-enabled=true`（RLock）；否则 SET NX；本地回退 |
| Outbox 预演 | `LocalTxLogService` + `OutboxRelayJob`（`lunarcore.outbox.relay-enabled`） |
| IAP 真渠道 | Apple verifyReceipt / Google Publisher API；启动健康检查；Redis TTL 防重放 |
| 战斗快照 | `BattleSnapshotService`（断线重连/热恢复） |
| 配置灰度 | `ConfigGrayRelease`（UID 尾号 / 服务器白名单） |
| 归档 | `DataArchiveJob` 默认关 |
| 配置发布指纹 | `config_release` + `ConfigReleaseService`；见 `docs/config-versioning.md` |

## 3. 社交与内容

| 项 | 落地 |
|----|------|
| 公会基础 | 创建/加入/退出/贡献/商店（Cmd 970–983） |
| 公会战预留 | `GuildRaidScheduler` + `GuildContributionRank` |
| 对话树 / 过场 | `dialogue` + `cutscene`；NPC 交互联动 `DialogueTriggerEngine` |
| 家园/基地 | `HomeBaseService`（基建 / 入驻 / 体力） |
| 成就 / 新手 / 活动目录 | 既有 |

## 4. 安全

| 项 | 落地 |
|----|------|
| KCP 加密 | prod 强制 `lunarcore.kcp-crypto.enabled=true`；登录下发 `session_crypto_key` |
| KCP Nonce | `KcpGcmNonceGuard` 防 IV 重放 |
| KCP 拥塞 | `KcpRetransmitAlgo`（fixed/adaptive）+ `KcpRttMonitor` |
| 协议 HMAC | prod 强制 `lunarcore.protocol-hmac.enabled=true` |
| 管理后台 IP | `lunarcore.admin.ip-whitelist` / `ADMIN_IP_WHITELIST`（prod 必填） |
| IAP mock | prod 禁止 `mylunarcore.iap.mock-verify=true`（`IapProductionGuard` + SecretsValidator） |
| 战斗确定性校验 | `BattleDeterministicValidator`（不信任客户端伤害） |
| 战斗审计 | `BattleAuditService` |
| HikariCP | `spring.datasource.hikari.*`（见 application.properties） |
| 匹配/聊天拆分 | `microservices/services/match-service`、`chat-service` 脚手架 |

## 5. 微服务战略

- **生产 = 单体**；脚手架仅实验。
- **优先可拆（允许最终一致、开服洪峰大）**：匹配大厅、世界聊天；其次 Admin/热更、排行榜、AI sidecar。
- **暂不拆**：战斗、场景、钱包/抽卡写路径（避免分布式事务）。
- 详见 `microservices/README.md`。

## 启用示例

```properties
lunarcore.redis.enabled=true
lunarcore.kcp-crypto.enabled=true
lunarcore.kcp.retransmit.algo=adaptive
lunarcore.battle-snapshot.enabled=true
lunarcore.protocol-hmac.enabled=true
lunarcore.config-gray.enabled=true
lunarcore.config-gray.uid-tails=0,1
lunarcore.netty.virtual-threads-enabled=true
```
