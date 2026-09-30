-- P1: 抽卡历史表（审计 + GetGachaHistory）
CREATE TABLE IF NOT EXISTS `gacha_draw_history` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `uid` int UNSIGNED NOT NULL COMMENT '玩家uid',
  `banner_type` int NOT NULL COMMENT '卡池类型',
  `item_id` int NOT NULL COMMENT '产出道具',
  `count` int NOT NULL DEFAULT 1 COMMENT '数量',
  `is_new` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否新获得',
  `cost_currency_id` int NOT NULL DEFAULT 0 COMMENT '消耗货币类型',
  `cost_amount` int NOT NULL DEFAULT 0 COMMENT '本抽消耗货币',
  `tx_id` varchar(64) NULL DEFAULT NULL COMMENT '抽卡事务幂等键',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '抽卡时间',
  PRIMARY KEY (`id`),
  INDEX `idx_gacha_hist_uid_created`(`uid`, `created_at`),
  INDEX `idx_gacha_hist_uid_banner`(`uid`, `banner_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='抽卡历史与审计';
