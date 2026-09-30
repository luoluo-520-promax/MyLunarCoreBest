-- 公会基础表 + 配置发布指纹
CREATE TABLE IF NOT EXISTS guild (
    guild_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(64)  NOT NULL,
    notice        VARCHAR(512) NOT NULL DEFAULT '',
    level         INT          NOT NULL DEFAULT 1,
    exp           INT          NOT NULL DEFAULT 0,
    leader_id     INT          NOT NULL,
    member_count  INT          NOT NULL DEFAULT 1,
    max_members   INT          NOT NULL DEFAULT 30,
    created_at    VARCHAR(40)  NOT NULL,
    UNIQUE KEY uk_guild_name (name),
    INDEX idx_guild_leader (leader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS guild_member (
    guild_id       BIGINT       NOT NULL,
    player_id      INT          NOT NULL,
    role           TINYINT      NOT NULL DEFAULT 0 COMMENT '0=member 1=officer 2=leader',
    contribution   INT          NOT NULL DEFAULT 0,
    weekly_contrib INT          NOT NULL DEFAULT 0,
    joined_at      VARCHAR(40)  NOT NULL,
    PRIMARY KEY (guild_id, player_id),
    UNIQUE KEY uk_guild_player (player_id),
    INDEX idx_guild_member_guild (guild_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS guild_shop_purchase (
    guild_id    BIGINT NOT NULL,
    player_id   INT    NOT NULL,
    product_id  INT    NOT NULL,
    week_key    VARCHAR(16) NOT NULL,
    buy_count   INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (guild_id, player_id, product_id, week_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS config_release (
    release_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_name    VARCHAR(128) NOT NULL,
    content_hash   VARCHAR(64)  NOT NULL,
    game_version   VARCHAR(64)  NOT NULL DEFAULT '',
    operator       VARCHAR(128) NOT NULL DEFAULT '',
    note           VARCHAR(512) NOT NULL DEFAULT '',
    created_at     VARCHAR(40)  NOT NULL,
    INDEX idx_config_release_name (config_name, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
