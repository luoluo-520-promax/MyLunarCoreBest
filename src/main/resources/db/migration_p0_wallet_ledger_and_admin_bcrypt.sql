-- P0: 钱包流水表（已有库可单独执行）
CREATE TABLE IF NOT EXISTS `wallet_ledger` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `uid` int UNSIGNED NOT NULL COMMENT '玩家uid',
  `currency_id` int NOT NULL COMMENT '货币类型',
  `delta` int NOT NULL COMMENT '变更量（加正减负）',
  `reason` varchar(128) NOT NULL DEFAULT '' COMMENT '业务原因',
  `balance_before` int NOT NULL COMMENT '变更前余额',
  `balance_after` int NOT NULL COMMENT '变更后余额',
  `tx_id` varchar(64) NULL DEFAULT NULL COMMENT '事务/幂等键',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  INDEX `idx_wallet_ledger_uid`(`uid`),
  INDEX `idx_wallet_ledger_created`(`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='钱包变更流水';

-- 将 {noop} 种子口令替换为 bcrypt（密码分别为 ops123/sup123/aud123/view123）
UPDATE `admin_user` SET `password_hash` = '{bcrypt}$2a$10$bu9d60nQWdOXveygaSj.buRdnWD0Iqe2/2/mpY0JnvuM5Xyzm4..y' WHERE `username` = 'ops01';
UPDATE `admin_user` SET `password_hash` = '{bcrypt}$2a$10$FPnaI3GAVOZUPG4jhbTr7ORBl54ngxxSgeZaophnBl76D533leZBi' WHERE `username` = 'support01';
UPDATE `admin_user` SET `password_hash` = '{bcrypt}$2a$10$XnSVz6P6ijzFdBBBviv3eOIBOepgXRNtMQKr4IHlNv4F/o8DbKcTC' WHERE `username` = 'auditor01';
UPDATE `admin_user` SET `password_hash` = '{bcrypt}$2a$10$WAM.MVMmWOJSdlI/mI8LVen4d0VpH08/FZqujZl4H.zIaLZrpras.' WHERE `username` = 'viewer01';
