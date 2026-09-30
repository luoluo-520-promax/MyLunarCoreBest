# 组队迁移 Saga 混沌演练

配合 `PartyMigrateSagaDeadLetterService`：

1. 调用 `injectChaosFailNext()` 模拟下一次 advance 网络分区/节点宕机
2. 或 `chaosKillTxn(txnId, reason)` 直接触发补偿 rollback
3. 超过 3 次重试进入 DeadLetter，运维可从 `/api/admin` 或日志捞取 `party_migrate_dead_letter`

参考已有：`deploy/chaos/player-migration-chaos.yaml`
