package cn.itcast.demo.mylunarcore.net;

/**
 * 客户端 SupportedFeatures 位掩码（登录握手交换）。
 * 服务端据此隐藏未支持玩法入口，避免下发空数据。
 */
public final class ClientFeatureFlags {

    private ClientFeatureFlags() {}

    public static final long GUILD = 1L << 0;
    public static final long GUILD_WAR = 1L << 1;
    public static final long HOME = 1L << 2;
    public static final long DIALOGUE_TREE = 1L << 3;
    public static final long BATTLE_PASS = 1L << 4;
    public static final long DAILY_MISSION = 1L << 5;
    public static final long SUPPORT_UNIT = 1L << 6;
    public static final long VERSION_ACTIVITY = 1L << 7;
    public static final long STORY_CHAPTER = 1L << 8;
    /** 角色协议号段 160–173（wire_version≥2）；旧客户端勿声明。 */
    public static final long CHARACTER_V2 = 1L << 9;
    /** 家园社交（点赞/繁荣度榜）。 */
    public static final long HOME_SOCIAL = 1L << 10;
    /** 键鼠输入（wire v3，与 LoginCsReq.input_methods bit0 对齐）。 */
    public static final long INPUT_KEYBOARD_MOUSE = 1L << 11;
    /** 触屏输入。 */
    public static final long INPUT_TOUCH = 1L << 12;
    /** 手柄输入。 */
    public static final long INPUT_GAMEPAD = 1L << 13;
    /** 理解 recommended_layout_id / 分级切图过渡。 */
    public static final long LAYOUT_HINT = 1L << 14;
    /** 服务端代打自动战斗。 */
    public static final long BATTLE_AUTO = 1L << 15;

    /** 服务端当前完整能力集（新客户端应声明）。 */
    public static final long SERVER_ALL = GUILD | GUILD_WAR | HOME | DIALOGUE_TREE
            | BATTLE_PASS | DAILY_MISSION | SUPPORT_UNIT | VERSION_ACTIVITY | STORY_CHAPTER
            | CHARACTER_V2 | HOME_SOCIAL
            | INPUT_KEYBOARD_MOUSE | INPUT_TOUCH | INPUT_GAMEPAD | LAYOUT_HINT | BATTLE_AUTO;

    public static boolean supports(long mask, long feature) {
        return (mask & feature) != 0;
    }

    /** 未声明时按旧客户端：仅基础能力（无公会战等）。 */
    public static long normalizeClientMask(long clientMask) {
        if (clientMask == 0L) {
            return GUILD | HOME | DIALOGUE_TREE;
        }
        return clientMask;
    }
}
