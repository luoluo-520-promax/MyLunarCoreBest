// 游戏网络命令号（cmd id）常量定义所在包
package cn.itcast.demo.mylunarcore.net;

/**
 * 客户端与服务端约定的命令号（cmd id）常量：与 Protobuf 分包及 {@link cn.itcast.demo.mylunarcore.net.PacketCommandRegistry} 注册表一致，修改需同步客户端。
 */
public final class CmdIds {

    /** 工具类禁止 new */
    private CmdIds() {}

    // ---------- 玩家与会话 ----------
    public static final int PLAYER_LOGIN_CS_REQ = 68; // 客户端：登录请求
    public static final int PLAYER_LOGIN_SC_RSP = 6; // 服务端：登录响应

    public static final int PLAYER_HEART_BEAT_CS_REQ = 69; // 客户端：心跳请求
    public static final int PLAYER_HEART_BEAT_SC_RSP = 70; // 服务端：心跳响应

    public static final int PLAYER_LOGOUT_CS_REQ = 71; // 客户端：登出请求
    public static final int PLAYER_LOGOUT_SC_RSP = 72; // 服务端：登出响应

    public static final int PLAYER_SYNC_SC_NOTIFY = 73; // 服务端：玩家数据同步推送

    public static final int GET_SESSION_INFO_CS_REQ = 74; // 客户端：查询会话信息
    public static final int GET_SESSION_INFO_SC_RSP = 75; // 服务端：会话信息响应

    // ---------- 战斗 ----------
    public static final int FIGHT_START_CS_REQ = 200; // 客户端：开战
    public static final int FIGHT_START_SC_RSP = 201; // 服务端：开战结果

    public static final int FIGHT_ACTION_CS_REQ = 202; // 客户端：战斗内操作
    public static final int FIGHT_ACTION_SC_RSP = 203; // 服务端：操作结果

    public static final int FIGHT_RESULT_CS_REQ = 204; // 客户端：上报战斗结果
    public static final int FIGHT_RESULT_SC_RSP = 205; // 服务端：结算响应

    public static final int FIGHT_QUIT_CS_REQ = 206; // 客户端：退出战斗
    public static final int FIGHT_QUIT_SC_RSP = 207; // 服务端：退出响应

    public static final int FIGHT_STATE_SC_NOTIFY = 208; // 服务端：战斗状态推送

    public static final int GET_BATTLE_INFO_CS_REQ = 209; // 客户端：查询当前战斗信息
    public static final int GET_BATTLE_INFO_SC_RSP = 210; // 服务端：战斗信息响应

    // 场景与实体系统
    public static final int ENTER_SCENE_CS_REQ = 300; // 客户端：请求进入场景
    public static final int ENTER_SCENE_SC_RSP = 301; // 服务端：进入场景结果

    public static final int GET_CUR_SCENE_INFO_CS_REQ = 302; // 客户端：查询当前场景信息
    public static final int GET_CUR_SCENE_INFO_SC_RSP = 303; // 服务端：当前场景信息

    public static final int INTERACT_NPC_CS_REQ = 310; // 客户端：与 NPC 交互
    public static final int INTERACT_NPC_SC_RSP = 311; // 服务端：NPC 交互结果

    public static final int PICKUP_PROP_CS_REQ = 312; // 客户端：拾取场景道具
    public static final int PICKUP_PROP_SC_RSP = 313; // 服务端：拾取结果

    public static final int TRIGGER_SCENE_EVENT_CS_REQ = 314; // 客户端：触发场景事件
    public static final int TRIGGER_SCENE_EVENT_SC_RSP = 315; // 服务端：事件触发结果

    public static final int SCENE_ENTITY_SYNC_SC_NOTIFY = 320; // 服务端：场景实体同步推送

    public static final int SUMMON_UNIT_CREATE_SC_NOTIFY = 330; // 服务端：召唤物创建推送
    public static final int SUMMON_UNIT_REMOVE_SC_NOTIFY = 331; // 服务端：召唤物移除推送

    public static final int USE_HEALING_SPRING_CS_REQ = 340; // 客户端：使用治疗泉
    public static final int USE_HEALING_SPRING_SC_RSP = 341; // 服务端：治疗泉使用结果

    // 物品/背包系统
    public static final int GET_BAG_CS_REQ = 400; // 客户端：查询背包
    public static final int GET_BAG_SC_RSP = 401; // 服务端：背包快照

    public static final int USE_ITEM_CS_REQ = 410; // 客户端：使用道具
    public static final int USE_ITEM_SC_RSP = 411; // 服务端：使用道具结果

    public static final int EQUIP_ITEM_CS_REQ = 420; // 客户端：装备道具
    public static final int EQUIP_ITEM_SC_RSP = 421; // 服务端：装备结果

    public static final int UNEQUIP_ITEM_CS_REQ = 422; // 客户端：卸下装备
    public static final int UNEQUIP_ITEM_SC_RSP = 423; // 服务端：卸下结果

    public static final int ENHANCE_ITEM_CS_REQ = 430; // 客户端：强化道具
    public static final int ENHANCE_ITEM_SC_RSP = 431; // 服务端：强化结果

    public static final int PROMOTE_ITEM_CS_REQ = 432; // 客户端：晋升道具（养成）
    public static final int PROMOTE_ITEM_SC_RSP = 433; // 服务端：晋升结果

    public static final int RANK_UP_ITEM_CS_REQ = 434; // 客户端：道具升阶
    public static final int RANK_UP_ITEM_SC_RSP = 435; // 服务端：升阶结果

    public static final int LOCK_ITEM_CS_REQ = 440; // 客户端：锁定/解锁道具
    public static final int LOCK_ITEM_SC_RSP = 441; // 服务端：锁定状态结果

    public static final int DISCARD_ITEM_CS_REQ = 442; // 客户端：丢弃道具
    public static final int DISCARD_ITEM_SC_RSP = 443; // 服务端：丢弃结果

    public static final int ITEM_CHANGE_SC_NOTIFY = 450; // 服务端：背包变更推送

    // 抽卡系统
    public static final int GET_GACHA_INFO_CS_REQ = 500; // 客户端：查询卡池信息
    public static final int GET_GACHA_INFO_SC_RSP = 501; // 服务端：卡池信息

    public static final int DO_GACHA_CS_REQ = 502; // 客户端：发起抽卡
    public static final int DO_GACHA_SC_RSP = 503; // 服务端：抽卡结果

    public static final int EXCHANGE_GACHA_CEILING_CS_REQ = 504; // 客户端：兑换保底/天井
    public static final int EXCHANGE_GACHA_CEILING_SC_RSP = 505; // 服务端：兑换结果

    public static final int GET_GACHA_HISTORY_CS_REQ = 506; // 客户端：查询抽卡历史
    public static final int GET_GACHA_HISTORY_SC_RSP = 507; // 服务端：抽卡历史

    public static final int GACHA_BANNER_UPDATE_SC_NOTIFY = 508; // 服务端：卡池横幅更新推送

    // 模拟宇宙（Rogue）系统
    public static final int START_ROGUE_CS_REQ = 550; // 客户端：开始 Rogue 关卡
    public static final int START_ROGUE_SC_RSP = 551; // 服务端：开局结果

    public static final int GET_ROGUE_INFO_CS_REQ = 552; // 客户端：查询 Rogue 进度信息
    public static final int GET_ROGUE_INFO_SC_RSP = 553; // 服务端：Rogue 信息

    public static final int ROGUE_MOVE_CS_REQ = 554; // 客户端：Rogue 地图移动
    public static final int ROGUE_MOVE_SC_RSP = 555; // 服务端：移动结果

    public static final int ROGUE_SELECT_BLESSING_CS_REQ = 556; // 客户端：选择祝福
    public static final int ROGUE_SELECT_BLESSING_SC_RSP = 557; // 服务端：祝福选择结果

    public static final int ROGUE_SELECT_MIRACLE_CS_REQ = 558; // 客户端：选择奇物
    public static final int ROGUE_SELECT_MIRACLE_SC_RSP = 559; // 服务端：奇物选择结果

    public static final int ROGUE_BATTLE_RESULT_CS_REQ = 560; // 客户端：上报 Rogue 战斗结果
    public static final int ROGUE_BATTLE_RESULT_SC_RSP = 561; // 服务端：结算响应

    public static final int ROGUE_EVENT_CS_REQ = 562; // 客户端：触发 Rogue 事件分支
    public static final int ROGUE_EVENT_SC_RSP = 563; // 服务端：事件处理结果

    public static final int ROGUE_QUIT_CS_REQ = 564; // 客户端：退出 Rogue
    public static final int ROGUE_QUIT_SC_RSP = 565; // 服务端：退出结果

    public static final int GET_ROGUE_GLOBAL_INFO_CS_REQ = 566; // 客户端：查询 Rogue 全局信息
    public static final int GET_ROGUE_GLOBAL_INFO_SC_RSP = 567; // 服务端：全局信息

    public static final int ROGUE_SELECT_PATH_CS_REQ = 568; // 客户端：选择命途/路径
    public static final int ROGUE_SELECT_PATH_SC_RSP = 569; // 服务端：路径选择结果

    public static final int ROGUE_STATE_UPDATE_SC_NOTIFY = 570; // 服务端：Rogue 状态变更推送

    // 模拟宇宙（Rogue）系统 - 天赋升级
    public static final int ROGUE_UPGRADE_TALENT_CS_REQ = 598; // 客户端：升级 Rogue 天赋
    public static final int ROGUE_UPGRADE_TALENT_SC_RSP = 599; // 服务端：天赋升级结果

    // 挑战与地下城系统
    public static final int START_CHALLENGE_CS_REQ = 600; // 客户端：开始挑战
    public static final int START_CHALLENGE_SC_RSP = 601; // 服务端：挑战开始结果

    public static final int GET_CHALLENGE_INFO_CS_REQ = 602; // 客户端：查询挑战信息
    public static final int GET_CHALLENGE_INFO_SC_RSP = 603; // 服务端：挑战信息

    public static final int REPORT_CHALLENGE_RESULT_CS_REQ = 604; // 客户端：上报挑战结果
    public static final int REPORT_CHALLENGE_RESULT_SC_RSP = 605; // 服务端：上报结果响应

    public static final int CLAIM_CHALLENGE_GROUP_REWARD_CS_REQ = 606; // 客户端：领取挑战组奖励
    public static final int CLAIM_CHALLENGE_GROUP_REWARD_SC_RSP = 607; // 服务端：领取结果

    public static final int GET_CHALLENGE_HISTORY_CS_REQ = 608; // 客户端：查询挑战历史
    public static final int GET_CHALLENGE_HISTORY_SC_RSP = 609; // 服务端：挑战历史

    public static final int GET_CHALLENGE_GROUP_REWARD_CS_REQ = 610; // 客户端：查询挑战组奖励状态
    public static final int GET_CHALLENGE_GROUP_REWARD_SC_RSP = 611; // 服务端：奖励状态

    public static final int CHALLENGE_STAGE_UPDATE_SC_NOTIFY = 612; // 服务端：挑战关卡进度推送
    public static final int CHALLENGE_SETTLE_SC_NOTIFY = 613; // 服务端：挑战结算推送
}

