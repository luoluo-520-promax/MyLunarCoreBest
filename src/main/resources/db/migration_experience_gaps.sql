-- 体验短板补齐：扫荡战绩、新手引导检查点
CREATE TABLE IF NOT EXISTS player_stage_clear_best (
    player_id       INT NOT NULL,
    stage_id        INT NOT NULL,
    best_turn_count INT NOT NULL,
    updated_at      DATETIME NOT NULL,
    PRIMARY KEY (player_id, stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE player_newbie_guide ADD COLUMN checkpoint_step_id VARCHAR(64) NULL;
ALTER TABLE player_newbie_guide ADD COLUMN skipped TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE player_newbie_guide ADD COLUMN committed_json TEXT NULL;
