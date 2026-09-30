# 配置版本管理与回滚

## 现状

| 能力 | 状态 |
|------|------|
| 文件级热更 | `HotReloadCoordinator` + `VersionHotReloadService` |
| 导入前备份 | `ConfigFileService` 写 `.bak` / 备份目录 |
| 一键回滚到备份 | `ConfigFileService.rollbackFromBackup` |
| Admin 审计 | `AdminAuditLogService` → `admin_audit_log` |
| 发布审批流 | **未实现**（建议下阶段走工单 `SupportTicket`） |
| 配置版本库（多版本并存） | **基线**：用 `config_release` 表记录指纹与游戏版本绑定 |
| **灰度发布** | **已落地骨架**：`ConfigGrayRelease`（UID 尾号 / 服务器白名单） |

## 灰度发布（卡池等敏感配置）

1. 开启：`lunarcore.config-gray.enabled=true`
2. 指定先生效玩家：`lunarcore.config-gray.uid-tails=0,1,2`（UID % 10）
3. 或指定服：`lunarcore.config-gray.server-ids=s1,s2`
4. 热更后 `HotReloadCoordinator` 会刷新灰度策略并记审计；灰度关闭时视为全量生效
5. 业务读配置时调用 `ConfigGrayRelease.inGray(uid, serverId)` 选择新旧配置指针（卡池等写路径需显式分支）

## 启用建议（生产）

1. 热更仅允许 Admin 角色 + `INTERNAL_API_TOKEN`。
2. 每次导入写入 `config_release`（见 `migration_p3_guild_and_config_release.sql`）。
3. **先灰度再全量**：卡池/商店价格变更必须灰度验证后再关灰度开关。
4. 回滚：选历史 `release_id` → 恢复文件 → 热加载 → 审计。
5. 与客户端资源版本绑定：拒绝「配置版本 > 客户端可识别版本」的静默加载。

## 代码热更边界

- **支持**：JSON/CSV 策划配置。
- **不支持**：任意 Java 类热替换。
- **可选后续**：战斗公式 / 掉落抽成 Groovy 脚本动态加载；或 Spring Cloud RefreshScope 仅限旁路 Bean。
