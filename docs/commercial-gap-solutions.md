# 商业箱庭缺口解决方案落地说明

对应「性能 / 玩法闭环 / 运维可观测」三块评审缺口，本轮已在单体与微服务中落地可运行骨架。默认开关偏保守，生产按需开启。

## 一、性能与基础设施

| 缺口 | 落地 | 关键类 / 配置 |
|------|------|----------------|
| Zone 单点上限过低 | 单线默认 **1000**；动态分线 `lineId`；轻量 Zone Actor 邮箱；AOI 广播异步队列 | `ZoneManager`、`DynamicZoneLineAllocator`、`ZoneActorMailbox`、`AoiAsyncProcessor`、`lunarcore.zone.*` |
| MySQL 写瓶颈 | 读写分离 + 影子库路由；UID Hash 分库助手（16 逻辑库） | `RoutingDataSourceConfig`、`ReadWriteRouter`、`UidShardRouter`、`PtLoadRoutingFilter`（`x-pt-load`） |
| 配置热更全量毛刺 | JSON Path Delta 指针替换 + 二进制快照头 | `ConfigDeltaPatchService`；Admin `POST /api/admin/resources/config/delta` |
| KCP 弱网优先级 | 出站优先级整形：战斗 Cmd 200–299 HIGH，场景 300–399 NORMAL | `PacketPriority`、`PacketPriorityOutboundHandler`（已挂入 `GamePipelineConfigurer`） |

**压测影子库**：开启 `lunarcore.datasource.routing-enabled=true`，配置 `spring.datasource.shadow.url`，请求带 `x-pt-load: true`。

**历史流水**：继续走现有 ClickHouse / `AnalyticsEventPublisher` 埋点管道；MySQL 活跃窗由 `DataArchiveJob` 控制。

## 二、功能深度与玩法闭环

| 缺口 | 落地 | 关键类 / 接口 |
|------|------|----------------|
| Auth/Player 演示桩 | Player-Service 提供 Profile/Session 中心化存储 API（可独立重启） | `PUT/GET /players/{uid}/profile\|session` |
| Chat/Match 未挂网关 | api-gateway 增加 `/v1/chat/**`、`/v1/match/**`；Match 评分队列；Chat 削峰+离线 7 天 | `MatchController`、`ChatController`、gateway `application.yml` |
| 战斗事后审计 | 动作 Salt+Hash 即时校验，失败中断并踢线 | `BattleActionIntegrityGuard` |
| 客户端资源热更 | Manifest 调度中心 + Admin 上传/Diff | `ClientResourceManifestService`、`/api/admin/resources/manifest/**` |
| AI 只问答 | 策略代理实验开关：输出可一键采纳行动 JSON | `AiBattleStrategyProxy`、`lunarcore.ai-assist.battle-strategy-proxy-enabled` |
| 成就离线回溯 | 登录时按归档/战报补进度 | `OfflineAchievementCompensator`（挂登录成功路径） |
| IAP 退款撤销 | Google RTDN / Apple ASN Webhook + 负资产冻结禁抽卡 | `IapRefundWebhookController`、`NegativeBalanceFreezeService`（抽卡 retcode=9） |

## 三、运维与可观测

| 缺口 | 落地 | 关键类 / 接口 |
|------|------|----------------|
| 全链路压测隔离 | `x-pt-load` → SHADOW 数据源 | `PtLoadRoutingFilter` |
| 日志分级告警 | 战斗 DEBUG 白名单采样；飞书/钉钉 Webhook | `BattleLogSampler`、`AlertWebhookNotifier`；`/api/admin/ops/alert-webhook/test` |
| 优雅停机重连 | 维护模式拒绝登录；停机 abort 前依赖已有 Redis `BattleSnapshot` | `MaintenanceModeService`；`POST /api/admin/ops/maintenance/enable\|disable` |

## 推荐启用顺序

1. 开服前：`zone.aoi-async` + `dynamic-line` + `max-players=1000`（已默认）
2. 压测：影子库 + k6 带 `x-pt-load`
3. 灰度：`battle-strategy-proxy`、战斗 Salt 校验接到 `FightAction` Handler
4. 生产：配置 replica/shadow URL、告警 Webhook URL、维护窗口演练

## 仍需客户端 / 基建配合

- 战斗 Hash Challenge 需协议字段与客户端回传（服务端 Guard 已就绪）
- Kafka 世界聊天：当前为进程内 backlog，可无缝换 topic
- Player/Auth Redis 集群：当前 player-service 为中心化内存 API，接 Redis 时替换 Map 即可
- AssetBundle/PAK 打包流水线仍在客户端工程侧
