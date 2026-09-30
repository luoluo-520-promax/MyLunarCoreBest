-- P10：商业化分层 / 社交赠力 / 活动资源预载 / 助战日限 / 抽卡表现会话

CREATE TABLE IF NOT EXISTS player_monthly_card (
    player_id           INT NOT NULL,
    card_kind           VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
    active              TINYINT NOT NULL DEFAULT 1,
    expire_at           DATETIME(3) NOT NULL,
    remaining_days      INT NOT NULL DEFAULT 0,
    daily_currency_id   INT NOT NULL DEFAULT 1,
    daily_currency_amt  INT NOT NULL DEFAULT 90,
    daily_stamina_amt   INT NOT NULL DEFAULT 60,
    last_grant_day      VARCHAR(16) NOT NULL DEFAULT '',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (player_id, card_kind),
    INDEX idx_monthly_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='月卡/小月卡状态';

CREATE TABLE IF NOT EXISTS player_topup_bonus (
    player_id           INT PRIMARY KEY,
    first_topup_done    TINYINT NOT NULL DEFAULT 0,
    year_cycle          VARCHAR(8) NOT NULL DEFAULT '',
    year_reset_at       DATETIME(3) NULL,
    total_topup_cents   BIGINT NOT NULL DEFAULT 0,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='首充双倍与年度重置';

CREATE TABLE IF NOT EXISTS player_gacha_rebate (
    player_id           INT PRIMARY KEY,
    points              INT NOT NULL DEFAULT 0,
    lifetime_draws      INT NOT NULL DEFAULT 0,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抽卡返利积分（星尘）';

CREATE TABLE IF NOT EXISTS gacha_presentation_session (
    session_id          VARCHAR(64) PRIMARY KEY,
    player_id           INT NOT NULL,
    banner_type         INT NOT NULL,
    times               INT NOT NULL,
    state               VARCHAR(16) NOT NULL DEFAULT 'STARTED',
    client_nonce        VARCHAR(64) NOT NULL DEFAULT '',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    closed_at           DATETIME(3) NULL,
    INDEX idx_gps_player (player_id, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抽卡表现层状态机会话';

CREATE TABLE IF NOT EXISTS friend_stamina_gift_log (
    gift_day            VARCHAR(16) NOT NULL,
    from_player_id      INT NOT NULL,
    to_player_id        INT NOT NULL,
    amount              INT NOT NULL DEFAULT 5,
    claimed             TINYINT NOT NULL DEFAULT 0,
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (gift_day, from_player_id, to_player_id),
    INDEX idx_fsg_to (to_player_id, gift_day, claimed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='好友体力赠送/领取';

CREATE TABLE IF NOT EXISTS support_daily_usage (
    usage_day           VARCHAR(16) NOT NULL,
    lender_id           INT NOT NULL,
    borrower_id         INT NOT NULL,
    use_count           INT NOT NULL DEFAULT 1,
    PRIMARY KEY (usage_day, lender_id, borrower_id),
    INDEX idx_sdu_lender (lender_id, usage_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='助战角色每日使用次数（每好友每天一次）';

CREATE TABLE IF NOT EXISTS activity_resource_version (
    version_activity_id INT PRIMARY KEY,
    cdn_bundle_version  VARCHAR(64) NOT NULL,
    cdn_manifest_url    VARCHAR(512) NOT NULL DEFAULT '',
    preload_ready       TINYINT NOT NULL DEFAULT 0,
    active              TINYINT NOT NULL DEFAULT 0,
    open_at             DATETIME(3) NULL,
    close_at            DATETIME(3) NULL,
    config_json         JSON NULL,
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='活动 CDN 资源版本与开关';

CREATE TABLE IF NOT EXISTS activity_preload_cache (
    cache_key           VARCHAR(128) PRIMARY KEY,
    payload_json        MEDIUMTEXT NOT NULL,
    activate_at         DATETIME(3) NULL,
    activated           TINYINT NOT NULL DEFAULT 0,
    created_by          VARCHAR(64) NOT NULL DEFAULT '',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运维预加载配置缓存';

-- 流水表复合索引：客服时间线按 uid+时间回溯（若已存在可忽略报错）
-- CREATE INDEX idx_wallet_ledger_uid_created ON wallet_ledger (uid, created_at);
