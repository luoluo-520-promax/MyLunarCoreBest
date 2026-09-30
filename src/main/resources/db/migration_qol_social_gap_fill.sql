-- QoL / social gap-fill tables (custom emote, title, recent contact, reminder dedup)
-- Safe to run repeatedly (IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS player_custom_emote (
    custom_emote_id INT          NOT NULL AUTO_INCREMENT,
    player_id       INT          NOT NULL,
    name            VARCHAR(64)  NOT NULL DEFAULT '',
    content_type    VARCHAR(64)  NOT NULL DEFAULT 'image/png',
    image_data      MEDIUMBLOB   NULL,
    review_status   VARCHAR(16)  NOT NULL DEFAULT 'pending',
    created_at      VARCHAR(40)  NOT NULL,
    updated_at      VARCHAR(40)  NOT NULL,
    PRIMARY KEY (custom_emote_id),
    INDEX idx_custom_emote_player (player_id),
    INDEX idx_custom_emote_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Prefer IDs starting at 100000 (application also enforces floor)
ALTER TABLE player_custom_emote AUTO_INCREMENT = 100000;

CREATE TABLE IF NOT EXISTS player_title (
    player_id    INT         NOT NULL,
    title_id     VARCHAR(64) NOT NULL,
    unlocked_at  VARCHAR(40) NOT NULL,
    PRIMARY KEY (player_id, title_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_social_profile (
    player_id          INT          NOT NULL,
    signature          VARCHAR(160) NOT NULL DEFAULT '',
    status_message     VARCHAR(80)  NOT NULL DEFAULT '',
    custom_status      VARCHAR(32)  NOT NULL DEFAULT 'ONLINE',
    equipped_title_id  VARCHAR(64)  NOT NULL DEFAULT '',
    updated_at         VARCHAR(40)  NOT NULL,
    PRIMARY KEY (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 已有表补列（忽略重复列错误）
SET @qol_title_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'player_social_profile'
    AND COLUMN_NAME = 'equipped_title_id'
);
SET @qol_title_sql := IF(@qol_title_col = 0,
  'ALTER TABLE player_social_profile ADD COLUMN equipped_title_id VARCHAR(64) NOT NULL DEFAULT ''''',
  'SELECT 1');
PREPARE qol_title_stmt FROM @qol_title_sql;
EXECUTE qol_title_stmt;
DEALLOCATE PREPARE qol_title_stmt;
CREATE TABLE IF NOT EXISTS player_recent_contact (
    player_id  INT         NOT NULL,
    other_id   BIGINT      NOT NULL,
    reason     VARCHAR(32) NOT NULL DEFAULT '',
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (player_id, other_id),
    INDEX idx_recent_contact_updated (player_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_activity_reminder_dedup (
    player_id   INT         NOT NULL,
    activity_id INT         NOT NULL,
    kind        VARCHAR(16) NOT NULL,
    pushed_at   VARCHAR(40) NOT NULL,
    PRIMARY KEY (player_id, activity_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS activity_template_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id   INT          NOT NULL,
    activity_id INT          NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    detail      VARCHAR(256)  NOT NULL DEFAULT '',
    created_at  VARCHAR(40)  NOT NULL,
    INDEX idx_activity_tpl_player (player_id, activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_exploration (
    player_id    INT         NOT NULL,
    plane_id     INT         NOT NULL,
    floor_id     INT         NOT NULL,
    collect_id   VARCHAR(64) NOT NULL,
    kind         VARCHAR(32) NOT NULL DEFAULT 'other',
    collected_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (player_id, plane_id, floor_id, collect_id),
    INDEX idx_exploration_region (player_id, plane_id, floor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_report (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    reporter_id    INT          NOT NULL,
    target_id      INT          NOT NULL,
    scene          VARCHAR(32)  NOT NULL DEFAULT '',
    evidence_type  VARCHAR(32)  NOT NULL DEFAULT '',
    evidence       TEXT,
    reason         VARCHAR(256) NOT NULL DEFAULT '',
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    resolve_result VARCHAR(32)  NOT NULL DEFAULT '',
    resolve_note   VARCHAR(256) NOT NULL DEFAULT '',
    operator       VARCHAR(64)  NOT NULL DEFAULT '',
    created_at     VARCHAR(40)  NOT NULL,
    resolved_at    VARCHAR(40)  NULL,
    INDEX idx_report_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_block (
    player_id  INT NOT NULL,
    blocked_id INT NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (player_id, blocked_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_return_activity (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id    INT NOT NULL,
    activity_id  INT NOT NULL,
    offline_days INT NOT NULL DEFAULT 0,
    expire_at    DATETIME(3) NOT NULL,
    active       TINYINT NOT NULL DEFAULT 1,
    created_at   VARCHAR(40) NOT NULL,
    INDEX idx_return_player (player_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_daily_reminder_pref (
    player_id         INT PRIMARY KEY,
    enabled           TINYINT NOT NULL DEFAULT 1,
    disabled_modules  VARCHAR(256) NOT NULL DEFAULT '',
    updated_at        VARCHAR(40) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

