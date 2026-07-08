// 道具 JSON 解析工具所在包
package cn.itcast.demo.mylunarcore.item;

// 道具系统 Protobuf（SubAffix）
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
// Jackson JSON 树节点
import com.fasterxml.jackson.databind.JsonNode;
// Jackson JSON 解析器
import com.fasterxml.jackson.databind.ObjectMapper;

// 可变数组列表
import java.util.ArrayList;
// 不可变空集合工厂
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * 解析 {@code game_item.sub_affixes} 字段 JSON 为协议 {@link ItemSystemProto.SubAffix} 列表。
 * <p>期望结构（容错）：{@code [{ "affix_id": 1, "count": 2, "step": 3 }, ...]}</p>
 */
public class ItemJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 复用 JSON 解析器

    /**
     * 将 sub_affixes JSON 字符串解析为 SubAffix 列表；空或非法时返回空列表。
     */
    public List<ItemSystemProto.SubAffix> parseSubAffixes(String subAffixesJson) { // 解析副词条 JSON
        if (subAffixesJson == null || subAffixesJson.trim().isEmpty()) { // 空输入
            return Collections.emptyList(); // 返回空列表
        }
        try { // 捕获 JSON 解析异常
            JsonNode arr = MAPPER.readTree(subAffixesJson); // 解析为 JSON 数组节点
            if (arr == null || !arr.isArray()) { // 非数组结构
                return Collections.emptyList(); // 无法识别则返回空
            }
            List<ItemSystemProto.SubAffix> out = new ArrayList<>(); // 协议副词条列表
            for (JsonNode node : arr) { // 遍历数组元素
                if (node == null || node.isNull()) { // 跳过空节点
                    continue; // 继续下一元素
                }
                int affixId = parseInt(node, "affix_id", "affixId"); // 读取词条 ID（兼容两种键名）
                int count = parseInt(node, "count"); // 读取词条计数
                int step = parseInt(node, "step"); // 读取词条阶数
                if (affixId <= 0) { // 无效词条 ID
                    continue; // 跳过无效条目
                }
                out.add(ItemSystemProto.SubAffix.newBuilder() // 组装单条副词条
                        .setAffixId(affixId) // 写入词条 ID
                        .setCount(Math.max(0, count)) // 写入非负计数
                        .setStep(Math.max(0, step)) // 写入非负阶数
                        .build()); // 完成单条构建
            }
            return out; // 返回解析结果
        } catch (Exception e) { // JSON 格式错误或字段异常
            return Collections.emptyList(); // 降级为空列表
        }
    }

    /**
     * 从 JSON 节点按多个候选键名读取整数值；均不存在或非法时返回 0。
     */
    private int parseInt(JsonNode node, String... keys) { // 灵活键名整型解析
        for (String k : keys) { // 依次尝试候选键名
            JsonNode v = node.get(k); // 读取对应字段节点
            if (v != null && !v.isNull()) { // 字段存在且非 null
                if (v.isNumber()) { // 数值类型
                    return v.asInt(); // 直接转 int
                }
                if (v.isTextual()) { // 字符串类型
                    try { // 尝试字符串转 int
                        return Integer.parseInt(v.asText()); // 解析成功则返回
                    } catch (Exception ignore) { // 解析失败
                        return 0; // 返回默认值 0
                    }
                }
            }
        }
        return 0; // 所有键均无效时返回 0
    }
}
