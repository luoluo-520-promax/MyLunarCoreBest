// 抽卡卡池静态配置 DTO 所在包：对应 data/Banners.json 中单条 Banner 记录的反序列化结构
package cn.itcast.demo.mylunarcore.gacha;

// Lombok @Data：自动生成 getter/setter、equals、hashCode、toString，便于 Jackson 反序列化与业务层读取
import lombok.Data;

// List：承载 UP 道具模板 id 列表（5 星 / 4 星各一份）
import java.util.List;

/**
 * 单张卡池 Banner 的配置快照。
 * <p>数据来源于 {@code data/Banners.json}，描述一张卡池的开放时间窗、类型标识以及 UP 道具列表。
 * 由 {@link GachaConfigService} 加载并按类型索引，供 {@link GachaNettyService} 查询展示与抽卡概率计算使用。</p>
 */
@Data // 配合 Jackson ObjectMapper 将 JSON 字段映射为本类属性，无需手写样板代码
public class GachaBannerConfig {

    private int id;                      // Banner 在配置文件中的唯一 id，下发给客户端用于标识当前生效卡池
    private String gachaType;            // 卡池类型字符串（Newbie/Normal/AvatarUp/WeaponUp），经 GachaBannerType 转为整型常量
    private long beginTime;              // 卡池开放起始 Unix 秒时间戳；<= 0 表示不限制开始时间
    private long endTime;                // 卡池开放结束 Unix 秒时间戳；<= 0 表示不限制结束时间
    private List<Integer> rateUpItems5;  // 本池 5 星 UP 道具模板 id 列表，UP 池命中 UP 时从此列表随机抽取
    private List<Integer> rateUpItems4;  // 本池 4 星 UP 道具模板 id 列表，出 4 星时优先从此列表随机抽取
}
