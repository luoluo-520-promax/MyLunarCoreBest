-- 角色穿戴皮肤：0 表示使用该角色默认皮肤
ALTER TABLE `avatar`
  ADD COLUMN `equipped_skin_id` int UNSIGNED NOT NULL DEFAULT 0
  COMMENT '当前穿戴皮肤ID；0表示默认皮肤' AFTER `locked`;
