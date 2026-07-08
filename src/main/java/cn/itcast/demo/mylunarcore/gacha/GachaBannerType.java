// 抽卡卡池类型常量与字符串互转工具所在包：统一协议整型与配置文件字符串两种表示
package cn.itcast.demo.mylunarcore.gacha;

/**
 * 卡池类型常量定义与配置字符串转换工具。
 * <p>协议层与内存索引均使用整型常量（1/2/11/12），而 {@code Banners.json} 使用可读字符串
 * （Newbie/Normal/AvatarUp/WeaponUp）。本类负责二者之间的映射，避免魔法数字散落各处。</p>
 */
public final class GachaBannerType {

    /** 私有构造器：工具类不允许被实例化，防止误 new 占用无意义对象 */
    private GachaBannerType() {}

    public static final int NEWBIE = 1;      // 新手池：通常仅对新账号开放，有独立保底规则
    public static final int NORMAL = 2;      // 常驻池（群星跃迁）：长期开放，累计 300 抽可兑换自选五星
    public static final int AVATAR_UP = 11;  // 角色 UP 池：限时开放，50/50 UP 与大保底机制
    public static final int WEAPON_UP = 12;  // 光锥/武器 UP 池：机制同角色 UP 池，UP 列表为武器模板 id

    /**
     * 将配置文件 {@code gachaType} 字段的字符串值转换为内部整型常量。
     * <p>无法识别的字符串返回 0，调用方（如 {@link GachaConfigService#reload()}）应跳过该条配置，
     * 避免脏数据污染内存索引。</p>
     *
     * @param s 配置 JSON 中的 gachaType 字段值，可为 null
     * @return 对应的整型常量；null 或未知字符串时返回 0
     */
    public static int fromGachaTypeString(String s) {
        if (s == null) { // 配置缺失类型字段时无法归类，返回 0 让上层跳过
            return 0;
        }
        switch (s) { // 与 Banners.json 中约定的类型名一一对应
            case "Newbie": // 新手召集池
                return NEWBIE;
            case "Normal": // 常驻群星跃迁池
                return NORMAL;
            case "AvatarUp": // 限时角色 UP 池
                return AVATAR_UP;
            case "WeaponUp": // 限时光锥 UP 池
                return WEAPON_UP;
            default: // 策划新增但未同步代码的类型名，安全降级为 0
                return 0;
        }
    }
}
