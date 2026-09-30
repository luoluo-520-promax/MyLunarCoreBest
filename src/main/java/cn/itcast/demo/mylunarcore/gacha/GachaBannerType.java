// 卡池类型整型常量与 Banners.json 中 gachaType 字符串的映射工具
package cn.itcast.demo.mylunarcore.gacha;

/**
 * 卡池类型常量与配置字符串转换。
 * 协议与 DB 使用整型（1/2/11/12），配置文件使用可读字符串（Newbie/Normal 等）。
 */
public final class GachaBannerType {

    /** 私有构造器，禁止实例化工具类。 */
    private GachaBannerType() {}

    /** 新手池整型值 1：开服限定，保底规则与常驻池不同。 */
    public static final int NEWBIE = 1;
    /** 常驻池整型值 2：长期开放，累计 300 抽可兑换自选五星。 */
    public static final int NORMAL = 2;
    /** 角色 UP 池整型值 11：限时，含 50/50 与大保底。 */
    public static final int AVATAR_UP = 11;
    /** 武器/光锥 UP 池整型值 12：规则同角色 UP，UP 列表为武器。 */
    public static final int WEAPON_UP = 12;

    /**
     * 将 Banners.json 的 gachaType 字符串映射为上述整型常量。
     * 未识别或 null 返回 0，上层可跳过脏配置。
     */
    public static int fromGachaTypeString(String s) {
        if (s == null) { // JSON 缺字段或显式 null
            return 0; // 0 表示未知类型，加载时可忽略
        }
        switch (s) { // 与配置文件约定的字符串一一对应
            case "Newbie": // 新手池配置名
                return NEWBIE; // 映射为 1
            case "Normal": // 常驻池配置名
                return NORMAL; // 映射为 2
            case "AvatarUp": // 角色 UP 池配置名（无空格驼峰）
                return AVATAR_UP; // 映射为 11
            case "WeaponUp": // 武器 UP 池配置名
                return WEAPON_UP; // 映射为 12
            default: // 新增类型尚未在此注册
                return 0; // 未知字符串，避免抛异常
        }
    }
}
