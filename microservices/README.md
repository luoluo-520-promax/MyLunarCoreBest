# MyLunarCore Spring Cloud Alibaba Scaffold

> **实验性脚手架 — 生产账号/玩家域仍以单体为主。**  
> `player-service` 已提供可独立部署的 Profile/Session API；`chat-service` / `match-service` **已挂入 api-gateway**（`/v1/chat/**`、`/v1/match/**`）。  
> 详见 [`docs/commercial-gap-solutions.md`](../docs/commercial-gap-solutions.md)。

This directory is the microservice migration scaffold and does **not** replace the current monolith.

### 轻量 AI 旁路

仅需 AI sidecar 时：

```bash
cd microservices && mvn -pl services/ai-assist-service -am package
java -jar services/ai-assist-service/target/*.jar
# 或见 docker-compose.ai-assist.yml（实验）
```

### API 预拆分

| 模块 | 端口 | 说明 |
|------|------|------|
| `common/match-api` | — | 匹配契约 + 事件载荷 |
| `common/chat-api` | — | 聊天契约 + 事件载荷 |
| `match-service` | **18085** | 全局评分队列 + zoneId 回跳 |
| `chat-service` | 18084 | 世界削峰 backlog + 离线私聊 7 天 |
| `player-service` | — | Profile/Session 中心化查询（非演示桩） |
| `ai-assist-service` | 18083 | 旁路 AI |

## Product positioning (from 2026-08 assessment)

Short-term target: **崩铁式**（Plane/Floor 分区实例 + 遇敌进回合战），不是原神/鸣潮式无缝大世界。

### 冷静拆分建议（补充）

箱庭回合制战斗是 CPU 密集计算，但不涉及高频位置同步，**单体完全扛得住**。因此：

| 候选 | 建议 | 原因 |
|------|------|------|
| **匹配大厅** | **可拆（优先）** | 脚手架：`services/match-service`（端口 **18085**） |
| **世界聊天** | **可拆（优先）** | 脚手架：`services/chat-service`（端口 18084）；Redis `lunar:chat:*` |
| Admin / 热更 ops | 可拆 | 与 Netty tick 隔离 |
| 排行榜 | 可拆 | 最终一致 |
| AI sidecar | 已部分落地 | 只读建议，故障隔离 |
| 账号 | 稍后 | 脚手架有演示桩，未达生产 |
| 钱包 / 抽卡写路径 | **勿拆** | 强一致写协议 |
| **战斗 / 场景** | **勿拆** | 先证明 Center 多节点；避免分布式事务 |

主工程已落地 Phase 0–2 最小集，并补充：对话树/过场、家园骨架、公会 Raid 接口、战斗快照、KCP 自适应重传、钱包分布式锁、配置灰度、战斗确定性校验、协议 HMAC、TraceId 透传。

| 能力 | 配置 / 类 |
|------|-----------|
| Redis 票据/在线表/排行榜/世界聊天（可选） | `lunarcore.redis.enabled` + `MigrationTicketService` / `OnlinePresenceService` / `LeaderboardService` / `ChatService` Pub/Sub |
| Zone 租约心跳 + 人数上限 | `SceneRegistry` + `lunarcore.zone.max-players` |
| Global / Zone / Battle 时钟 | `GameServer` + `zone-period-ms` / `battle-period-ms` |
| 移动反作弊 + 抽卡扣费流水 | `MoveSpeedGuard`；`GachaApplicationService` + `gacha_draw_history` |
| 组队协议 + 多人共战 | `PartyService`；`BattleContext.participantPlayerIds` |
| 公会基础 + Raid/贡献榜预留 | CmdId 970+；`GuildService` / `GuildRaidScheduler` / `GuildContributionRank` |
| 对话树 / 过场 / 家园 | `DialogueTriggerEngine` / `CutsceneTriggerService` / `HomeBaseService` |
| 战斗快照 / 确定性伤害 | `BattleSnapshotService` / `BattleDeterministicValidator` |
| KCP 拥塞 + 协议 HMAC | `KcpRetransmitAlgo` / `ProtocolHmacCodec` |
| 钱包分布式锁 / 配置灰度 | `DistributedLockService` / `ConfigGrayRelease` |
| TraceId 跨服务 | `NetTraceContext` + `X-Trace-Id` → ai-assist |
| 无缝 Cell / 实时战斗（实验，默认关） | `lunarcore.world.*` |

双节点切服请开启共享 Redis，验证清单见 `docs/multi-node-failover.md`：

```properties
lunarcore.redis.enabled=true
lunarcore.redis.host=127.0.0.1
lunarcore.redis.port=6379
lunarcore.center.mode=remote
lunarcore.center.remote-base-url=http://center-host:8080
lunarcore.center.advertise-host=<本机对外IP>
lunarcore.center.advertise-port=9000
```

## Included modules

- `common/common-api`
- `common/match-api`
- `common/chat-api`
- `services/api-gateway`（路由：auth / player / ai-assist；**不含** chat/match）
- `services/auth-service`（**演示**）
- `services/player-service`（**演示**）
- `services/ai-assist-service` (sidecar AI: rule coach + optional LLM)
- `services/chat-service`（端口 18084，独立 HTTP 骨架）
- `services/match-service`（端口 18085，独立 HTTP 骨架）

## Run infrastructure

```bash
# 开发仅需 MySQL/Redis 时优先用根目录:
docker compose -f docker-compose.dev.yml up -d

# 本目录含 Nacos/Prometheus/Grafana:
docker compose -f microservices/docker-compose.yml up -d
```

## Build services

```bash
cd microservices
mvn -U clean package
```

## Start order（仅本地实验）

**最小网关联调：**

1. `auth-service`
2. `player-service`
3. `ai-assist-service`
4. `api-gateway`

**完整脚手架（可选，不经网关）：** `chat-service`（18084）、`match-service`（18085）

After startup (gateway HTTPS mock):

- `GET https://localhost:18443/auth/health`
- `GET https://localhost:18443/players/10001`
- `GET https://localhost:18443/ai/health`

## AI assist sidecar

`ai-assist-service` (default port `18083`) provides:

- `GET /ai/health` — health check (gateway-exposed)
- `POST /internal/ai/ask` — internal ask（`X-Internal-Token` + 可选 `X-Trace-Id`）

Game server config (remote disabled by default):

```properties
lunarcore.ai-assist.remote-enabled=true
lunarcore.ai-assist.remote-base-url=http://127.0.0.1:18083
lunarcore.ai-assist.remote-internal-token=dev-internal-token
```

On failure the game server falls back to in-process rule coach / local LLM.

## Observability (production hardening)

```bash
docker compose -f microservices/docker-compose.yml up -d prometheus grafana
```

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 （默认 admin/admin），看板 `MyLunarCore Business`
- 业务指标见主工程 `BusinessMetrics`；告警规则 `deploy/prometheus/rules/business-alerts.yml`
- 完整说明：`docs/production-hardening.md`；压测结论：`docs/load-test-baseline.md`

## API versioning

Gateway supports both legacy and versioned paths:

- `/v1/auth/**` → auth-service
- `/v1/players/**` → player-service
- `/v1/ai/**` → ai-assist-service（含熔断）
- 旧路径 `/auth/**` `/players/**` `/ai/**` 仍可用

## Split evaluation

| Candidate | Real-time consistency with game loop | Split now? | Rationale |
|-----------|--------------------------------------|------------|-----------|
| Matchmaking lobby | Low–medium (eventual OK) | **Yes (peak)** | 开服洪峰；允许最终一致 |
| World chat | Low (eventual OK) | **Yes (peak)** | 洪峰型社交 |
| Admin / config import / hot-reload ops | Low (HTTP ops plane) | **Yes** | Ops plane isolated from Netty tick |
| Leaderboard | Low–medium (eventual OK) | **Yes** | Hall Netty stays as thin client |
| AI assist (coach / LLM) | Low (async advice only) | **Yes (sidecar)** | Read-only context; fault-isolated |
| Auth / account | Medium | Later | Scaffold already has auth-service |
| Player aggregate / wallet / gacha | High | **No** | Shared write protocol required |
| Battle / scene | High | **No** | 箱庭回合制单体可扛；先 Center |

**Recommendation:** keep the Netty game core as a modular monolith; extract **matchmaking + world chat** first for launch peaks, then Admin/Config, Leaderboard and AI assist. Do not split battle/scene until Center routing is proven under load.

## Spring Boot version note

- 主工程与本脚手架均对齐：**Spring Boot 4.0.4** + **Spring Cloud 2025.1.0** + **Spring Cloud Alibaba 2025.1.0.0**
- Nacos 已移除 bootstrap，请使用 `spring.config.import=optional:nacos:...`（见各服务 `application.yml`）

## Center routing (monolith)

- `lunarcore.center.mode=local` → `LocalCenterServer` (default)
- `lunarcore.center.mode=remote` → `RemoteCenterServer` + `HttpCenterRoutingClient`
- Peer plan API (local mode): `GET /internal/center/plan?planeId=&floorId=`

## Publish drill

Unit drill: `PublishDrillTest`.

```powershell
.\scripts\publish_drill.ps1
.\scripts\publish_drill.ps1 -Extended
```
