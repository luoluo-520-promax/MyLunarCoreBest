-- P4：公会战闭环、家园持久化、剧情进度、竞技场、离线私聊、周期刷新水位、审计归档

CREATE TABLE IF NOT EXISTS guild_war_season (
    season_id       BIGINT PRIMARY KEY AUTO_INCREMENT,
    season_key      VARCHAR(32) NOT NULL UNIQUE,
    open_at         DATETIME(3) NOT NULL,
    close_at        DATETIME(3) NOT NULL,
    settled         TINYINT NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS guild_war_match (
    match_id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    season_id       BIGINT NOT NULL,
    guild_a_id      BIGINT NOT NULL,
    guild_b_id      BIGINT NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'MATCHED',
    score_a         INT NOT NULL DEFAULT 0,
    score_b         INT NOT NULL DEFAULT 0,
    winner_guild_id BIGINT NULL,
    matched_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    settled_at      DATETIME(3) NULL,
    INDEX idx_gwm_season (season_id),
    INDEX idx_gwm_guild_a (guild_a_id),
    INDEX idx_gwm_guild_b (guild_b_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS guild_war_score (
    season_id       BIGINT NOT NULL,
    guild_id        BIGINT NOT NULL,
    points          INT NOT NULL DEFAULT 0,
    wins            INT NOT NULL DEFAULT 0,
    losses          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (season_id, guild_id),
    INDEX idx_gws_points (season_id, points DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS home_state (
    player_id           INT PRIMARY KEY,
    stamina             INT NOT NULL DEFAULT 100,
    stamina_cap         INT NOT NULL DEFAULT 120,
    facilities_json     JSON NOT NULL,
    stationed_json      JSON NOT NULL,
    furniture_json      JSON NOT NULL,
    production_json     JSON NOT NULL,
    last_recover_at_ms  BIGINT NOT NULL,
    last_produce_at_ms  BIGINT NOT NULL,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS home_visit_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    host_player_id  INT NOT NULL,
    visitor_id      INT NOT NULL,
    visited_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_hvl_host (host_player_id, visited_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS dialogue_progress (
    player_id           INT NOT NULL,
    tree_id             VARCHAR(64) NOT NULL,
    current_node_id     VARCHAR(64) NOT NULL DEFAULT '',
    flags_json          JSON NOT NULL,
    unlocked_cgs_json   JSON NOT NULL,
    choice_history_json JSON NOT NULL,
    played              TINYINT NOT NULL DEFAULT 0,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, tree_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cutscene_progress (
    player_id       INT NOT NULL,
    cutscene_id     VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'TRIGGERED',
    skipped         TINYINT NOT NULL DEFAULT 0,
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, cutscene_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS arena_rating (
    player_id       INT PRIMARY KEY,
    rating          INT NOT NULL DEFAULT 1000,
    wins            INT NOT NULL DEFAULT 0,
    losses          INT NOT NULL DEFAULT 0,
    season_key      VARCHAR(32) NOT NULL DEFAULT 'S0',
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_arena_rating (season_key, rating DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS offline_chat_message (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel_type    INT NOT NULL,
    sender_id       INT NOT NULL,
    sender_name     VARCHAR(64) NOT NULL DEFAULT '',
    target_id       INT NOT NULL,
    content         VARCHAR(512) NOT NULL,
    send_time_ms    BIGINT NOT NULL,
    delivered       TINYINT NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_ocm_target (target_id, delivered, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS periodic_reset_watermark (
    reset_key       VARCHAR(64) PRIMARY KEY,
    last_period     VARCHAR(32) NOT NULL,
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
