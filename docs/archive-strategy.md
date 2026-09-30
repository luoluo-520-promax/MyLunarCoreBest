# 数据归档策略

## 保留周期（热表）

| 表 | 默认保留 | 配置项 |
|----|----------|--------|
| gacha_draw_history | 180 天 | `lunarcore.data-retention.gacha-history-days` |
| wallet_ledger | 90 天 | `lunarcore.data-retention.ledger-archive-days` |
| biz_replay_guard | 90 天 | `battle-audit-days` |
| offline_chat_message（已投递） | ≥7 天 | `chat-archive-days` |
| admin_audit_log | ≥30 天 | `chat-archive-days`（下限 30） |

## 冷归档

1. 设置 `lunarcore.data-retention.cold-archive-jdbc-url`（ClickHouse JDBC）。
2. 冷库预先建同名表结构。
3. `DataArchiveJob`：先 `ColdArchiveClient.copyExpiredRows`，完整性校验通过后再 DELETE 热表。
4. 生产建议：`archive-job-enabled=true`，cron 凌晨低峰。

## 完整性校验

复制批次行数 ≥ 热表过期行数（采样 LIMIT 5000）；缺口写 warn，跳过删除以防丢数。
