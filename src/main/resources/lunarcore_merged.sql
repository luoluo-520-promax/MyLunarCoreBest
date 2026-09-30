/*
 * MyLunarCore 合并数据库脚本
 * 来源：Desktop/lunarcore.sql（Navicat 全量表结构） + src/main/resources/schema.sql（rogue_room、game_data、后台 RBAC）
 * 用途：一键建库 + 关联测试数据（每表 ≥5 条，账号→玩家→各子表与后台权限链互通）
 *
 * MySQL 8.0+，执行前请确认目标库名，或在本文件首行添加：USE your_database;
 */
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

/* ========== 删除顺序：先后台关联表，再其余（游戏表无 FK 时可任意顺序） ========== */
DROP TABLE IF EXISTS `admin_user_role`;
DROP TABLE IF EXISTS `admin_role_permission`;
DROP TABLE IF EXISTS `admin_user`;
DROP TABLE IF EXISTS `admin_role`;
DROP TABLE IF EXISTS `admin_permission`;
DROP TABLE IF EXISTS `game_data`;
DROP TABLE IF EXISTS `rogue_room`;
DROP TABLE IF EXISTS `summon_unit_config`;
DROP TABLE IF EXISTS `scene_config`;
DROP TABLE IF EXISTS `rogue_talent`;
DROP TABLE IF EXISTS `rogue_player_data`;
DROP TABLE IF EXISTS `rogue`;
DROP TABLE IF EXISTS `player_gacha_info`;
DROP TABLE IF EXISTS `player_gacha_banner_info`;
DROP TABLE IF EXISTS `npc_config`;
DROP TABLE IF EXISTS `monster_config`;
DROP TABLE IF EXISTS `maze_skill_action`;
DROP TABLE IF EXISTS `maze_skill`;
DROP TABLE IF EXISTS `maze_buff`;
DROP TABLE IF EXISTS `lineup`;
DROP TABLE IF EXISTS `game_item`;
DROP TABLE IF EXISTS `friend`;
DROP TABLE IF EXISTS `challenge_history`;
DROP TABLE IF EXISTS `challenge_group_reward`;
DROP TABLE IF EXISTS `challenge`;
DROP TABLE IF EXISTS `battle_monster_wave`;
DROP TABLE IF EXISTS `battle`;
DROP TABLE IF EXISTS `avatar`;
DROP TABLE IF EXISTS `player`;
DROP TABLE IF EXISTS `account`;

/* ========================= 以下为 lunarcore.sql 表结构（原样合并） ========================= */

CREATE TABLE `account`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '账户唯一标识',
  `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `email` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '电子邮箱',
  `phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '手机号',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '账户状态：0-封禁，1-正常',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `last_login_at` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `last_login_ip` varchar(45) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最后登录IP',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username`) USING BTREE,
  UNIQUE INDEX `uk_email`(`email`) USING BTREE,
  UNIQUE INDEX `uk_phone`(`phone`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '账户信息表' ROW_FORMAT = Dynamic;

CREATE TABLE `player`  (
  `uid` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '玩家唯一ID',
  `account_id` int UNSIGNED NOT NULL COMMENT '关联账户ID',
  `nickname` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '玩家昵称',
  `level` smallint UNSIGNED NOT NULL DEFAULT 1 COMMENT '玩家等级',
  `exp` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前经验值',
  `world_level` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '世界等级',
  `stamina` smallint UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前体力值',
  `currency` json NULL COMMENT '货币数据',
  `scene_id` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前场景ID',
  `pos_x` float NOT NULL DEFAULT 0 COMMENT '位置X坐标',
  `pos_y` float NOT NULL DEFAULT 0 COMMENT '位置Y坐标',
  `pos_z` float NOT NULL DEFAULT 0 COMMENT '位置Z坐标',
  `rot_x` float NOT NULL DEFAULT 0 COMMENT '旋转X角度',
  `rot_y` float NOT NULL DEFAULT 0 COMMENT '旋转Y角度',
  `rot_z` float NOT NULL DEFAULT 0 COMMENT '旋转Z角度',
  `last_login` datetime NULL DEFAULT NULL COMMENT '上次登录时间',
  `last_logout` datetime NULL DEFAULT NULL COMMENT '上次登出时间',
  `data_version` bigint UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本，落盘条件更新',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`uid`) USING BTREE,
  UNIQUE INDEX `uk_account_id`(`account_id`) USING BTREE,
  INDEX `idx_nickname`(`nickname`) USING BTREE,
  INDEX `idx_level`(`level`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '玩家基本信息表' ROW_FORMAT = Dynamic;

CREATE TABLE `wallet_ledger` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '流水ID',
  `uid` int UNSIGNED NOT NULL COMMENT '玩家uid',
  `currency_id` int NOT NULL COMMENT '货币类型',
  `delta` int NOT NULL COMMENT '变更量（加正减负）',
  `reason` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '业务原因',
  `balance_before` int NOT NULL COMMENT '变更前余额',
  `balance_after` int NOT NULL COMMENT '变更后余额',
  `tx_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '事务/幂等键',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_wallet_ledger_uid`(`uid`) USING BTREE,
  INDEX `idx_wallet_ledger_created`(`created_at`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '钱包变更流水' ROW_FORMAT = Dynamic;

CREATE TABLE `avatar`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `avatar_id` int UNSIGNED NOT NULL COMMENT '角色ID',
  `level` smallint UNSIGNED NOT NULL DEFAULT 1 COMMENT '角色等级',
  `exp` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前经验值',
  `promotion` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '突破阶段',
  `rank` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '命座等级',
  `locked` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否锁定',
  `equipped_skin_id` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前穿戴皮肤ID；0表示默认皮肤',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_avatar`(`player_id`, `avatar_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色收集与进度表' ROW_FORMAT = Dynamic;

CREATE TABLE `lineup`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '编队ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT '编队名称',
  `is_active` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为当前激活编队',
  `avatars` json NOT NULL COMMENT '角色列表',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_is_active`(`is_active`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '队伍编成表' ROW_FORMAT = Dynamic;

CREATE TABLE `battle`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '战斗实例ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `lineup_id` int UNSIGNED NOT NULL COMMENT '阵容ID',
  `battle_stage_id` int UNSIGNED NOT NULL COMMENT '战斗阶段ID',
  `start_time` datetime NOT NULL COMMENT '战斗开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '战斗结束时间',
  `end_status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '结束状态：0-进行中 1-胜利 2-失败 3-退出',
  `statistics` json NULL COMMENT '战斗统计数据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_lineup_id`(`lineup_id`) USING BTREE,
  INDEX `idx_battle_stage_id`(`battle_stage_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '战斗实例表' ROW_FORMAT = Dynamic;

CREATE TABLE `battle_monster_wave`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '波次ID',
  `battle_stage_id` int UNSIGNED NOT NULL COMMENT '战斗阶段ID',
  `wave_order` tinyint UNSIGNED NOT NULL COMMENT '波次顺序',
  `monsters` json NOT NULL COMMENT '怪物ID列表',
  `custom_level` int NOT NULL DEFAULT 0 COMMENT '自定义等级调整',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_stage_order`(`battle_stage_id`, `wave_order`) USING BTREE,
  INDEX `idx_battle_stage_id`(`battle_stage_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '怪物波次配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `challenge`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `challenge_type` tinyint UNSIGNED NOT NULL COMMENT '挑战类型：1-主线，2-活动，3-周常，4-深渊等',
  `challenge_id` int UNSIGNED NOT NULL COMMENT '挑战配置ID',
  `progress` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前进度值',
  `max_progress` int UNSIGNED NOT NULL COMMENT '目标进度值',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0-进行中，1-已完成，2-已领取奖励',
  `start_time` datetime NOT NULL COMMENT '挑战开始时间',
  `complete_time` datetime NULL DEFAULT NULL COMMENT '完成时间',
  `reward_claimed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '奖励是否已领取',
  `extra_data` json NULL COMMENT '额外数据',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_challenge`(`player_id`, `challenge_type`, `challenge_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '挑战进度表' ROW_FORMAT = Dynamic;

CREATE TABLE `challenge_group_reward`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `group_id` int UNSIGNED NOT NULL COMMENT '挑战组ID',
  `taken_stars` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '已领取奖励的星级位掩码',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_group`(`player_id`, `group_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_group_id`(`group_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '挑战组奖励表' ROW_FORMAT = Dynamic;

CREATE TABLE `challenge_history`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `challenge_id` int UNSIGNED NOT NULL COMMENT '挑战配置ID',
  `group_id` int UNSIGNED NOT NULL COMMENT '挑战组ID',
  `stars` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '获得的星级',
  `score` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '挑战得分',
  `taken_reward` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '已领取的奖励',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_challenge`(`player_id`, `challenge_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_group_id`(`group_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '挑战历史表' ROW_FORMAT = Dynamic;

CREATE TABLE `friend`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '好友关系ID',
  `player_id_1` int UNSIGNED NOT NULL COMMENT '玩家ID1',
  `player_id_2` int UNSIGNED NOT NULL COMMENT '玩家ID2',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '关系状态：0-待确认，1-已确认，2-黑名单，3-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关系创建时间',
  `confirm_time` datetime NULL DEFAULT NULL COMMENT '确认时间',
  `source` tinyint UNSIGNED NULL DEFAULT NULL COMMENT '来源',
  `remark` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '备注信息',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_pair`(`player_id_1`, `player_id_2`) USING BTREE,
  INDEX `idx_player_id_1`(`player_id_1`) USING BTREE,
  INDEX `idx_player_id_2`(`player_id_2`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '好友关系表' ROW_FORMAT = Dynamic;

CREATE TABLE `mail`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '邮件ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '收件玩家ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标题',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '正文',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '0-未读 1-已读 2-已领取 3-已删除',
  `attachments_json` json NULL COMMENT '附件JSON',
  `send_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  `expire_time` datetime NULL DEFAULT NULL COMMENT '过期时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '玩家邮件表' ROW_FORMAT = Dynamic;

CREATE TABLE `quest_progress`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '进度ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `quest_id` int UNSIGNED NOT NULL COMMENT '任务配置ID',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '0-未接 1-进行中 2-可提交 3-已完成 4-已放弃',
  `objectives_json` json NULL COMMENT '目标进度JSON',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_quest`(`player_id`, `quest_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '任务进度表' ROW_FORMAT = Dynamic;

CREATE TABLE `avatar_talent`  (
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `avatar_id` int UNSIGNED NOT NULL COMMENT '角色ID',
  `talent_id` int UNSIGNED NOT NULL COMMENT '天赋ID',
  `level` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '天赋等级',
  `activated` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否激活',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`player_id`, `avatar_id`, `talent_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色通用天赋表' ROW_FORMAT = Dynamic;

CREATE TABLE `game_item`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '物品唯一ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '所属玩家ID',
  `item_id` int UNSIGNED NOT NULL COMMENT '物品配置ID',
  `type` tinyint UNSIGNED NOT NULL COMMENT '物品类型：1-装备，2-遗器，3-材料',
  `count` int UNSIGNED NOT NULL DEFAULT 1 COMMENT '数量',
  `level` smallint UNSIGNED NOT NULL DEFAULT 1 COMMENT '等级',
  `exp` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前经验值',
  `promotion` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '突破等级',
  `rank` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '阶位',
  `locked` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否锁定',
  `discarded` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已丢弃',
  `main_affix_id` int UNSIGNED NULL DEFAULT NULL COMMENT '主词条ID',
  `sub_affixes` json NULL COMMENT '副词条列表',
  `equip_avatar_id` int UNSIGNED NULL DEFAULT NULL COMMENT '装备的角色ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '获得时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_item_id`(`item_id`) USING BTREE,
  INDEX `idx_player_type`(`player_id`, `type`) USING BTREE,
  INDEX `idx_equip_avatar`(`equip_avatar_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '物品表' ROW_FORMAT = Dynamic;

CREATE TABLE `maze_buff`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Buff ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'Buff名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'Buff描述',
  `buff_type` tinyint UNSIGNED NOT NULL COMMENT 'Buff类型：1-增益，2-减益，3-特殊',
  `duration` int NOT NULL DEFAULT 0 COMMENT '持续时间',
  `max_stack` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '最大叠加层数',
  `can_dispel` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可被驱散',
  `effects` json NOT NULL COMMENT '效果列表',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_buff_type`(`buff_type`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Buff配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `maze_skill`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '技能ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '技能名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT '技能描述',
  `skill_type` tinyint UNSIGNED NOT NULL COMMENT '技能类型',
  `trigger_battle` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否触发战斗',
  `adventure_modifier` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否为冒险修饰符',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_skill_type`(`skill_type`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '技能配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `maze_skill_action`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '动作ID',
  `skill_id` int UNSIGNED NOT NULL COMMENT '所属技能ID',
  `action_type` tinyint UNSIGNED NOT NULL COMMENT '动作类型：1-修改生命值，2-添加Buff，3-击中道具，4-召唤单位，5-设置攻击目标怪物死亡',
  `action_category` tinyint UNSIGNED NOT NULL COMMENT '动作类别：1-施放动作，2-攻击动作',
  `action_order` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '执行顺序',
  `params` json NULL COMMENT '动作参数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_skill_id`(`skill_id`) USING BTREE,
  INDEX `idx_action_category`(`action_category`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '技能动作表' ROW_FORMAT = Dynamic;

CREATE TABLE `monster_config`  (
  `id` int UNSIGNED NOT NULL COMMENT '怪物ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '怪物名称',
  `level` smallint UNSIGNED NOT NULL DEFAULT 1 COMMENT '默认等级',
  `hp` int UNSIGNED NOT NULL COMMENT '生命值',
  `attack` int UNSIGNED NOT NULL COMMENT '攻击力',
  `defense` int UNSIGNED NOT NULL COMMENT '防御力',
  `speed` smallint UNSIGNED NOT NULL COMMENT '速度',
  `model_id` int UNSIGNED NOT NULL COMMENT '模型资源ID',
  `buffs` json NULL COMMENT '默认携带的Buff列表',
  `farm_element_id` int UNSIGNED NULL DEFAULT NULL COMMENT '农场元素ID',
  `custom_stage_id` int UNSIGNED NULL DEFAULT NULL COMMENT '自定义战斗阶段ID',
  `drop_group_id` int UNSIGNED NULL DEFAULT NULL COMMENT '掉落组ID',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '怪物配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `npc_config`  (
  `id` int UNSIGNED NOT NULL COMMENT 'NPC ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'NPC名称',
  `model_id` int UNSIGNED NOT NULL COMMENT '模型资源ID',
  `dialogue_id` int UNSIGNED NULL DEFAULT NULL COMMENT '对话树ID',
  `rogue_event_id` int UNSIGNED NULL DEFAULT NULL COMMENT '关联的Rogue事件ID',
  `is_interactable` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可交互',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'NPC配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `player_gacha_banner_info`  (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `banner_type` tinyint UNSIGNED NOT NULL COMMENT '卡池类型：1-新手，2-常驻，11-角色UP，12-武器UP',
  `pity_5` smallint UNSIGNED NOT NULL DEFAULT 0 COMMENT '5星保底计数',
  `pity_4` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '4星保底计数',
  `failed_up_count` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT 'UP失败次数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_banner`(`player_id`, `banner_type`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_banner_type`(`banner_type`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '玩家卡池保底信息表' ROW_FORMAT = Dynamic;

CREATE TABLE `player_gacha_info`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `ceiling_num` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '常驻卡池累计抽数',
  `ceiling_claimed` tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否已领取300抽上限奖励',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_id`(`player_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '玩家抽卡信息表' ROW_FORMAT = Dynamic;

CREATE TABLE `rogue`  (
  `id` int UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录ID',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `rogue_id` int UNSIGNED NOT NULL COMMENT 'Roguelike活动ID',
  `current_floor` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前层数',
  `current_wave` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前波次',
  `difficulty` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '难度等级',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '状态：0-进行中，1-已完成，2-失败',
  `score` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前得分/积分',
  `rewards_claimed` json NULL COMMENT '已领取奖励的标记',
  `progress_data` json NULL COMMENT '其他进度数据',
  `start_time` datetime NOT NULL COMMENT '本次Roguelike开始时间',
  `end_time` datetime NULL DEFAULT NULL COMMENT '本次Roguelike结束时间',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_player_rogue`(`player_id`, `rogue_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_status`(`status`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Roguelike模式进度表' ROW_FORMAT = Dynamic;

CREATE TABLE `rogue_player_data`  (
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `talents` json NULL COMMENT '天赋数据',
  `unlocked_miracles` json NULL COMMENT '已解锁的奇物ID列表',
  `selected_path` tinyint UNSIGNED NULL DEFAULT NULL COMMENT '当前选择的命途ID',
  `completed_runs` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '完成的模拟宇宙总次数',
  `highest_floor` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '历史最高层数',
  `total_score` bigint UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计获得的总积分',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`player_id`) USING BTREE,
  INDEX `idx_selected_path`(`selected_path`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '模拟宇宙玩家数据表' ROW_FORMAT = Dynamic;

CREATE TABLE `rogue_talent`  (
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `talent_id` int UNSIGNED NOT NULL COMMENT '天赋配置ID',
  `level` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前天赋等级',
  `activated` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否已激活',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '解锁时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`player_id`, `talent_id`) USING BTREE,
  INDEX `idx_player_id`(`player_id`) USING BTREE,
  INDEX `idx_talent_id`(`talent_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '玩家Roguelike天赋表' ROW_FORMAT = Dynamic;

CREATE TABLE `scene_config`  (
  `plane_id` int UNSIGNED NOT NULL COMMENT '平面ID',
  `floor_id` int UNSIGNED NOT NULL COMMENT '楼层ID',
  `world_context` tinyint UNSIGNED NOT NULL COMMENT '世界上下文',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '地图名称',
  `map_resource` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '地图资源路径',
  `load_side` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '加载端：1-服务器，2-客户端',
  `groups` json NULL COMMENT '组信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`plane_id`, `floor_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '地图配置表' ROW_FORMAT = Dynamic;

CREATE TABLE `summon_unit_config`  (
  `id` int UNSIGNED NOT NULL COMMENT '召唤物ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '召唤物名称',
  `model_id` int UNSIGNED NOT NULL COMMENT '模型资源ID',
  `duration` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '默认持续时间',
  `skill_action_id` int UNSIGNED NULL DEFAULT NULL COMMENT '关联的技能动作ID',
  `hp` int UNSIGNED NULL DEFAULT NULL COMMENT '生命值',
  `attack` int UNSIGNED NULL DEFAULT NULL COMMENT '攻击力',
  `can_be_targeted` tinyint(1) NOT NULL DEFAULT 1 COMMENT '是否可被选为目标',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '召唤物配置表' ROW_FORMAT = Dynamic;

/* ========== 来自 schema.sql 的额外表 ========== */

CREATE TABLE `rogue_room` (
  `room_id` INT UNSIGNED NOT NULL COMMENT '房间配置ID',
  `room_type` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '房间类型',
  `pos_x` INT NOT NULL DEFAULT 0 COMMENT '地图坐标 X',
  `pos_y` INT NOT NULL DEFAULT 0 COMMENT '地图坐标 Y',
  PRIMARY KEY (`room_id`),
  KEY `idx_room_type` (`room_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟宇宙房间配置（L2，供 RogueRoomData 延迟加载）';

CREATE TABLE `game_data` (
  `data_key` VARCHAR(128) NOT NULL COMMENT '资源/配置键',
  `payload_json` JSON NOT NULL COMMENT 'JSON 负载',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`data_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='游戏资源与通用配置快照（L2）';

CREATE TABLE `admin_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '后台用户主键',
  `username` VARCHAR(64) NOT NULL COMMENT '登录名',
  `password_hash` VARCHAR(255) NOT NULL COMMENT '密码（支持 Spring DelegatingPasswordEncoder）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用 0 禁用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台用户表';

CREATE TABLE `admin_role` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '角色主键',
  `role_code` VARCHAR(64) NOT NULL COMMENT '角色编码',
  `role_name` VARCHAR(128) NOT NULL COMMENT '角色名称',
  `description` VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台角色表';

CREATE TABLE `admin_permission` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '权限主键',
  `perm_code` VARCHAR(128) NOT NULL COMMENT '权限标识',
  `perm_name` VARCHAR(128) NOT NULL COMMENT '权限名称',
  `description` VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_perm_code` (`perm_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后台权限表';

CREATE TABLE `admin_user_role` (
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'admin_user.id',
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT 'admin_role.id',
  PRIMARY KEY (`user_id`, `role_id`),
  KEY `idx_aur_role` (`role_id`),
  CONSTRAINT `fk_aur_user` FOREIGN KEY (`user_id`) REFERENCES `admin_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_aur_role` FOREIGN KEY (`role_id`) REFERENCES `admin_role` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联';

CREATE TABLE `admin_role_permission` (
  `role_id` BIGINT UNSIGNED NOT NULL COMMENT 'admin_role.id',
  `permission_id` BIGINT UNSIGNED NOT NULL COMMENT 'admin_permission.id',
  PRIMARY KEY (`role_id`, `permission_id`),
  KEY `idx_arp_perm` (`permission_id`),
  CONSTRAINT `fk_arp_role` FOREIGN KEY (`role_id`) REFERENCES `admin_role` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_arp_perm` FOREIGN KEY (`permission_id`) REFERENCES `admin_permission` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联';

CREATE TABLE `player_settings` (
  `player_id` INT UNSIGNED NOT NULL COMMENT '玩家 uid',
  `display_json` JSON NOT NULL COMMENT '画面设置',
  `sound_json` JSON NOT NULL COMMENT '声音设置',
  `keybinds_json` JSON NOT NULL COMMENT '按键绑定列表',
  `gameplay_json` JSON NOT NULL COMMENT '游戏细节设置',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`player_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家游戏设置（画面/按键/声音/细节）';

CREATE TABLE `support_ticket` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '工单ID',
  `player_id` INT UNSIGNED NOT NULL COMMENT '玩家 uid',
  `category` VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT '分类：display/sound/keybind/gameplay/other',
  `subject` VARCHAR(128) NOT NULL COMMENT '标题',
  `content` VARCHAR(2000) NOT NULL COMMENT '正文',
  `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2已回复 3已关闭',
  `admin_reply` VARCHAR(2000) NULL DEFAULT NULL COMMENT '客服回复',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_support_player` (`player_id`),
  KEY `idx_support_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='玩家客服工单';

/* ========================= 关联测试数据（account.id 与 player.account_id 数值对齐） ========================= */

INSERT INTO `account` (`id`, `username`, `password`, `email`, `phone`, `status`, `last_login_at`, `last_login_ip`) VALUES
('1', 'tester01', '{bcrypt}$2a$10$dLZJFYbuXBvZcBv.M7BJe.nCttuZp/5cs0c2KjL0XpFYd6OHbXsL6', 't01@lunar.test', '13800000001', 1, '2026-03-28 10:00:00', '127.0.0.1'),
('2', 'tester02', '{bcrypt}$2a$10$n1EuIT7zYwJRIJUAVpqOlORYz0AAEfSZy84wCYEXzX7hNxiUV1cru', 't02@lunar.test', '13800000002', 1, '2026-03-28 10:01:00', '127.0.0.1'),
('3', 'tester03', '{bcrypt}$2a$10$4hcA6FmWuIWqG8t5VO3aQe9CERvnaoCleYPK01nM6DL6gxgRww7g2', 't03@lunar.test', '13800000003', 1, '2026-03-28 10:02:00', '127.0.0.1'),
('4', 'tester04', '{bcrypt}$2a$10$ggI8U/.o7IKG3TQB8MIeguc0FHGCAJ0C8I3Zhazamy9qoROFdzFUO', 't04@lunar.test', '13800000004', 1, '2026-03-28 10:03:00', '127.0.0.1'),
('5', 'tester05', '{bcrypt}$2a$10$eS/jME3qGrlsrFpMO33/AuqA.7rDhdax0/w5xibIobEwz0nmSEwHy', 't05@lunar.test', '13800000005', 1, '2026-03-28 10:04:00', '127.0.0.1');

INSERT INTO `player` (`uid`, `account_id`, `nickname`, `level`, `exp`, `world_level`, `stamina`, `currency`, `scene_id`, `pos_x`, `pos_y`, `pos_z`, `rot_x`, `rot_y`, `rot_z`, `last_login`, `last_logout`) VALUES
(1, 1, '旅人一号', 40, 120000, 6, 180, JSON_OBJECT('2', 50000, '3', 120), 10001, 10.5, 0.0, 20.0, 0, 0, 0, '2026-03-28 09:00:00', '2026-03-27 22:00:00'),
(2, 2, '旅人二号', 35, 80000, 5, 160, JSON_OBJECT('2', 30000), 10001, 11.0, 0.0, 21.0, 0, 0, 0, '2026-03-28 09:10:00', NULL),
(3, 3, '旅人三号', 50, 200000, 7, 200, JSON_OBJECT('2', 80000), 10002, 5.0, 1.0, 5.0, 0, 0, 0, '2026-03-28 09:20:00', NULL),
(4, 4, '旅人四号', 28, 40000, 4, 140, JSON_OBJECT('2', 10000), 10002, 0.0, 0.0, 0.0, 0, 0, 0, '2026-03-28 09:30:00', NULL),
(5, 5, '旅人五号', 60, 300000, 8, 240, JSON_OBJECT('2', 200000), 10001, 100.0, 0.0, 50.0, 0, 0, 0, '2026-03-28 09:40:00', NULL);

INSERT INTO `avatar` (`player_id`, `avatar_id`, `level`, `exp`, `promotion`, `rank`, `locked`) VALUES
(1, 1001, 70, 0, 6, 0, 0),
(1, 1002, 65, 0, 5, 1, 0),
(2, 1001, 60, 0, 5, 0, 0),
(3, 1003, 80, 0, 6, 2, 0),
(4, 1001, 50, 0, 4, 0, 0);

INSERT INTO `lineup` (`player_id`, `name`, `is_active`, `avatars`) VALUES
(1, '主队', 1, JSON_ARRAY(1001, 1002, 0, 0)),
(2, '探索', 1, JSON_ARRAY(1001, 0, 0, 0)),
(3, '深渊', 1, JSON_ARRAY(1003, 1001, 0, 0)),
(4, '默认', 1, JSON_ARRAY(1001, 0, 0, 0)),
(5, '满编', 1, JSON_ARRAY(1001, 1002, 1003, 0));

INSERT INTO `battle` (`player_id`, `lineup_id`, `battle_stage_id`, `start_time`, `end_time`, `end_status`, `statistics`) VALUES
(1, 1, 90001, '2026-03-28 08:00:00', '2026-03-28 08:05:00', 1, JSON_OBJECT('turns', 8, 'mvp', 1001)),
(1, 1, 90002, '2026-03-28 08:10:00', NULL, 0, JSON_OBJECT('turns', 3)),
(2, 2, 90001, '2026-03-27 20:00:00', '2026-03-27 20:04:00', 1, JSON_OBJECT('turns', 6)),
(3, 3, 90003, '2026-03-26 12:00:00', '2026-03-26 12:10:00', 2, JSON_OBJECT('reason', 'timeout')),
(5, 5, 90001, '2026-03-28 07:00:00', '2026-03-28 07:06:00', 1, JSON_OBJECT('turns', 10));

INSERT INTO `battle_monster_wave` (`battle_stage_id`, `wave_order`, `monsters`, `custom_level`) VALUES
(90001, 1, JSON_ARRAY(JSON_OBJECT('monsterId', 2001, 'count', 2)), 0),
(90001, 2, JSON_ARRAY(JSON_OBJECT('monsterId', 2002, 'count', 1)), 5),
(90001, 3, JSON_ARRAY(JSON_OBJECT('monsterId', 2003, 'count', 1)), 10),
(90002, 1, JSON_ARRAY(JSON_OBJECT('monsterId', 2004, 'count', 3)), 0),
(90003, 1, JSON_ARRAY(JSON_OBJECT('monsterId', 2005, 'count', 1)), 15);

INSERT INTO `challenge` (`player_id`, `challenge_type`, `challenge_id`, `progress`, `max_progress`, `status`, `start_time`, `complete_time`, `reward_claimed`, `extra_data`) VALUES
(1, 1, 101, 3, 3, 1, '2026-03-01 00:00:00', '2026-03-10 00:00:00', 1, JSON_OBJECT('group', 1)),
(1, 2, 201, 5, 10, 0, '2026-03-20 00:00:00', NULL, 0, NULL),
(2, 1, 102, 1, 3, 0, '2026-03-25 00:00:00', NULL, 0, NULL),
(3, 3, 301, 10, 10, 1, '2026-03-15 00:00:00', '2026-03-16 00:00:00', 1, NULL),
(5, 1, 105, 0, 5, 0, '2026-03-28 00:00:00', NULL, 0, NULL);

INSERT INTO `challenge_group_reward` (`player_id`, `group_id`, `taken_stars`) VALUES
(1, 1, 7),
(1, 2, 0),
(2, 1, 3),
(3, 1, 15),
(5, 1, 0);

INSERT INTO `challenge_history` (`player_id`, `challenge_id`, `group_id`, `stars`, `score`, `taken_reward`) VALUES
(1, 5001, 1, 3, 9500, 7),
(1, 5002, 1, 2, 8200, 3),
(2, 5001, 1, 1, 6000, 1),
(3, 5003, 2, 3, 9900, 7),
(5, 5001, 1, 0, 4000, 0);

INSERT INTO `friend` (`player_id_1`, `player_id_2`, `status`, `confirm_time`, `source`, `remark`) VALUES
(1, 2, 1, '2026-03-01 12:00:00', 1, '组队加的好友'),
(1, 3, 1, '2026-03-02 12:00:00', 1, NULL),
(1, 4, 1, '2026-03-03 12:00:00', 2, NULL),
(2, 3, 1, '2026-03-04 12:00:00', 1, NULL),
(2, 5, 0, NULL, 1, '待确认');

INSERT INTO `game_item` (`player_id`, `item_id`, `type`, `count`, `level`, `exp`, `promotion`, `rank`, `locked`, `discarded`, `main_affix_id`, `sub_affixes`, `equip_avatar_id`) VALUES
(1, 50001, 3, 99, 1, 0, 0, 1, 0, 0, NULL, NULL, NULL),
(1, 60001, 1, 1, 15, 0, 6, 5, 1, 0, 9001, JSON_ARRAY(JSON_OBJECT('id', 1, 'val', 3.2)), 1001),
(2, 50001, 3, 50, 1, 0, 0, 1, 0, 0, NULL, NULL, NULL),
(3, 60002, 2, 1, 12, 0, 4, 3, 0, 0, 9100, JSON_ARRAY(), 1003),
(5, 50002, 3, 5, 1, 0, 0, 1, 0, 0, NULL, NULL, NULL);

INSERT INTO `maze_buff` (`name`, `description`, `buff_type`, `duration`, `max_stack`, `can_dispel`, `effects`) VALUES
('攻击提升', '全队攻击+10%', 1, 3, 5, 1, JSON_ARRAY(JSON_OBJECT('stat', 'atk', 'pct', 10))),
('防御降低', '单体防御-15%', 2, 2, 3, 1, JSON_ARRAY(JSON_OBJECT('stat', 'def', 'pct', -15))),
('持续治疗', '每回合回复生命', 1, 5, 1, 0, JSON_ARRAY(JSON_OBJECT('hot', 500))),
('易伤', '受到伤害+20%', 2, 4, 1, 1, JSON_ARRAY(JSON_OBJECT('dmg_taken', 20))),
('能量回复', '行动后回能', 3, 1, 1, 1, JSON_ARRAY(JSON_OBJECT('sp', 10)));

INSERT INTO `maze_skill` (`name`, `description`, `skill_type`, `trigger_battle`, `adventure_modifier`) VALUES
('烈焰斩', '对单体造成火伤', 1, 1, 0),
('群体治疗', '回复全队生命', 2, 0, 0),
('召唤图腾', '召唤支援单位', 3, 1, 1),
('破盾冲击', '削减韧性', 1, 1, 0),
('秘技·充能', '不进入战斗的回能', 4, 0, 1);

INSERT INTO `maze_skill_action` (`skill_id`, `action_type`, `action_category`, `action_order`, `params`) VALUES
(1, 5, 2, 1, JSON_OBJECT('ratio', 1.2)),
(2, 1, 1, 1, JSON_OBJECT('heal', 800)),
(3, 4, 1, 1, JSON_OBJECT('summonId', 4001)),
(4, 2, 2, 1, JSON_OBJECT('buffId', 2)),
(5, 1, 1, 1, JSON_OBJECT('sp', 30));

INSERT INTO `monster_config` (`id`, `name`, `level`, `hp`, `attack`, `defense`, `speed`, `model_id`, `buffs`, `custom_stage_id`, `drop_group_id`) VALUES
(2001, '虚卒·掠夺者', 40, 80000, 1200, 400, 100, 50001, JSON_ARRAY(1), 90001, 70001),
(2002, '虚卒·践踏者', 42, 120000, 1500, 500, 90, 50002, JSON_ARRAY(2), 90001, 70001),
(2003, '外宇宙之冰', 45, 200000, 2000, 600, 85, 50003, JSON_ARRAY(1, 2), 90001, 70002),
(2004, '小蜘蛛群', 38, 30000, 800, 200, 110, 50004, NULL, 90002, 70003),
(2005, 'BOSS·末日兽', 50, 500000, 3000, 800, 70, 50005, JSON_ARRAY(3, 4, 5), 90003, 70099);

INSERT INTO `npc_config` (`id`, `name`, `model_id`, `dialogue_id`, `rogue_event_id`, `is_interactable`) VALUES
(3001, '黑塔人偶', 80001, 101, 5001, 1),
(3002, '阮·梅', 80002, 102, 5002, 1),
(3003, '螺丝咕姆', 80003, 103, 5003, 1),
(3004, '斯蒂芬', 80004, 104, 5004, 1),
(3005, '信使', 80005, 105, 5005, 1);

INSERT INTO `player_gacha_banner_info` (`player_id`, `banner_type`, `pity_5`, `pity_4`, `failed_up_count`) VALUES
(1, 1, 20, 5, 0),
(1, 2, 35, 7, 1),
(1, 11, 50, 8, 2),
(2, 2, 10, 3, 0),
(5, 12, 60, 9, 3);

INSERT INTO `player_gacha_info` (`player_id`, `ceiling_num`, `ceiling_claimed`) VALUES
(1, 120, 0),
(2, 45, 0),
(3, 300, 1),
(4, 10, 0),
(5, 280, 0);

INSERT INTO `rogue` (`player_id`, `rogue_id`, `current_floor`, `current_wave`, `difficulty`, `status`, `score`, `rewards_claimed`, `progress_data`, `start_time`, `end_time`) VALUES
(1, 1, 3, 2, 2, 0, 1200, JSON_ARRAY(1), JSON_OBJECT('seed', 12345), '2026-03-28 06:00:00', NULL),
(2, 1, 1, 1, 1, 0, 0, JSON_ARRAY(), JSON_OBJECT(), '2026-03-28 07:00:00', NULL),
(3, 1, 6, 3, 3, 1, 5600, JSON_ARRAY(1, 2, 3), JSON_OBJECT('cleared', true), '2026-03-25 10:00:00', '2026-03-25 11:30:00'),
(4, 1, 2, 1, 1, 2, 200, JSON_ARRAY(), JSON_OBJECT('fail', 'dead'), '2026-03-20 09:00:00', '2026-03-20 09:20:00'),
(5, 1, 4, 1, 4, 0, 3400, JSON_ARRAY(1), JSON_OBJECT(), '2026-03-28 05:00:00', NULL);

INSERT INTO `rogue_player_data` (`player_id`, `talents`, `unlocked_miracles`, `selected_path`, `completed_runs`, `highest_floor`, `total_score`) VALUES
(1, JSON_OBJECT('1', 3, '2', 2), JSON_ARRAY(9001, 9002), 1, 12, 6, 450000),
(2, JSON_OBJECT('1', 1), JSON_ARRAY(9001), 2, 3, 3, 80000),
(3, JSON_OBJECT('1', 5, '3', 4), JSON_ARRAY(9001, 9002, 9003), 3, 40, 8, 1200000),
(4, JSON_OBJECT(), JSON_ARRAY(), NULL, 0, 0, 0),
(5, JSON_OBJECT('2', 2), JSON_ARRAY(9001), 1, 25, 7, 900000);

INSERT INTO `rogue_talent` (`player_id`, `talent_id`, `level`, `activated`) VALUES
(1, 1, 3, 1),
(1, 2, 2, 1),
(2, 1, 1, 1),
(3, 1, 5, 1),
(5, 2, 2, 1);

INSERT INTO `scene_config` (`plane_id`, `floor_id`, `world_context`, `name`, `map_resource`, `load_side`, `groups`) VALUES
(10, 1, 1, '主控舱段', 'maps/bridge_01', 1, JSON_ARRAY(JSON_OBJECT('npc', 3001))),
(10, 2, 1, '接待中心', 'maps/lobby_01', 1, JSON_ARRAY(JSON_OBJECT('npc', 3002))),
(20, 1, 2, '大矿区', 'maps/mine_01', 1, JSON_ARRAY(JSON_OBJECT('monster', 2001))),
(20, 2, 2, '矿道深处', 'maps/mine_02', 1, JSON_ARRAY(JSON_OBJECT('monster', 2002))),
(99, 1, 9, '测试平面', 'maps/test', 2, JSON_ARRAY(JSON_OBJECT('prop', 1)));

INSERT INTO `summon_unit_config` (`id`, `name`, `model_id`, `duration`, `skill_action_id`, `hp`, `attack`, `can_be_targeted`) VALUES
(4001, '治疗图腾', 90001, 3, 2, 5000, 0, 1),
(4002, '炮塔', 90002, 5, 1, 8000, 1200, 1),
(4003, '护盾无人机', 90003, 4, 4, 3000, 0, 1),
(4004, '诱饵', 90004, 2, 1, 1, 0, 0),
(4005, '支援信标', 90005, 6, 3, 2000, 500, 1);

INSERT INTO `rogue_room` (`room_id`, `room_type`, `pos_x`, `pos_y`) VALUES
(5001, 1, 0, 0),
(5002, 2, 1, 0),
(5003, 3, 2, 0),
(5004, 4, 0, 1),
(5005, 5, 1, 1);

INSERT INTO `game_data` (`data_key`, `payload_json`) VALUES
('meta', JSON_OBJECT('version', '0.0.1', 'shard', 1)),
('gacha.banner.revision', JSON_OBJECT('rev', 7, 'file', 'Banners.json')),
('server.maintenance', JSON_OBJECT('enabled', false, 'msg', '')),
('feature.flags', JSON_ARRAY('rogue_v2', 'challenge_star')),
('balance.patch', JSON_OBJECT('crit', 1.5, 'heal', 1.0));

INSERT INTO `admin_user` (`id`, `username`, `password_hash`, `status`) VALUES
(1, 'admin', '{bcrypt}$2a$10$Xw7zafBJtoSPT69fXriEEug.pNUXGqmNFBf7WONUnlwD9ETPbFOny', 1),
(2, 'ops01', '{bcrypt}$2a$10$bu9d60nQWdOXveygaSj.buRdnWD0Iqe2/2/mpY0JnvuM5Xyzm4..y', 1),
(3, 'support01', '{bcrypt}$2a$10$FPnaI3GAVOZUPG4jhbTr7ORBl54ngxxSgeZaophnBl76D533leZBi', 1),
(4, 'auditor01', '{bcrypt}$2a$10$XnSVz6P6ijzFdBBBviv3eOIBOepgXRNtMQKr4IHlNv4F/o8DbKcTC', 1),
(5, 'viewer01', '{bcrypt}$2a$10$WAM.MVMmWOJSdlI/mI8LVen4d0VpH08/FZqujZl4H.zIaLZrpras.', 1);

INSERT INTO `admin_role` (`id`, `role_code`, `role_name`, `description`) VALUES
(1, 'SUPER_ADMIN', '超级管理员', '全权限'),
(2, 'SUPPORT', '客服', '处理玩家问题'),
(3, 'OPERATOR', '运营', '活动与公告'),
(4, 'AUDITOR', '审计', '只读审计'),
(5, 'VIEWER', '访客', '最小只读');

INSERT INTO `admin_permission` (`id`, `perm_code`, `perm_name`, `description`) VALUES
(1, 'admin:complaint:handle', '处理玩家投诉', NULL),
(2, 'admin:item:manage', '管理游戏内物品', NULL),
(3, 'admin:game:read', '查看游戏配置', NULL),
(4, 'admin:ops:write', '运营写权限', NULL),
(5, 'admin:audit:read', '审计只读', NULL);

INSERT INTO `admin_user_role` (`user_id`, `role_id`) VALUES
(1, 1),
(2, 3),
(3, 2),
(4, 4),
(5, 5);

INSERT INTO `admin_role_permission` (`role_id`, `permission_id`) VALUES
(1, 1), (1, 2), (1, 3), (1, 4), (1, 5),
(2, 1), (2, 3),
(3, 3), (3, 4),
(4, 5),
(5, 3);

/* ========== IAP 充值订单 / 发货 / 限购 / 权益（P0 内存实现，表结构预留落库） ========== */
CREATE TABLE IF NOT EXISTS `iap_order` (
  `order_id` varchar(64) NOT NULL COMMENT '订单号',
  `player_id` int UNSIGNED NOT NULL COMMENT '玩家ID',
  `shop_id` int UNSIGNED NOT NULL COMMENT '商店ID',
  `shop_item_id` int UNSIGNED NOT NULL COMMENT '商品槽位ID',
  `sku_id` varchar(128) NOT NULL COMMENT '渠道SKU',
  `amount_cents` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '金额(分)',
  `status` varchar(32) NOT NULL COMMENT 'CREATED/PAYING/PAID/GRANTED/...',
  `channel` varchar(64) NULL DEFAULT NULL COMMENT '支付渠道',
  `channel_tx_id` varchar(128) NULL DEFAULT NULL COMMENT '渠道交易号',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `paid_at` datetime NULL DEFAULT NULL,
  `granted_at` datetime NULL DEFAULT NULL,
  PRIMARY KEY (`order_id`),
  INDEX `idx_iap_order_player`(`player_id`),
  INDEX `idx_iap_order_status`(`status`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IAP充值订单';

CREATE TABLE IF NOT EXISTS `iap_grant_log` (
  `order_id` varchar(64) NOT NULL COMMENT '订单号（幂等键）',
  `rewards_json` text NOT NULL COMMENT '发货内容JSON',
  `granted_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`order_id`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IAP发货流水';

CREATE TABLE IF NOT EXISTS `iap_purchase_limit` (
  `player_id` int UNSIGNED NOT NULL,
  `shop_item_id` int UNSIGNED NOT NULL,
  `period_key` varchar(64) NOT NULL COMMENT 'DAY/WEEK/MONTH/LIFETIME键',
  `count` int UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`player_id`, `shop_item_id`, `period_key`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IAP限购计数';

CREATE TABLE IF NOT EXISTS `iap_entitlement` (
  `player_id` int UNSIGNED NOT NULL,
  `pack_kind` varchar(64) NOT NULL COMMENT 'MONTHLY_CARD/STARTER/...',
  `expire_at` datetime NOT NULL,
  `claim_bitmap` varbinary(64) NULL DEFAULT NULL COMMENT '日领位图',
  `daily_currency_id` int NULL DEFAULT NULL,
  `daily_amount` int NULL DEFAULT NULL,
  `total_days` int NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`player_id`, `pack_kind`)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'IAP月卡/基金权益';

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE IF NOT EXISTS `gacha_draw_history` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT,
  `uid` int UNSIGNED NOT NULL,
  `banner_type` int NOT NULL,
  `item_id` int NOT NULL,
  `count` int NOT NULL DEFAULT 1,
  `is_new` tinyint(1) NOT NULL DEFAULT 0,
  `cost_currency_id` int NOT NULL DEFAULT 0,
  `cost_amount` int NOT NULL DEFAULT 0,
  `tx_id` varchar(64) NULL DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  INDEX `idx_gacha_hist_uid_created`(`uid`, `created_at`),
  INDEX `idx_gacha_hist_uid_banner`(`uid`, `banner_type`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='�鿨��ʷ�����';
