-- P8: 高频查询复合索引 + config_release 兼容说明
-- 若索引已存在，手工跳过对应 ALTER（MySQL 无通用 IF NOT EXISTS 索引语法）。

-- 钱包流水：登录/对账按 uid + 时间
ALTER TABLE wallet_ledger ADD INDEX idx_wallet_ledger_uid_time (uid, created_at);

-- 抽卡历史：玩家页按 uid + 时间
ALTER TABLE gacha_draw_history ADD INDEX idx_gacha_history_uid_time (uid, created_at);

-- 本地事务日志：补偿任务按 status + created_at
ALTER TABLE local_tx_log ADD INDEX idx_local_tx_status_created (status, created_at);

-- 匹配/在线相关（若表存在）
-- ALTER TABLE player ADD INDEX idx_player_updated (updated_at);
