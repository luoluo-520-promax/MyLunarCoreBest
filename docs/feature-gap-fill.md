# 功能缺口补齐说明（产品 + 技术）

对应策划/架构评审中的「需补充」项，本轮落地如下。

## 玩法

| 项 | 实现 |
|----|------|
| 公会战闭环 | `GuildWarService`：匹配→报分→积分榜→周期结算；Cmd 984–989；表 `guild_war_*` |
| 家园深度 | `HomeBaseService`：产出、家具、好友互访、DB `home_state` |
| 剧情状态 | `DialogueProgressService` 分支记录/已播放；`CutsceneTriggerService` 跳过/完成与设置联动 |
| PVP/竞技场 | `ArenaRatingService` ELO + 赛季；匹配 mode=10 |
| 日/周刷新 | `PeriodicResetService` + `ClusterJobLock`；周贡献清零 |
| 存档导入导出 | `GET/POST /api/admin/ops/player-data/{uid}/export\|import` |
| **体力（Stamina）** | `StaminaService`：自然恢复/打本消耗/日购；配置 `data/StaminaConfigs.json`；Tick 驱动 |
| **每日任务** | `DailyMissionService`：目标-进度-领奖；与 PeriodicReset 日切耦合；`DailyMissionConfigs.json` |
| **战令** | `BattlePassService`：XP/等级/免费·付费轨；`BattlePassConfigs.json` |
| **主线章节** | `StoryChapterService`：章节进度 + Plane/Floor 解锁门闸；`ChapterConfigs.json`；Quest 主线优先 |
| **好友助战** | `SupportService`：外借快照；开战加入 `BattleContext.participantPlayerIds` |
| **公会科技** | `GuildTechService`：公会等级全局 Buff（攻/防/血/体力恢复） |
| **切图加载态** | `PlayerLoadingStateService`：LoadingTicket；加载中拒绝移动/开战；Cmd 352–353 |
| **版本活动容器** | `VersionActivityService`：树形节点 + 共享 ActivityToken；`VersionActivityConfigs.json` |
| **光锥/遗器深度养成** | `EquipmentAffixService`：随机主/副词条池、强化成长、分解返还、套装加成；`AttributeCalculator` 叠加装备；配置 `EquipmentAffixPool.json` / `RelicSetConfigs.json` |
| **世界 BOSS** | `WorldBossService`：全服血量、阶段、日限、排名奖励；`WorldBossConfigs.json` |
| **深渊/忘却之庭** | `AbyssSeasonService`：周期重置、多队轮战、星级奖励；`AbyssSeasonConfigs.json` |
| **社交赠礼/红包/语音信令** | `GiftService`、`GuildRedPacketService`、`VoiceSignalingService` |
| **收集图鉴** | `HandbookService` + 成就 `handbook_*` |
| **好感度×剧情** | `AffinityService`；对话选项 `affinity:npc:delta` 联动任务旗标 |
| **玩家名片/头像框** | `PlayerCardService` |
| **活动模板** | 转盘/拼图/积分兑换：`ActivityTemplateService` + `ActivityTypeCatalog` |
| **Rogue 程序化地图** | `RogueMapGenerator`（种子可复现） |
| **战斗平衡分析** | `CombatBalanceAnalyticsService` |
| **剧情资源版本** | `StoryAssetVersionService` 与热更联动 |

## 技术

| 项 | 实现 |
|----|------|
| 钱包多节点锁 | Redis 分布式锁 + `data_version` 乐观锁 + CAS 重试（`WalletApplicationService`） |
| 配置灰度读路径 | `ConfigGrayReader` + `@GrayRead` / `GrayReadAspect`（MDC `configInGray`） |
| 战斗快照 hydrate | `BattleContext.fromSnapshot`；`BattleManager.findActiveByPlayerId` 自动恢复；迁移载荷 export/import |
| AOI | `AoiGrid` 九宫格空间索引（Zone 人数上调时降低广播开销） |
| 错误码 Admin API | `GET /api/admin/error-codes`（含轻量 OpenAPI 摘要） |
| 配置版本可视化 | `GET /api/admin/config-versions` + `/admin/config-versions.html` |
| Party 权威 | `Party.ownerNodeId`；非 owner 返回 retcode=8 |
| 离线私聊 | `offline_chat_message`；登录 `flushOffline` |
| 定时任务单点 | `ClusterJobLock` 覆盖归档与周期刷新 |
| 协议 CI | `.github/workflows/ci.yml`：`buf lint/breaking` + `CmdIdUniquenessTest` |
| **协议干燥测试** | `CmdIdProtoCompletenessTest`：Handler 签名 + CsReq/ScRsp 成对 |
| **SupportedFeatures** | 登录交换位掩码；公会战入口按能力隐藏（retcode=20） |
| **Zone 容量** | 默认 1000 + 动态分线 + AOI 异步 + Actor 邮箱；见 `docs/commercial-gap-solutions.md` |

## 运维

| 项 | 实现 |
|----|------|
| 一键回滚 | `POST /api/admin/ops/rollback?confirm=ROLLBACK&reason=` + `/admin/config-rollback.html` |
| 配置版本列表/对比 | `/admin/config-versions.html` → `ConfigVersionController` |
| 数据归档 | `DataArchiveJob` 支持 `categoryRetention`；prod 默认可开；流水默认 30 天 |
| 匹配机器人难度 | `fillWithBots` 按队列均战力 × `botPowerRatio`（ELO/战力同档） |
| 告警阈值 | 登录失败率 >1%/5m warning、>5% critical；上线前按 1.5× 峰值压测校准（见 load-test-baseline） |
| 文档元数据 | `docs/meta/cmdid-config-meta.yaml` + `scripts/generate_docs_from_meta.py` |
| KCP 边缘 TLS | `docs/kcp-crypto.md`：Nginx/Envoy TCP TLS 终结 + UDP ACL |

## 迁移

执行：

1. `src/main/resources/db/migration_p4_feature_gap_fill.sql`
2. `src/main/resources/db/migration_p5_core_loop_systems.sql`（体力/日常/战令/章节/助战/版本活动）
3. `src/main/resources/db/migration_gap_fill_gameplay.sql`（世界BOSS/深渊/赠礼/红包/图鉴/名片/好感等）

## 协议层（已服务端接线，待客户端联调）

| 模块 | Proto | Handler | CmdId |
|------|-------|---------|-------|
| 公会战 | `guild_system.proto` | `GuildPacketHandlers` | 984–989 |
| 家园 | `home_system.proto` | `HomePacketHandlers` | 850–857 |
| 对话/过场 | `dialogue_cutscene.proto` | `DialogueCutscenePacketHandlers` | 860–868 |
| 切图加载完成 | `scene_system.proto` | `ScenePacketHandlers` | 352–353 |
| 战令 | `daily_loop_system.proto` 等 | `BattlePassPacketHandlers` | 990–995、1009 |
| 体力 / 每日 / 章节 | `daily_loop_system.proto` | `DailyLoopPacketHandlers` | 996–1008 |
| 登录能力握手 | `player_session.proto` `supported_features` / `enabled_features` | `PlayerSessionService` | 68/6 |

登录强制 `wire_version>=2`，成功响应含 `session_crypto_key` 与 `enabled_features`。

## P11 体验/社交缺口补齐

| 项 | 实现 |
|----|------|
| 一键领取日常奖励 | `ClaimAllDailyRewardsService` + Cmd 1200–1201；配置 `ClaimAllDailyRewardsConfig.json` |
| 区域探索度/收集品 | `ExplorationService` + `SceneInteractHandler`；Cmd 1202–1204；`ExplorationConfigs.json` |
| 签名/自定义状态 | `PlayerProfileService`；Cmd 1205–1208；`FriendOnlineNotify` 扩展字段；预设 `PlayerStatusPresets.json` |
| 举报/屏蔽 | `PlayerReportBlockService`；Cmd 1209–1213；后台 `/api/admin/social/reports*` |
| 回流活动 | `ReturnCheckService` + `RETURN_PLAYER` 模板；Cmd 1214；`ReturnPlayerActivityConfig.json` |
| 战斗录像分享 | `BattleReplayShareService`；Cmd 1215–1219 |
| 每日挑战次数提醒 | `DailyReminderService`；Cmd 1220–1222；`DailyReminderConfig.json` |
| 自定义表情 | `CustomEmoteService`；Cmd 1223–1224；审核 `/api/admin/social/custom-emotes*` |
| 月卡补发/补签 | `MonthlyCardService.grantTodayIfNeeded`；Cmd 1225–1227；`ActivityTemplateService.makeupSignIn` |
| 好友/公会模糊搜索 | `FriendSearchService` / `GuildSearchService`；Cmd 1228–1233 |
| 荣誉称号 | `TitleService`；Cmd 1234–1237；成就领取联动解锁 |
| 组队一键邀请 | `PartyService.inviteMany`；Cmd 1238–1242 |
| 活动结束提醒 | `ActivityReminderService`；Cmd 1243 |
| 保底计数/消费导出 | `GachaGuaranteeQueryService` / `TransactionHistoryExportService`；Cmd 1244–1247 |

迁移：`src/main/resources/db/migration_qol_social_gap_fill.sql`

协议：`qol_social_system.proto`；入口 `QolSocialPacketHandlers`；登录聚合 `QolLoginHookService`。

- AI 助手 `media_links`：客户端按 `OPEN_VIDEO` / `OPEN_EXTERNAL_LINK` 打开白名单外链（B站/抖音/官方网站）；运营在 `ExternalGuideCatalog.json` 填入真实合作视频 URL
- IAP 渠道密钥建议接 Vault/KMS（见 `production-hardening.md`）
- 真实 KCP 压测客户端替换 k6 HTTP 代理场景
- 无缝 Cell / 实时战斗保持默认关闭
- 脚手架 `chat-service` / `match-service` 可按需挂入网关并替换内存实现

## P10 运营硬化补齐（本轮）

| 项 | 实现 |
|----|------|
| 表现层状态机协议 | `GachaStart`/`GachaResultAck`（Cmd 509–512）；清单见 `docs/presentation-protocol-checklist.md`；配置 `client_ui_params` |
| 月卡 / 首充双倍 / 抽卡返利 | `MonthlyCardService`、`TopUpBonusService`、`GachaRebateService` |
| 钱包乐观锁 + Sticky | 既有 `data_version` CAS；`StickySessionAffinity` + 网关 `UserStickyLoadBalancer` |
| 活动预下载 | `ActivityResourceService` + `POST /api/admin/ops/preload` |
| 助战日限 / 好友体力 | `SupportService` 日用次数；`FriendGiftService` |
| 玩家时间线 | `GET /api/admin/ops/timeline/{uid}` |
| 资源版本封锁 | `ClientRequiredVersions.json` + 登录 `client_res_version` / retcode=10 |
| 战斗重连快进 | `BattleReplayService` ActionTick → ReplayPacket |

迁移：`src/main/resources/db/migration_p10_ops_hardening.sql`
