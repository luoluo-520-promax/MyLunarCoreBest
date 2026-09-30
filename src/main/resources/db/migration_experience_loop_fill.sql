-- 体验闭环补齐（wire v3）：体力储备、过期道具、家园拜访日志、对话存档点
-- 可重复执行：列/表已存在时忽略错误（由运维脚本或手工判断）

ALTER TABLE player_stamina_meta
    ADD COLUMN reserve_stamina INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS player_timed_item (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id       INT NOT NULL,
    item_id         INT NOT NULL,
    count           INT NOT NULL DEFAULT 0,
    expire_at_ms    BIGINT NOT NULL,
    created_at_ms   BIGINT NOT NULL,
    INDEX idx_timed_item_expire (expire_at_ms),
    INDEX idx_timed_item_player (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS home_visitor_social_log (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    host_player_id  INT NOT NULL,
    visitor_id      INT NOT NULL,
    action          VARCHAR(32) NOT NULL,
    message         VARCHAR(200) NOT NULL DEFAULT '',
    unread          TINYINT(1) NOT NULL DEFAULT 1,
    created_at_ms   BIGINT NOT NULL,
    INDEX idx_home_visitor_host (host_player_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE dialogue_progress
    ADD COLUMN savepoint_node_id VARCHAR(64) NULL;
