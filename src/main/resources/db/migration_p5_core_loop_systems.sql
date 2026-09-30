-- P5：体力/日常任务/战令/主线章节/助战/加载态/版本活动容器

CREATE TABLE IF NOT EXISTS player_stamina_meta (
    player_id           INT PRIMARY KEY,
    last_regen_at_ms    BIGINT NOT NULL,
    daily_buy_count     INT NOT NULL DEFAULT 0,
    buy_day             VARCHAR(16) NOT NULL DEFAULT '',
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS daily_mission_progress (
    player_id           INT NOT NULL,
    mission_day         VARCHAR(16) NOT NULL,
    mission_id          INT NOT NULL,
    progress            INT NOT NULL DEFAULT 0,
    claimed             TINYINT NOT NULL DEFAULT 0,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, mission_day, mission_id),
    INDEX idx_dmp_day (mission_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS battle_pass_progress (
    player_id           INT NOT NULL,
    season_id           INT NOT NULL,
    xp                  INT NOT NULL DEFAULT 0,
    level               INT NOT NULL DEFAULT 0,
    premium             TINYINT NOT NULL DEFAULT 0,
    claimed_free_json   JSON NOT NULL,
    claimed_premium_json JSON NOT NULL,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, season_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS story_chapter_progress (
    player_id               INT PRIMARY KEY,
    current_chapter_id      INT NOT NULL DEFAULT 1,
    completed_chapters_json JSON NOT NULL,
    unlocked_planes_json    JSON NOT NULL,
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS support_unit (
    player_id           INT PRIMARY KEY,
    avatar_instance_id  BIGINT NOT NULL,
    avatar_id           INT NOT NULL,
    level               INT NOT NULL,
    promotion           INT NOT NULL DEFAULT 0,
    rank_val            INT NOT NULL DEFAULT 0,
    snapshot_json       JSON NOT NULL,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS version_activity_progress (
    player_id               INT NOT NULL,
    version_activity_id     INT NOT NULL,
    token_balance           INT NOT NULL DEFAULT 0,
    completed_nodes_json    JSON NOT NULL,
    updated_at              DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, version_activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
