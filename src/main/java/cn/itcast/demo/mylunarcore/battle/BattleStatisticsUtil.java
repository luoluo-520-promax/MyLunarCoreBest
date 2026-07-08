// 战斗统计工具所在包
package cn.itcast.demo.mylunarcore.battle;

// 战斗系统 Protobuf 统计结构
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
// Jackson JSON 序列化
import com.fasterxml.jackson.databind.ObjectMapper;

// 可变哈希映射
import java.util.HashMap;
// 映射接口
import java.util.Map;

/**
 * 战斗统计转换工具：将 Protobuf {@link BattleSystemProto.BattleStatistics} 序列化为可入库的 JSON 字符串。
 */
public final class BattleStatisticsUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 复用 JSON 序列化器

    private BattleStatisticsUtil() { // 工具类禁止实例化
    }

    /**
     * 把 Protobuf 战斗统计转换成 JSON 字符串；失败或空入参返回 "{}".
     */
    public static String toJson(BattleSystemProto.BattleStatistics statistics) { // Protobuf 统计转 JSON
        if (statistics == null) { // 空入参
            return "{}"; // 返回空 JSON 对象
        }
        Map<String, Object> map = new HashMap<>(); // 构建键值映射供序列化
        map.put("damage_dealt", statistics.getDamageDealt()); // 造成伤害
        map.put("damage_taken", statistics.getDamageTaken()); // 承受伤害
        map.put("turn_count", statistics.getTurnCount()); // 战斗回合数
        map.put("kill_count", statistics.getKillCount()); // 击杀数
        if (statistics.getExtraDataJson() != null && !statistics.getExtraDataJson().trim().isEmpty()) { // 有扩展 JSON
            map.put("extra_data", statistics.getExtraDataJson()); // 原样写入扩展字段
        }
        try { // 序列化为 JSON 字符串
            return MAPPER.writeValueAsString(map); // 成功则返回 JSON
        } catch (Exception e) { // 序列化失败
            return "{}"; // 降级为空 JSON 对象
        }
    }
}
