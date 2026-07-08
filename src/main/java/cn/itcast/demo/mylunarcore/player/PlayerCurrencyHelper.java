// 将数据库中存储的货币 JSON 字符串解析为同步协议使用的整数 Map
package cn.itcast.demo.mylunarcore.player;

// 统一日志门面，按业务分类输出
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类常量，便于过滤同步相关日志
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J 日志接口
import org.slf4j.Logger;

// 不可变空 Map，解析失败或无内容时复用，避免重复分配
import java.util.Collections;

// 可变哈希表，承载解析出的货币键值对
import java.util.HashMap;

// Map 接口，作为方法返回类型
import java.util.Map;

// 正则匹配器，逐段扫描 JSON 中的 id:value 对
import java.util.regex.Matcher;

// 正则模式，描述宽松的 JSON 键值对格式
import java.util.regex.Pattern;

/**
 * 玩家货币 JSON 解析工具。
 * <p>
 * 数据库存储格式示例：{@code {"1":"1000","2":"50"}}，本类将其转为 {@code Map<Integer,Integer>}
 * 供登录响应与 {@link PlayerCoreSyncable} 统一同步共用。
 * </p>
 */
public final class PlayerCurrencyHelper {

    /** 本类专用日志记录器，分类为 BUSINESS_SYNC */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SYNC, PlayerCurrencyHelper.class);

    /**
     * 宽松匹配 JSON 中 {@code "货币类型id":"数量"} 或 {@code 货币类型id:数量} 形态的正则。
     * group(1) 为货币类型 id，group(2) 为数量。
     */
    private static final Pattern PAIR_PATTERN = Pattern.compile("\"?(\\d+)\"?\\s*:\\s*\"?(\\d+)\"?");

    /**
     * 工具类禁止实例化。
     */
    private PlayerCurrencyHelper() {
    }

    /**
     * 将 player.currency 字段的 JSON 文本解析为整型 Map。
     *
     * @param currencyJson 存储于 {@link cn.itcast.demo.mylunarcore.model.PlayerEntity#getCurrencyJson()} 的 JSON 文本，可为 null
     * @return 解析成功的货币 map；输入为空或解析异常时返回不可变空 map
     */
    public static Map<Integer, Integer> parseCurrency(String currencyJson) {
        // 空输入直接返回共享空集合，避免无意义的正则扫描
        if (currencyJson == null || currencyJson.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            Map<Integer, Integer> map = new HashMap<>(); // 存放解析结果
            Matcher matcher = PAIR_PATTERN.matcher(currencyJson); // 对整段 JSON 建立匹配器
            while (matcher.find()) { // 循环提取每一对 id:value
                int key = Integer.parseUnsignedInt(matcher.group(1)); // 货币类型 id（无符号解析，避免负数）
                int value = Integer.parseUnsignedInt(matcher.group(2)); // 货币数量
                map.put(key, value); // 写入结果 map；同 key 后者覆盖前者
            }
            return map; // 返回可变 map 的引用（调用方通常只读）
        } catch (Exception e) {
            log.warn("parseCurrency failed, json={}", currencyJson, e); // 记录异常 JSON 便于排查脏数据
            return Collections.emptyMap(); // 降级为空 map，不阻断登录/同步主流程
        }
    }
}
