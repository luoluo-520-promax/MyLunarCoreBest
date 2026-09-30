-- 助战借用日志 + 聊天运营表
CREATE TABLE IF NOT EXISTS support_borrow_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  requester_id INT NOT NULL,
  lender_id INT NOT NULL,
  borrow_day VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  INDEX idx_support_borrow_req_day (requester_id, borrow_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_mute (
  player_id INT NOT NULL PRIMARY KEY,
  unmute_at_ms BIGINT NOT NULL,
  operator VARCHAR(64) NOT NULL DEFAULT '',
  reason VARCHAR(256) NOT NULL DEFAULT '',
  updated_at VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_report (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  reporter_id INT NOT NULL,
  target_id INT NOT NULL,
  content TEXT,
  reason VARCHAR(256) NOT NULL DEFAULT '',
  created_at VARCHAR(40) NOT NULL,
  INDEX idx_chat_report_target (target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_id INT NOT NULL,
  target_id INT NOT NULL,
  action VARCHAR(32) NOT NULL,
  detail VARCHAR(512) NOT NULL DEFAULT '',
  created_at VARCHAR(40) NOT NULL,
  INDEX idx_chat_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
