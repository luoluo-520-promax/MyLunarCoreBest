-- 本地事务日志：抽卡/支付等写路径补偿与对账
CREATE TABLE IF NOT EXISTS local_tx_log (
    tx_id        VARCHAR(64)  NOT NULL PRIMARY KEY,
    biz_type     VARCHAR(64)  NOT NULL,
    player_id    INT          NOT NULL,
    payload_json TEXT         NOT NULL,
    status       VARCHAR(32)  NOT NULL,
    created_at   VARCHAR(40)  NOT NULL,
    updated_at   VARCHAR(40)  NOT NULL,
    INDEX idx_local_tx_status_created (status, created_at),
    INDEX idx_local_tx_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 防重放：业务 nonce / channel_tx 唯一
CREATE TABLE IF NOT EXISTS biz_replay_guard (
    guard_key    VARCHAR(128) NOT NULL PRIMARY KEY,
    biz_type     VARCHAR(64)  NOT NULL,
    player_id    INT          NOT NULL,
    created_at   VARCHAR(40)  NOT NULL,
    INDEX idx_replay_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 管理后台审计（热更/导入/工单）
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    operator     VARCHAR(128) NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    resource     VARCHAR(256) NOT NULL DEFAULT '',
    diff_json    TEXT         NOT NULL,
    success      TINYINT(1)   NOT NULL DEFAULT 1,
    created_at   VARCHAR(40)  NOT NULL,
    INDEX idx_admin_audit_created (created_at),
    INDEX idx_admin_audit_operator (operator)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 成就进度
CREATE TABLE IF NOT EXISTS player_achievement (
    player_id      INT          NOT NULL,
    achievement_id VARCHAR(64)  NOT NULL,
    progress       INT          NOT NULL DEFAULT 0,
    claimed        TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at     VARCHAR(40)  NOT NULL,
    PRIMARY KEY (player_id, achievement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 新手引导进度
CREATE TABLE IF NOT EXISTS player_newbie_guide (
    player_id    INT          NOT NULL PRIMARY KEY,
    step_id      VARCHAR(64)  NOT NULL,
    completed    TINYINT(1)   NOT NULL DEFAULT 0,
    updated_at   VARCHAR(40)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
