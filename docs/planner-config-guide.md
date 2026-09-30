# 策划配置手册（data/*.json）

配置热更入口：Admin `HotReloadController` / `ConfigImportController`。导入后建议走「**dry-run 差异** → 确认 → 发布」，失败可用文件备份回滚。

- Dry-run：`POST /api/admin/ops/import/json-files` 带 `"dryRun":true`，响应含 `diff[].status=changed|unchanged|new`。
- 可视化回滚页：`/admin/config-rollback.html`（需先登录）。
- 灰度：`ConfigGrayReader` 统一分流；开启 `lunarcore.config-gray.enabled` 后业务读 canary/stable。

## 通用约定

| 字段习惯 | 含义 |
|----------|------|
| `*Id` | 正整数主键；跨表引用必须存在 |
| 时间戳 | 多数为 Unix 秒；活动排期见 `ActivityScheduling.json` |
| 道具 | `itemId` 必须在物品表 / `items_config.csv` 可解析 |
| 热更 | 仅 data 下 JSON/CSV；Java 逻辑仍须发版重启 |

## 文件一览

| 文件 | 用途 | 关键关联 |
|------|------|----------|
| `Banners.json` | 卡池 UP、消耗货币 | `cost_item_id` → 货币；奖励 → Item |
| `ShopConfigs.json` | 商店货架 | `product.itemId`、`currencyId` |
| `SkinConfigs.json` | 皮肤衣柜 | `avatarId` → 角色 |
| `ActivityConfigs.json` | 活动定义（类型见下） | `shopId`、代币 `tokenId` |
| `ActivityScheduling.json` | 活动开关窗 | `activityId` → ActivityConfigs |
| `activities/activity.*.json` | 单活动详案 | 与 ActivityConfigs 同 id |
| `QuestConfigs.json` | 任务 | 奖励 item、解锁条件 |
| `EncounterConfigs.json` | 遭遇战斗 | 怪物/关卡 |
| `NewbieGuide.json` | 新手步骤 | UI/解锁 |
| `GuidePack.json` | 端侧攻略包 | AI/教练 |
| `ExternalGuideCatalog.json` | 主流平台外链/视频攻略（B站/抖音/官方网站白名单） | AI/教练 |
| `CoachTips.json` | 规则教练提示 | 场景/条件 |
| `AssistFeatureContent.json` / `AssistSafetyRules.json` | AI 助手文案与安全 | 勿含密钥 |
| `GuildShopConfigs.json` | 公会商店 | 贡献币兑换 |
| `ActivityTypeCatalog.json` | 活动类型模板目录 | 策划选模板用 |
| `hotfix.json` | 热更元数据 | 版本指纹 |
| `DialogueTrees.json` | 对话树/分支 | 条件表达式见下 |
| `CutsceneConfigs.json` | 过场触发 | `trigger` / `nextCutsceneId` |
| `HomeFacilityConfigs.json` | 家园基建 | 产出/槽位 |
| `BattlePassConfigs.json` | 战令/通行证 | 等级/奖励轨道 |
| `DailyMissionConfigs.json` | 日常任务 | 可加 BP XP |
| `StaminaConfigs.json` | 体力 | 与 PeriodicReset 联动 |
| `ChapterConfigs.json` | 章节进度 | 解锁条件 |
| `VersionActivityConfigs.json` | 版本活动 | 与排期联动 |

## 对话树 / 过场条件表达式

`DialogueTrees.json` 节点支持：

| 语法 | 含义 | 示例 |
|------|------|------|
| `var:name==value` | 变量等于 | `var:quest_1001==done` |
| `var:name>=n` | 数值比较 | `var:level>=20` |
| `flag:name` | 布尔标记为真 | `flag:met_npc_3` |
| `item:id>=n` | 持有道具数量 | `item:201>=1` |
| `&&` / `\|\|` | 与 / 或 | `var:level>=10&&flag:tutorial` |

分支：`choices[].condition` 不满足则客户端隐藏或灰显；服务端 `DialogueTriggerEngine` 再校验一次防作弊。

过场：`CutsceneConfigs.json` 的 `trigger` 可用同语法；播完写进度键，避免重复触发。

Excel 导入：可用 Admin `ConfigImportController` 或 `scripts/import_activity_excel.py` 模式扩展对话表（建议列：`treeId,nodeId,text,condition,next`）。

## 活动类型（`activityType`）

合法取值见 `ActivityTypeCatalog.json`：`signin` / `limited_challenge` / `tower` / `coop` / `seasonal` / `exchange_shop` 等。新类型：先在目录登记 handler 与必填字段，再写 ActivityConfigs。

## 养成相关（道具类型）

物品 `type` 约定（与 `ItemApplicationService` 对齐）：

- `1`：光锥 / 专武（可叠影 `rank`）
- `2`：遗器 / 圣遗物（随机词条刷取，后续扩展 substats）
- 角色星魂 / 命座：通过重复获得提升 `AvatarEntity` / item `rank` 字段（深坑系统持续补齐）

## 版本与回滚

1. 导入前自动备份当前文件。
2. Admin 审计写入 `admin_audit_log`。
3. 回滚：调用备份恢复接口后热加载。
4. 建议将配置变更绑定游戏客户端 `PROTOCOL_WIRE_VERSION` / 资源版本，避免老客户端读到不兼容字段。

## 导入 Excel

活动模板：`data/activity_template.xlsx` + `scripts/import_activity_excel.py`（依赖见 `scripts/requirements-import.txt`）。
