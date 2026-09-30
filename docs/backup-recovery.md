# 备份与恢复演练

## MySQL

| 项 | 建议 |
|----|------|
| 全量 | 每日 `mysqldump --single-transaction --routines --triggers` 或云厂商快照 |
| 增量 | 开启 binlog（ROW），保留 ≥7 天 |
| 校验 | 恢复到隔离实例后跑 `scripts/publish_drill.ps1` 冒烟 |

示例（Linux 运维机）：

```bash
mysqldump -h "$DB_HOST" -u backup -p --single-transaction mylunarcore \
  | gzip > "/backup/mysql/mylunarcore-$(date +%F).sql.gz"
```

## Redis

| 项 | 建议 |
|----|------|
| RDB | `save 900 1` 等策略；每日拷贝 `dump.rdb` |
| AOF | 生产建议 `appendonly yes` + `everysec` |
| 注意 | 票据/锁/聊天 PubSub 可丢失；**钱包权威在 MySQL** |

## 月度演练清单

1. 选备份点恢复到 staging。
2. 校验：登录、钱包余额、抽卡流水、公会成员、邮件未领。
3. 记录 RTO/RPO，更新 `docs/load-test-baseline.md` 旁注。
4. 演练失败不得直接在生产重试破坏性命令。

## 脚本

- 冷历史导出：`scripts/archive/export_and_purge.ps1`
- 冷恢复参考：`scripts/archive/restore_cold.ps1`
