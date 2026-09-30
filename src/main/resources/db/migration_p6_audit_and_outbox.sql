-- 管理后台审计扩展：客户端 IP / UA / 操作前后值
-- 若列已存在请跳过本段（一次性迁移）
ALTER TABLE admin_audit_log ADD COLUMN client_ip VARCHAR(64) NULL;
ALTER TABLE admin_audit_log ADD COLUMN user_agent VARCHAR(512) NULL;
ALTER TABLE admin_audit_log ADD COLUMN before_json TEXT NULL;
ALTER TABLE admin_audit_log ADD COLUMN after_json TEXT NULL;

-- Outbox 投递游标（补偿 worker 使用）
CREATE TABLE IF NOT EXISTS outbox_relay_cursor (
    worker_name  VARCHAR(64)  NOT NULL PRIMARY KEY,
    last_tx_id   VARCHAR(64)  NULL,
    updated_at   VARCHAR(40)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 防重放 TTL 辅助列（归档/清理用）
ALTER TABLE biz_replay_guard ADD COLUMN expires_at VARCHAR(40) NULL;
