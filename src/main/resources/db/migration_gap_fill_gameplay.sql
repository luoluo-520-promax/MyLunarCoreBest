-- 玩法/社交/图鉴/名片等缺口补齐表（幂等）
CREATE TABLE IF NOT EXISTS world_boss_state (
    boss_id INT PRIMARY KEY,
    current_hp BIGINT NOT NULL,
    max_hp BIGINT NOT NULL,
    season_end VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS world_boss_damage (
    boss_id INT NOT NULL,
    player_id INT NOT NULL,
    damage BIGINT NOT NULL DEFAULT 0,
    updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (boss_id, player_id)
);

CREATE TABLE IF NOT EXISTS abyss_player_progress (
    player_id INT NOT NULL,
    season_key VARCHAR(64) NOT NULL,
    cleared_floor INT NOT NULL DEFAULT 0,
    stars INT NOT NULL DEFAULT 0,
    floor_stars_json TEXT,
    updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_id, season_key)
);

CREATE TABLE IF NOT EXISTS player_gift_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    from_player_id INT NOT NULL,
    to_player_id INT NOT NULL,
    currency_id INT NOT NULL,
    amount INT NOT NULL,
    message VARCHAR(256),
    created_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS guild_red_packet (
    packet_id VARCHAR(32) PRIMARY KEY,
    guild_id BIGINT NOT NULL,
    from_player_id INT NOT NULL,
    currency_id INT NOT NULL,
    total_amount INT NOT NULL,
    remain_amount INT NOT NULL,
    total_shares INT NOT NULL,
    remain_shares INT NOT NULL,
    expire_at VARCHAR(64) NOT NULL,
    created_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS guild_red_packet_claim (
    packet_id VARCHAR(32) NOT NULL,
    player_id INT NOT NULL,
    amount INT NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (packet_id, player_id)
);

CREATE TABLE IF NOT EXISTS player_handbook (
    player_id INT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    entry_id INT NOT NULL,
    unlocked_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_id, entry_type, entry_id)
);

CREATE TABLE IF NOT EXISTS player_affinity (
    player_id INT NOT NULL,
    npc_id VARCHAR(64) NOT NULL,
    exp INT NOT NULL DEFAULT 0,
    updated_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_id, npc_id)
);

CREATE TABLE IF NOT EXISTS player_card (
    player_id INT PRIMARY KEY,
    frame_id INT NOT NULL DEFAULT 0,
    title VARCHAR(64) DEFAULT '',
    showcase_json TEXT,
    signature VARCHAR(128) DEFAULT '',
    nickname VARCHAR(64) DEFAULT '',
    level INT DEFAULT 1,
    updated_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS player_avatar_frame (
    player_id INT NOT NULL,
    frame_id INT NOT NULL,
    granted_at VARCHAR(64) NOT NULL,
    PRIMARY KEY (player_id, frame_id)
);

CREATE TABLE IF NOT EXISTS activity_template_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id INT NOT NULL,
    activity_id INT NOT NULL,
    action VARCHAR(32) NOT NULL,
    detail VARCHAR(128),
    created_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS combat_balance_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    avatar_id INT NOT NULL,
    light_cone_id INT NOT NULL,
    relic_set_id INT NOT NULL,
    win TINYINT NOT NULL,
    created_at VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS story_asset_bundle (
    bundle_id VARCHAR(64) PRIMARY KEY,
    version VARCHAR(32) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL
);
