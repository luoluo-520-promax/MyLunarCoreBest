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

    public static final int GET_VERSION_INFO_CS_REQ = 76; // 客户端：查询版本/资源热更信息
    public static final int GET_VERSION_INFO_SC_RSP = 77; // 服务端：版本信息响应
    public static final int VERSION_UPDATE_SC_NOTIFY = 78; // 服务端：版本/资源热更推送

    /**
     * 客户端：主界面「开始游戏」，从 HALL 进入场景玩法
     */
    public static final int START_GAME_CS_REQ = 80;
    /**
     * 服务端：开始游戏结果（成功后 session_state=SCENE）
     */
    public static final int START_GAME_SC_RSP = 81;
    /**
     * 客户端：退出当前玩法并返回主界面（保持登录，不断开连接）
     */
    public static final int RETURN_MAIN_MENU_CS_REQ = 82;
    /**
     * 服务端：返回主界面结果（成功后 session_state=HALL）
     */
    public static final int RETURN_MAIN_MENU_SC_RSP = 83;

    // ---------- 大厅/社交 ----------
    public static final int GET_FRIEND_LIST_CS_REQ = 100;
    public static final int GET_FRIEND_LIST_SC_RSP = 101;
    public static final int SEND_FRIEND_REQUEST_CS_REQ = 102;
    public static final int SEND_FRIEND_REQUEST_SC_RSP = 103;
    public static final int RESPOND_FRIEND_REQUEST_CS_REQ = 104;
    public static final int RESPOND_FRIEND_REQUEST_SC_RSP = 105;
    public static final int GET_MAIL_LIST_CS_REQ = 106;
    public static final int GET_MAIL_LIST_SC_RSP = 107;
    public static final int CLAIM_MAIL_CS_REQ = 108;
    public static final int CLAIM_MAIL_SC_RSP = 109;
    public static final int GET_LEADERBOARD_CS_REQ = 110;
    public static final int GET_LEADERBOARD_SC_RSP = 111;
    public static final int FRIEND_LIST_UPDATE_SC_NOTIFY = 112;
    public static final int MAIL_UPDATE_SC_NOTIFY = 113;
    public static final int SEND_CHAT_CS_REQ = 114;
    public static final int SEND_CHAT_SC_RSP = 115;
    public static final int CHAT_MESSAGE_SC_NOTIFY = 116;
    public static final int GET_CHAT_HISTORY_CS_REQ = 117;
    public static final int GET_CHAT_HISTORY_SC_RSP = 118;

    // ---------- 组队 Party（120–129，独占；勿与角色号段混用） ----------
    public static final int CREATE_PARTY_CS_REQ = 120;
    public static final int CREATE_PARTY_SC_RSP = 121;
    public static final int INVITE_PARTY_CS_REQ = 122;
    public static final int INVITE_PARTY_SC_RSP = 123;
    public static final int LEAVE_PARTY_CS_REQ = 124;
    public static final int LEAVE_PARTY_SC_RSP = 125;
    public static final int DISBAND_PARTY_CS_REQ = 126;
    public static final int DISBAND_PARTY_SC_RSP = 127;
    public static final int GET_PARTY_INFO_CS_REQ = 128;
    public static final int GET_PARTY_INFO_SC_RSP = 129;

    /**
     * 线协议主版本。v2：角色号段 160–173；v3：登录握手 InputCapability；v4：迁移进度/战斗修正/活动流程/资源差量。
     */
    public static final int PROTOCOL_WIRE_VERSION = 4;

    // ---------- 角色（160–173；历史曾与 Party 120–129 重叠，v2 已拆分） ----------
    public static final int CREATE_CHARACTER_CS_REQ = 160;
    public static final int CREATE_CHARACTER_SC_RSP = 161;
    public static final int PROMOTE_AVATAR_CS_REQ = 162;
    public static final int PROMOTE_AVATAR_SC_RSP = 163;
    public static final int GET_AVATAR_ATTRIBUTES_CS_REQ = 164;
    public static final int GET_AVATAR_ATTRIBUTES_SC_RSP = 165;
    public static final int UPGRADE_TALENT_CS_REQ = 166;
    public static final int UPGRADE_TALENT_SC_RSP = 167;
    public static final int GET_TALENT_LIST_CS_REQ = 168;
    public static final int GET_TALENT_LIST_SC_RSP = 169;
    public static final int GET_SKIN_WARDROBE_CS_REQ = 170; // 客户端：拉取角色皮肤衣柜
    public static final int GET_SKIN_WARDROBE_SC_RSP = 171; // 服务端：衣柜列表
    public static final int EQUIP_SKIN_CS_REQ = 172; // 客户端：穿戴/还原皮肤
    public static final int EQUIP_SKIN_SC_RSP = 173; // 服务端：穿戴结果

    // ---------- 成就（950–955） ----------
    public static final int GET_ACHIEVEMENT_LIST_CS_REQ = 950;
    public static final int GET_ACHIEVEMENT_LIST_SC_RSP = 951;
    public static final int CLAIM_ACHIEVEMENT_CS_REQ = 952;
    public static final int CLAIM_ACHIEVEMENT_SC_RSP = 953;
    public static final int ACHIEVEMENT_UPDATE_SC_NOTIFY = 954;

    // ---------- 新手引导（960–967） ----------
    public static final int GET_NEWBIE_GUIDE_CS_REQ = 960;
    public static final int GET_NEWBIE_GUIDE_SC_RSP = 961;
    public static final int ADVANCE_NEWBIE_GUIDE_CS_REQ = 962;
    public static final int ADVANCE_NEWBIE_GUIDE_SC_RSP = 963;
    public static final int NEWBIE_GUIDE_UPDATE_SC_NOTIFY = 964;
    public static final int SKIP_NEWBIE_GUIDE_CS_REQ = 965;
    public static final int SKIP_NEWBIE_GUIDE_SC_RSP = 966;

    // ---------- 公会（970–989） ----------
    public static final int CREATE_GUILD_CS_REQ = 970;
    public static final int CREATE_GUILD_SC_RSP = 971;
    public static final int JOIN_GUILD_CS_REQ = 972;
    public static final int JOIN_GUILD_SC_RSP = 973;
    public static final int LEAVE_GUILD_CS_REQ = 974;
    public static final int LEAVE_GUILD_SC_RSP = 975;
    public static final int GET_GUILD_INFO_CS_REQ = 976;
    public static final int GET_GUILD_INFO_SC_RSP = 977;
    public static final int CONTRIBUTE_GUILD_CS_REQ = 978;
    public static final int CONTRIBUTE_GUILD_SC_RSP = 979;
    public static final int GET_GUILD_SHOP_CS_REQ = 980;
    public static final int GET_GUILD_SHOP_SC_RSP = 981;
    public static final int BUY_GUILD_SHOP_CS_REQ = 982;
    public static final int BUY_GUILD_SHOP_SC_RSP = 983;
    /** 客户端：申请公会战匹配 */
    public static final int GUILD_WAR_MATCH_CS_REQ = 984;
    public static final int GUILD_WAR_MATCH_SC_RSP = 985;
    /** 客户端：上报公会战战斗结果 */
    public static final int GUILD_WAR_REPORT_CS_REQ = 986;
    public static final int GUILD_WAR_REPORT_SC_RSP = 987;
    /** 客户端：查询公会战赛季排行 */
    public static final int GUILD_WAR_RANK_CS_REQ = 988;
    public static final int GUILD_WAR_RANK_SC_RSP = 989;

    /** 战令：查询进度 */
    public static final int GET_BATTLE_PASS_CS_REQ = 990;
    public static final int GET_BATTLE_PASS_SC_RSP = 991;
    /** 战令：领取等级奖励 */
    public static final int CLAIM_BATTLE_PASS_CS_REQ = 992;
    public static final int CLAIM_BATTLE_PASS_SC_RSP = 993;
    /** 战令：开通付费轨 */
    public static final int BUY_BATTLE_PASS_PREMIUM_CS_REQ = 994;
    public static final int BUY_BATTLE_PASS_PREMIUM_SC_RSP = 995;
    /** 战令：XP/等级/赛季变更推送 */
    public static final int BATTLE_PASS_UPDATE_SC_NOTIFY = 1009;

    // ---------- 日常循环：体力 / 每日任务 / 章节（996–1008；1009 归战令推送） ----------
    public static final int GET_STAMINA_CS_REQ = 996;
    public static final int GET_STAMINA_SC_RSP = 997;
    public static final int BUY_STAMINA_CS_REQ = 998;
    public static final int BUY_STAMINA_SC_RSP = 999;
    public static final int STAMINA_UPDATE_SC_NOTIFY = 1000;

    public static final int GET_DAILY_MISSION_CS_REQ = 1001;
    public static final int GET_DAILY_MISSION_SC_RSP = 1002;
    public static final int CLAIM_DAILY_MISSION_CS_REQ = 1003;
    public static final int CLAIM_DAILY_MISSION_SC_RSP = 1004;
    public static final int DAILY_MISSION_UPDATE_SC_NOTIFY = 1005;
    public static final int SWEEP_STAGE_CS_REQ = 1010;
    public static final int SWEEP_STAGE_SC_RSP = 1011;
    public static final int GET_SWEEP_INFO_CS_REQ = 1012;
    public static final int GET_SWEEP_INFO_SC_RSP = 1013;

    public static final int GET_STORY_CHAPTER_CS_REQ = 1006;
    public static final int GET_STORY_CHAPTER_SC_RSP = 1007;
    public static final int STORY_CHAPTER_UPDATE_SC_NOTIFY = 1008;

    // ---------- 经济 ----------
    public static final int GET_SHOP_LIST_CS_REQ = 140;
    public static final int GET_SHOP_LIST_SC_RSP = 141;
    public static final int BUY_SHOP_ITEM_CS_REQ = 142;
    public static final int BUY_SHOP_ITEM_SC_RSP = 143;
    public static final int CURRENCY_CHANGE_SC_NOTIFY = 144;
    public static final int CREATE_IAP_ORDER_CS_REQ = 145;
    public static final int CREATE_IAP_ORDER_SC_RSP = 146;
    public static final int CONFIRM_IAP_ORDER_CS_REQ = 147;
    public static final int CONFIRM_IAP_ORDER_SC_RSP = 148;
    public static final int CLAIM_IAP_DAILY_CS_REQ = 149;
    public static final int CLAIM_IAP_DAILY_SC_RSP = 150;

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
    public static final int ASK_BATTLE_HINT_CS_REQ = 211; // 客户端：请求战斗战术提示（方案 D）
    public static final int ASK_BATTLE_HINT_SC_RSP = 212; // 服务端：战术提示响应
    public static final int BATTLE_FX_SC_NOTIFY = 213; // 服务端：打击感 FX 元数据推送

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

    public static final int MOVE_CS_REQ = 316; // 客户端：场景移动
    public static final int MOVE_SC_RSP = 317; // 服务端：移动结果

    public static final int MIGRATE_SCENE_CS_REQ = 350; // 客户端：跨场景迁移
    public static final int MIGRATE_SCENE_SC_RSP = 351; // 服务端：迁移结果
    public static final int SCENE_LOAD_COMPLETE_CS_REQ = 352; // 客户端：切图加载完成
    public static final int SCENE_LOAD_COMPLETE_SC_RSP = 353; // 服务端：加载完成确认
    public static final int SCENE_PRELOAD_CS_REQ = 354; // 客户端：接近传送门后台预加载
    public static final int SCENE_PRELOAD_SC_RSP = 355; // 服务端：预加载受理与资源键
    public static final int SCENE_PRELOAD_READY_SC_NOTIFY = 356; // 服务端：预加载就绪推送

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

    public static final int GACHA_START_CS_REQ = 509; // 客户端：抽卡表现开始（三次握手 1/3）
    public static final int GACHA_START_SC_RSP = 510; // 服务端：返回 presentation_session + client_ui
    public static final int GACHA_RESULT_ACK_CS_REQ = 511; // 客户端：动画结束确认（三次握手 3/3）
    public static final int GACHA_RESULT_ACK_SC_RSP = 512; // 服务端：关闭表现会话

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

    // ---------- 活动配置 ----------
    public static final int GET_ACTIVITY_INFO_CS_REQ = 650; // 客户端：查询活动配置
    public static final int GET_ACTIVITY_INFO_SC_RSP = 651; // 服务端：活动配置响应
    public static final int ACTIVITY_CONFIG_UPDATE_SC_NOTIFY = 652; // 服务端：活动配置热更推送

    // ---------- 任务/剧情 ----------
    public static final int GET_QUEST_LIST_CS_REQ = 700;
    public static final int GET_QUEST_LIST_SC_RSP = 701;
    public static final int ACCEPT_QUEST_CS_REQ = 702;
    public static final int ACCEPT_QUEST_SC_RSP = 703;
    public static final int SUBMIT_QUEST_CS_REQ = 704;
    public static final int SUBMIT_QUEST_SC_RSP = 705;
    public static final int ABANDON_QUEST_CS_REQ = 706;
    public static final int ABANDON_QUEST_SC_RSP = 707;
    public static final int QUEST_UPDATE_SC_NOTIFY = 708;

    // ---------- 匹配/房间 ----------
    public static final int JOIN_MATCH_QUEUE_CS_REQ = 800;
    public static final int JOIN_MATCH_QUEUE_SC_RSP = 801;
    public static final int CANCEL_MATCH_QUEUE_CS_REQ = 802;
    public static final int CANCEL_MATCH_QUEUE_SC_RSP = 803;
    public static final int MATCH_ROOM_SC_NOTIFY = 804;
    public static final int SET_ROOM_READY_CS_REQ = 805;
    public static final int SET_ROOM_READY_SC_RSP = 806;
    /** 客户端：查询竞技场 ELO / 赛季榜（mode=10） */
    public static final int GET_ARENA_INFO_CS_REQ = 807;
    public static final int GET_ARENA_INFO_SC_RSP = 808;

    // ---------- 家园 ----------
    public static final int GET_HOME_INFO_CS_REQ = 850;
    public static final int GET_HOME_INFO_SC_RSP = 851;
    public static final int HOME_PLACE_FACILITY_CS_REQ = 852;
    public static final int HOME_PLACE_FACILITY_SC_RSP = 853;
    public static final int HOME_CLAIM_PRODUCE_CS_REQ = 854;
    public static final int HOME_CLAIM_PRODUCE_SC_RSP = 855;
    public static final int HOME_VISIT_CS_REQ = 856;
    public static final int HOME_VISIT_SC_RSP = 857;
    public static final int HOME_PLACE_FURNITURE_CS_REQ = 858;
    public static final int HOME_PLACE_FURNITURE_SC_RSP = 859;
    public static final int HOME_FURNITURE_INTERACT_CS_REQ = 870;
    public static final int HOME_FURNITURE_INTERACT_SC_RSP = 871;
    public static final int HOME_AVATAR_SYNC_SC_NOTIFY = 872;
    public static final int HOME_UPDATE_PRESENCE_CS_REQ = 873;
    public static final int HOME_UPDATE_PRESENCE_SC_RSP = 874;

    // ---------- 剧情对话/过场 ----------
    public static final int DIALOGUE_CHOOSE_CS_REQ = 860;
    public static final int DIALOGUE_CHOOSE_SC_RSP = 861;
    public static final int DIALOGUE_SKIP_CS_REQ = 862;
    public static final int DIALOGUE_SKIP_SC_RSP = 863;
    public static final int CUTSCENE_SKIP_CS_REQ = 864;
    public static final int CUTSCENE_SKIP_SC_RSP = 865;
    public static final int CUTSCENE_COMPLETE_CS_REQ = 866;
    public static final int CUTSCENE_COMPLETE_SC_RSP = 867;
    public static final int CUTSCENE_SC_NOTIFY = 868;

    // ---------- AI 辅助 / 游戏教练 ----------
    public static final int ASK_COACH_HINT_CS_REQ = 900; // 客户端：请求规则教练建议
    public static final int ASK_COACH_HINT_SC_RSP = 901; // 服务端：教练建议响应
    public static final int ASK_AI_ASSIST_CS_REQ = 902; // 客户端：自然语言助手提问
    public static final int ASK_AI_ASSIST_SC_RSP = 903; // 服务端：助手回答（可降级规则）
    public static final int AI_HINT_SC_NOTIFY = 904; // 服务端：异步 AI 建议推送
    public static final int GET_GUIDE_PACK_CS_REQ = 910; // 客户端：拉取端侧攻略包（方案 E）
    public static final int GET_GUIDE_PACK_SC_RSP = 911; // 服务端：攻略包响应
    public static final int GUIDE_PACK_UPDATE_SC_NOTIFY = 912; // 服务端：攻略包热更推送
    public static final int ASSIST_FEEDBACK_CS_REQ = 913; // 客户端：助手回答有用性反馈
    public static final int ASSIST_FEEDBACK_SC_RSP = 914; // 服务端：反馈受理结果
    public static final int ASK_LINEUP_RECOMMEND_CS_REQ = 915; // 客户端：已拥有角色阵容推荐
    public static final int ASK_LINEUP_RECOMMEND_SC_RSP = 916; // 服务端：阵容推荐响应
    public static final int ASK_EXPLORE_PATH_CS_REQ = 917; // 客户端：请求探索路径标记
    public static final int ASK_EXPLORE_PATH_SC_RSP = 918; // 服务端：探索路径与资源提示
    public static final int ASK_QUEST_GUIDANCE_CS_REQ = 919; // 客户端：请求渐进式任务提示
    public static final int ASK_QUEST_GUIDANCE_SC_RSP = 920; // 服务端：任务渐进提示响应
    public static final int ENVIRONMENT_NARRATION_SC_NOTIFY = 921; // 服务端：环境解说推送

    // ---------- 游戏设置 / 客服工单（930–940，与 settings_system.proto 对齐） ----------
    /**
     * 客户端请求：拉取当前账号云端同步的画面/声音/按键/游戏细节设置
     */
    public static final int GET_PLAYER_SETTINGS_CS_REQ = 930;
    /**
     * 服务端响应：返回 PlayerSettingsSnapshot；retcode 0 成功，1 未登录
     */
    public static final int GET_PLAYER_SETTINGS_SC_RSP = 931;
    /**
     * 客户端请求：更新设置（可只带部分分区，或带 reset_* 标志重置某分区）
     */
    public static final int UPDATE_PLAYER_SETTINGS_CS_REQ = 932;
    /**
     * 服务端响应：回显合并后的完整设置快照
     */
    public static final int UPDATE_PLAYER_SETTINGS_SC_RSP = 933;
    /**
     * 客户端请求：恢复默认设置（reset_all 或按分区 reset）
     */
    public static final int RESET_PLAYER_SETTINGS_CS_REQ = 934;
    /**
     * 服务端响应：返回重置后的设置快照
     */
    public static final int RESET_PLAYER_SETTINGS_SC_RSP = 935;
    /**
     * 服务端推送：设置已变更（更新或重置后主动通知客户端刷新 UI）
     */
    public static final int PLAYER_SETTINGS_UPDATE_SC_NOTIFY = 936;
    /**
     * 客户端请求：提交客服工单（可咨询画面/按键/声音等设置问题）
     */
    public static final int SUBMIT_SUPPORT_TICKET_CS_REQ = 937;
    /**
     * 服务端响应：返回 ticket_id；retcode 0 成功，1 未登录，2 内容空，3 超限，4 落库失败
     */
    public static final int SUBMIT_SUPPORT_TICKET_SC_RSP = 938;
    /**
     * 客户端请求：分页查询本人历史客服工单（含客服回复）
     */
    public static final int GET_SUPPORT_TICKET_LIST_CS_REQ = 939;
    /**
     * 服务端响应：工单列表与 total
     */
    public static final int GET_SUPPORT_TICKET_LIST_SC_RSP = 940;

    // ---------- 体验补齐：战斗 Auto / 倍速（1020–1024） ----------
    public static final int SET_BATTLE_AUTO_CS_REQ = 1020;
    public static final int SET_BATTLE_AUTO_SC_RSP = 1021;
    public static final int BATTLE_AUTO_SC_NOTIFY = 1022;
    public static final int SET_BATTLE_SPEED_CS_REQ = 1023;
    public static final int SET_BATTLE_SPEED_SC_RSP = 1024;

    // ---------- 体力溢出提取 / 过期转化（1025–1027） ----------
    public static final int CLAIM_RESERVE_STAMINA_CS_REQ = 1025;
    public static final int CLAIM_RESERVE_STAMINA_SC_RSP = 1026;
    public static final int ITEM_RECYCLE_SC_NOTIFY = 1027;

    // ---------- 场景强制预加载（1028） ----------
    public static final int SCENE_PRELOAD_PUSH_SC_NOTIFY = 1028;

    // ---------- 家园拜访日志 / 留言（1030–1035） ----------
    public static final int GET_HOME_VISITOR_LOG_CS_REQ = 1030;
    public static final int GET_HOME_VISITOR_LOG_SC_RSP = 1031;
    public static final int HOME_VISITOR_LOG_SC_NOTIFY = 1032;
    public static final int FRIEND_ONLINE_SC_NOTIFY = 1033;
    public static final int HOME_LEAVE_MESSAGE_CS_REQ = 1034;
    public static final int HOME_LEAVE_MESSAGE_SC_RSP = 1035;

    // ---------- 成就即时推送 / 材料反查（1036–1038） ----------
    public static final int ACHIEVEMENT_UNLOCK_PUSH_SC_NOTIFY = 1036;
    public static final int QUERY_ITEM_SOURCE_CS_REQ = 1037;
    public static final int QUERY_ITEM_SOURCE_SC_RSP = 1038;

    // ---------- 对话存档 / 过场回放（1039–1042） ----------
    public static final int RESUME_FROM_BRANCH_CS_REQ = 1039;
    public static final int RESUME_FROM_BRANCH_SC_RSP = 1040;
    public static final int REPLAY_CUTSCENE_CS_REQ = 1041;
    public static final int REPLAY_CUTSCENE_SC_RSP = 1042;

    // ---------- 匹配成功 Ready Check（1043–1045） ----------
    public static final int MATCH_SUCCESS_SC_NOTIFY = 1043;
    public static final int MATCH_READY_CHECK_CS_REQ = 1044;
    public static final int MATCH_READY_CHECK_SC_RSP = 1045;

    // ---------- 养成计划 / 一键连续扫荡（1046–1050） ----------
    public static final int CALCULATE_UPGRADE_MATERIALS_CS_REQ = 1046;
    public static final int CALCULATE_UPGRADE_MATERIALS_SC_RSP = 1047;
    public static final int BATCH_SWEEP_CS_REQ = 1048;
    public static final int BATCH_SWEEP_SC_RSP = 1049;
    public static final int BATCH_SWEEP_PROGRESS_SC_NOTIFY = 1050;

    // ---------- Auto 手动大招覆盖（1051–1052） ----------
    public static final int BATTLE_MANUAL_ULT_CS_REQ = 1051;
    public static final int BATTLE_MANUAL_ULT_SC_RSP = 1052;

    // ---------- 场景物理交互反馈（1053） ----------
    public static final int SCENE_INTERACT_PHYSICS_SC_NOTIFY = 1053;

    // ---------- 表情包 / 场景动作（1054–1061） ----------
    public static final int GET_EMOTE_INVENTORY_CS_REQ = 1054;
    public static final int GET_EMOTE_INVENTORY_SC_RSP = 1055;
    public static final int SEND_CHAT_EMOTE_CS_REQ = 1056;
    public static final int SEND_CHAT_EMOTE_SC_RSP = 1057;
    public static final int CHAT_EMOTE_SC_NOTIFY = 1058;
    public static final int SCENE_EMOTE_CS_REQ = 1059;
    public static final int SCENE_EMOTE_SC_RSP = 1060;
    public static final int SCENE_EMOTE_SC_NOTIFY = 1061;

    // ---------- 键位云同步（1062–1064） ----------
    public static final int SYNC_KEY_BIND_CS_REQ = 1062;
    public static final int SYNC_KEY_BIND_SC_RSP = 1063;
    public static final int PUSH_KEY_BIND_SC_NOTIFY = 1064;

    // ---------- 剧情树快照（1065–1066） ----------
    public static final int GET_STORY_TREE_SNAPSHOT_CS_REQ = 1065;
    public static final int GET_STORY_TREE_SNAPSHOT_SC_RSP = 1066;

    // ---------- 家园助产 / 溢出推送（1067–1069） ----------
    public static final int HOME_HARVEST_ASSIST_CS_REQ = 1067;
    public static final int HOME_HARVEST_ASSIST_SC_RSP = 1068;
    public static final int HOME_OVERFLOW_SC_NOTIFY = 1069;

    // ---------- 体验优化二期（1070–1077） ----------
    /** 剧情选项触发场景环境联动（NPC 微表情 / 粒子 / BGM） */
    public static final int SCENE_ENVIRONMENT_MODIFY_SC_NOTIFY = 1070;
    public static final int CALCULATE_OPTIMAL_SCHEDULE_CS_REQ = 1071;
    public static final int CALCULATE_OPTIMAL_SCHEDULE_SC_RSP = 1072;
    public static final int DEVELOPMENT_SCHEDULE_SC_NOTIFY = 1073;
    public static final int HOME_ASSIST_EFFECT_SC_NOTIFY = 1074;
    public static final int MATCH_COUNTDOWN_INTERACTIVE_SC_NOTIFY = 1075;
    /** 跨节点组队：队员实体跟随迁移至队长节点 */
    public static final int PARTY_FOLLOW_MIGRATE_SC_NOTIFY = 1076;
    /** 箱庭环境微交互：踢石子 / 草丛晃动 / 水面涟漪（仅表现） */
    public static final int SCENE_ENV_MICRO_INTERACT_SC_NOTIFY = 1078;

    // ---------- 体验优化三期（1080–1097） ----------
    /** 客户端性能探针上报：SoC 温度 / FPS / 电量 */
    public static final int REPORT_DEVICE_PERF_CS_REQ = 1080;
    public static final int REPORT_DEVICE_PERF_SC_RSP = 1081;
    /** 服务端主动下发渲染降级（防烫手） */
    public static final int SCENE_PERFORMANCE_ADJUST_SC_NOTIFY = 1082;
    public static final int GET_WORLD_TIME_CS_REQ = 1083;
    public static final int GET_WORLD_TIME_SC_RSP = 1084;
    public static final int WORLD_TIME_SC_NOTIFY = 1085;
    /** 连战扫荡增量结算摘要（跳过 3D 抽奖盒动画） */
    public static final int BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY = 1086;
    public static final int HOME_SHADOW_GREETING_CS_REQ = 1087;
    public static final int HOME_SHADOW_GREETING_SC_RSP = 1088;
    public static final int FRIEND_SHADOW_GREETING_SC_NOTIFY = 1089;
    /** AOI 同步：死亡荧光残影出现/消失 */
    public static final int SCENE_DEATH_ECHO_SYNC_SC_NOTIFY = 1090;
    public static final int COMFORT_DEATH_ECHO_CS_REQ = 1091;
    public static final int COMFORT_DEATH_ECHO_SC_RSP = 1092;
    /** AI 助手：地图荧光引路线 */
    public static final int SCENE_NAVIGATE_SC_NOTIFY = 1093;
    /** AI 助手：自动寻路至材料门前 */
    public static final int AUTO_PATH_FIND_PUSH_SC_NOTIFY = 1094;
    public static final int GET_DEVICE_HAPTICS_CS_REQ = 1095;
    public static final int GET_DEVICE_HAPTICS_SC_RSP = 1096;
    public static final int PUSH_DEVICE_HAPTICS_SC_NOTIFY = 1097;

    // ---------- AI 助手增强（1098–1104） ----------
    /** 客户端：上传屏幕截图供 VLM 解读 */
    public static final int UPLOAD_SCREENSHOT_CS_REQ = 1098;
    public static final int UPLOAD_SCREENSHOT_SC_RSP = 1099;
    /** 服务端：屏幕 AR 叠加提示 */
    public static final int ASSIST_VISUAL_HINT_SC_NOTIFY = 1100;
    /** 客户端：一键采纳 AI Auto 策略 */
    public static final int APPLY_AI_SUGGESTION_CS_REQ = 1101;
    public static final int APPLY_AI_SUGGESTION_SC_RSP = 1102;
    /** 服务端：到达后自动打开二级 UI */
    public static final int ASSIST_DEEP_LINK_SC_NOTIFY = 1103;
    /** 服务端：语音配额减免埋点回执 */
    public static final int ASSIST_VOICE_USAGE_SC_NOTIFY = 1104;

    // ---------- 战斗手感 / 拥堵防护（1105–1107） ----------
    /** 服务端：断线重连行动增量回放 */
    public static final int BATTLE_REPLAY_DELTA_SC_NOTIFY = 1105;
    /** 服务端：场景节流（降低 NPC 刷新率保 BattleTick） */
    public static final int THROTTLE_SC_NOTIFY = 1106;
    /** 服务端：切图下发阻挡多边形（客户端预碰撞） */
    public static final int SCENE_COLLISION_MESH_SC_NOTIFY = 1107;

    // ---------- 运维停机 / 资源门禁（1108+） ----------
    /** 服务端：维护预告推送（ServerMaintenancePush，预计停机分钟数） */
    public static final int SERVER_MAINTENANCE_PUSH = 1108;

    // ---------- 稳定性 / 体验深化（1109–1125，wire v4） ----------
    /** 跨节点组队迁移进度（Saga 状态机推进） */
    @ProtoCommand(cmdId = 1109, purpose = "跨节点组队迁移进度", system = "party", pushNotify = true)
    public static final int PARTY_MIGRATE_PROGRESS_SC_NOTIFY = 1109;
    /** 战斗客户端预测偏差修正（生命值/状态权威回放） */
    @ProtoCommand(cmdId = 1110, purpose = "战斗预测修正", system = "battle", pushNotify = true)
    public static final int BATTLE_CORRECTION_SC_NOTIFY = 1110;
    /** Auto 弱网：客户端超时后主动确认/重试行动 */
    public static final int BATTLE_ACTION_CONFIRM_CS_REQ = 1111;
    public static final int BATTLE_ACTION_CONFIRM_SC_RSP = 1112;
    /** Auto 弱网：行动等待进度条 */
    public static final int BATTLE_TICK_PROGRESS_SC_NOTIFY = 1113;
    /** 活动流程 DSL 步骤推送（泛用 UI 容器） */
    public static final int ACTIVITY_FLOW_SC_NOTIFY = 1114;
    /** 好友亲密度变化 */
    public static final int FRIEND_INTIMACY_SC_NOTIFY = 1115;
    /** AI 助手情感气泡风格 */
    public static final int ASSIST_EMOTION_SC_NOTIFY = 1116;
    /** 资源差量补丁清单（支持断点续传） */
    public static final int RESOURCE_PATCH_SC_NOTIFY = 1117;
    /** 跨服私聊可靠投递 ACK */
    public static final int CHAT_MESSAGE_ACK_CS_REQ = 1118;
    public static final int CHAT_MESSAGE_ACK_SC_RSP = 1119;
    /** 登录后上报客户端支持的 Cmd 版本范围，返回 enabled_cmd_ids */
    public static final int PROTOCOL_CAPABILITY_CS_REQ = 1120;
    public static final int PROTOCOL_CAPABILITY_SC_RSP = 1121;
    /** 公会多人同屏讨伐状态 */
    public static final int GUILD_RAID_STATE_SC_NOTIFY = 1123;
    /** 语音信令票据下发 */
    public static final int VOICE_SIGNALING_SC_NOTIFY = 1124;
    /** 不支持的命令号统一回执（retcode=UNSUPPORTED_CMD） */
    public static final int UNSUPPORTED_CMD_SC_NOTIFY = 1125;

    // —— 体验缺口补齐：取消窗口 / QTE / 战术标记 / 重连 / 伤害榜 ——
    /** 取消后摇窗口下发（普攻命中后可接战技等） */
    public static final int CANCEL_WINDOW_SC_NOTIFY = 1130;
    /** 战斗 QTE 触发 */
    public static final int BATTLE_QTE_SC_NOTIFY = 1131;
    /** 战斗 QTE 玩家响应 */
    public static final int BATTLE_QTE_CS_REQ = 1132;
    public static final int BATTLE_QTE_SC_RSP = 1133;
    /** 场景战术 Ping（集火/集合） */
    public static final int SCENE_PING_CS_REQ = 1134;
    public static final int SCENE_PING_SC_RSP = 1135;
    public static final int SCENE_PING_SC_NOTIFY = 1136;
    /** Raid 实时伤害统计推送 */
    public static final int RAID_DAMAGE_STATS_SC_NOTIFY = 1137;
    /** 快速重连 */
    public static final int RECONNECT_CS_REQ = 1138;
    public static final int RECONNECT_SC_RSP = 1139;
    /** 云端 UI 缩放 / 触摸热区 */
    public static final int TOUCH_HEATMAP_SC_NOTIFY = 1140;
    public static final int UI_SCALE_SC_NOTIFY = 1141;
    /** 版本主题切换通知 */
    public static final int VERSION_THEME_SC_NOTIFY = 1142;
    /** NPC/生物日程行为切换（时段作息 → 客户端动画状态机） */
    @ProtoCommand(cmdId = 1143, purpose = "NPC日程行为推送", system = "scene", pushNotify = true)
    public static final int SCENE_NPC_BEHAVIOR_SC_NOTIFY = 1143;

    // ---------- 体验/社交缺口补齐（1200–1247） ----------
    /** 一键领取所有日常奖励（每日/战令/邮件/成就/签到） */
    public static final int CLAIM_ALL_DAILY_REWARDS_CS_REQ = 1200;
    public static final int CLAIM_ALL_DAILY_REWARDS_SC_RSP = 1201;
    /** 区域探索度与收集品 */
    public static final int GET_EXPLORATION_INFO_CS_REQ = 1202;
    public static final int GET_EXPLORATION_INFO_SC_RSP = 1203;
    public static final int EXPLORATION_UPDATE_SC_NOTIFY = 1204;
    /** 个人签名 / 自定义状态 */
    public static final int SET_PLAYER_STATUS_CS_REQ = 1205;
    public static final int SET_PLAYER_STATUS_SC_RSP = 1206;
    public static final int GET_PLAYER_PROFILE_DETAIL_CS_REQ = 1207;
    public static final int GET_PLAYER_PROFILE_DETAIL_SC_RSP = 1208;
    /** 举报 / 屏蔽 */
    public static final int REPORT_PLAYER_CS_REQ = 1209;
    public static final int REPORT_PLAYER_SC_RSP = 1210;
    public static final int BLOCK_PLAYER_CS_REQ = 1211;
    public static final int BLOCK_PLAYER_SC_RSP = 1212;
    public static final int REPORT_RESULT_SC_NOTIFY = 1213;
    /** 回流活动激活推送 */
    public static final int RETURN_ACTIVITY_SC_NOTIFY = 1214;
    /** 战斗录像分享 */
    public static final int SHARE_BATTLE_REPLAY_CS_REQ = 1215;
    public static final int SHARE_BATTLE_REPLAY_SC_RSP = 1216;
    public static final int GET_SHARED_REPLAY_CS_REQ = 1217;
    public static final int GET_SHARED_REPLAY_SC_RSP = 1218;
    public static final int GUILD_REPLAY_SC_NOTIFY = 1219;
    /** 每日挑战次数提醒 */
    public static final int DAILY_CHALLENGE_REMINDER_SC_NOTIFY = 1220;
    public static final int SET_DAILY_REMINDER_PREF_CS_REQ = 1221;
    public static final int SET_DAILY_REMINDER_PREF_SC_RSP = 1222;
    /** 自定义表情上传 */
    public static final int UPLOAD_CUSTOM_EMOTE_CS_REQ = 1223;
    public static final int UPLOAD_CUSTOM_EMOTE_SC_RSP = 1224;
    /** 月卡到账 / 签到补签 */
    public static final int MONTHLY_CARD_SC_NOTIFY = 1225;
    public static final int MAKEUP_SIGN_IN_CS_REQ = 1226;
    public static final int MAKEUP_SIGN_IN_SC_RSP = 1227;
    /** 好友/公会模糊搜索 / 最近联系人 */
    public static final int SEARCH_PLAYERS_CS_REQ = 1228;
    public static final int SEARCH_PLAYERS_SC_RSP = 1229;
    public static final int SEARCH_GUILDS_CS_REQ = 1230;
    public static final int SEARCH_GUILDS_SC_RSP = 1231;
    public static final int GET_RECENT_PLAYERS_CS_REQ = 1232;
    public static final int GET_RECENT_PLAYERS_SC_RSP = 1233;
    /** 荣誉称号 */
    public static final int GET_TITLE_LIST_CS_REQ = 1234;
    public static final int GET_TITLE_LIST_SC_RSP = 1235;
    public static final int EQUIP_TITLE_CS_REQ = 1236;
    public static final int EQUIP_TITLE_SC_RSP = 1237;
    /** 组队一键邀请好友/公会 */
    public static final int INVITE_FRIENDS_TO_PARTY_CS_REQ = 1238;
    public static final int INVITE_FRIENDS_TO_PARTY_SC_RSP = 1239;
    public static final int PARTY_INVITE_SC_NOTIFY = 1240;
    public static final int INVITE_GUILD_MEMBERS_CS_REQ = 1241;
    public static final int INVITE_GUILD_MEMBERS_SC_RSP = 1242;
    /** 活动即将结束提醒 */
    public static final int ACTIVITY_ENDING_SOON_SC_NOTIFY = 1243;
    /** 抽卡保底计数 / 消费历史导出 */
    public static final int GET_GACHA_GUARANTEE_INFO_CS_REQ = 1244;
    public static final int GET_GACHA_GUARANTEE_INFO_SC_RSP = 1245;
    public static final int EXPORT_TRANSACTION_HISTORY_CS_REQ = 1246;
    public static final int EXPORT_TRANSACTION_HISTORY_SC_RSP = 1247;

    /** 剧情表演时间轴推送 */
    public static final int DIALOGUE_PERFORMANCE_SC_NOTIFY = 1248;
    /** 抽卡结果命座变化推送 */
    public static final int GACHA_RESULT_SC_NOTIFY = 1249;
    /** 机关解谜完成 */
    public static final int SCENE_PUZZLE_COMPLETE_SC_NOTIFY = 1250;
    /** 探索限时事件（裂隙） */
    public static final int EXPLORATION_EVENT_SC_NOTIFY = 1251;
    /** 团队连携特效 */
    public static final int BATTLE_COMBO_SC_NOTIFY = 1252;
    /** Raid 策略准备期 */
    public static final int RAID_STRATEGY_PHASE_SC_NOTIFY = 1253;
    /** 新小玩法上线 */
    public static final int NEW_MINI_GAME_SC_NOTIFY = 1254;
    /** 活动高分分享建议 */
    public static final int ACTIVITY_HIGH_SCORE_SC_NOTIFY = 1255;
}

