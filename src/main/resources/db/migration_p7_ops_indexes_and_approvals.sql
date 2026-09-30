-- P7: 核心表复合索引 + 敏感审批 + 家园点赞
-- 若索引已存在，手工跳过对应 ALTER（MySQL 无通用 IF NOT EXISTS 索引语法）。
-- 复合索引正式启用见 migration_p8_hot_path_indexes.sql。

-- 钱包流水：按 uid + 时间（已迁移至 p8）
-- ALTER TABLE wallet_ledger ADD INDEX idx_wallet_ledger_uid_time (uid, created_at);

-- 抽卡历史（已迁移至 p8）
-- ALTER TABLE gacha_draw_history ADD INDEX idx_gacha_history_uid_time (uid, created_at);

CREATE TABLE IF NOT EXISTS home_like (
  host_player_id INT NOT NULL,
  visitor_id INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (host_player_id, visitor_id),
  INDEX idx_home_like_host (host_player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS admin_sensitive_approval (
  ticket_id VARCHAR(64) NOT NULL PRIMARY KEY,
  op_type VARCHAR(64) NOT NULL,
  payload_json TEXT,
  requester VARCHAR(64) NOT NULL,
  approver VARCHAR(64) DEFAULT '',
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_approval_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
