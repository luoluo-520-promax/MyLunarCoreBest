// 抽卡卡池静态配置 DTO 所在包：对应 data/Banners.json 中单条 Banner 记录的反序列化结构
package cn.itcast.demo.mylunarcore.gacha;

// Lombok @Data：自动生成 getter/setter、equals、hashCode、toString，便于 Jackson 反序列化与业务层读取
import lombok.Data;

// List：承载 UP 道具模板 id 列表（5 星 / 4 星各一份）
import java.util.List;

/**
 * 单张卡池 Banner 的配置快照。
 * <p>
 * 这是“纯配置对象”，只保存卡池展示与抽取所需的静态信息，不承载任何玩家状态。
 * 数据来源于 {@code data/Banners.json}，每一条记录对应客户端看到的一张卡池页签。
 * </p>
 * <p>
 * 设计上把 Banner 配置拆成独立 DTO，而不是直接复用协议对象或数据库实体，
 * 目的是避免配置层、协议层、持久化层互相耦合：
 * 配置变更只影响此类和装载逻辑，不会反向污染抽卡逻辑或数据库表结构。
 * </p>
 * <p>
 * 该类字段的含义与校验规则由 {@link GachaConfigService} 统一负责；
 * 业务层在使用时应默认配置可能脏、可能缺字段，因此需要空值兜底和时间窗判断。
 * </p>
 */
@Data // 配合 Jackson ObjectMapper 将 JSON 字段映射为本类属性，无需手写样板代码
public class GachaBannerConfig {

    /** Banner 在配置文件中的唯一 id；用于客户端展示、日志定位和热更对比。 */
    private int id; // 对应 Banners.json 中每条记录的 id 字段
    /** 卡池类型字符串（Newbie/Normal/AvatarUp/WeaponUp），由 {@link GachaBannerType} 统一映射成整型常量。 */
    private String gachaType; // 配置可读字符串，加载时转为 1/2/11/12 整型
    /** 卡池开放起始 Unix 秒时间戳；<= 0 表示不限制开始时间。 */
    private long beginTime; // 与当前时间比较判断卡池是否已开放
    /** 卡池开放结束 Unix 秒时间戳；<= 0 表示不限制结束时间。 */
    private long endTime; // 与当前时间比较判断卡池是否已过期
    /** 本池 5 星 UP 道具模板 id 列表；UP 池命中 UP 时从此列表随机抽取。 */
    private List<Integer> rateUpItems5; // 可为 null，抽卡逻辑需空值兜底
    /** 本池 4 星 UP 道具模板 id 列表；出 4 星时优先从此列表随机抽取。 */
    private List<Integer> rateUpItems4; // 可为 null，无 UP 时回退常驻池 4 星列表
    /** 客户端表现参数（特效/镜头/文案占位）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("client_ui_params")
    private cn.itcast.demo.mylunarcore.common.ClientUiParams clientUiParams;
}

