-- P0: 玩家主表乐观锁版本（已有库增量升级；若列已存在请跳过）
ALTER TABLE `player`
  ADD COLUMN `data_version` bigint UNSIGNED NOT NULL DEFAULT 0
  COMMENT '乐观锁版本，落盘条件更新' AFTER `last_logout`;
