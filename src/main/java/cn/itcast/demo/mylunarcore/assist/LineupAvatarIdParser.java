package cn.itcast.demo.mylunarcore.assist; // 队伍编组角色 ID 解析器所在包

import com.fasterxml.jackson.databind.JsonNode; // 使用 JSON 树遍历提取字段
import com.fasterxml.jackson.databind.ObjectMapper; // 复用解析器避免频繁创建

import java.util.ArrayList; // 用动态列表收集角色 ID
import java.util.List; // 以只读列表返回结果

/**
 * 队伍编组中角色 ID 的提取器。
 * <p>
 * 该工具会从前端传来的编组 JSON 里尽量找出 avatarId、id 或数组中的整型角色 ID，
 * 用于助手侧做阵容建议、使用率统计和养成判断。
 */
public final class LineupAvatarIdParser { // 工具类不持有实例状态
    /**
     * 复用 Jackson 解析器
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * 禁止外部实例化
     */
    private LineupAvatarIdParser() {
    } // 私有构造结束
    /**
     * 从 JSON 中提取角色 ID
     */
    public static List<Integer> parse(String avatarsJson) {
        if (avatarsJson == null || avatarsJson.isBlank()) { // 空文本不包含可解析内容
            return List.of(); // 直接返回空结果
        } // 空输入判断结束
        try { // 解析失败时静默降级
            JsonNode root = MAPPER.readTree(avatarsJson); // 将输入解析为 JSON 树
            List<Integer> ids = new ArrayList<>(); // 收集提取到的角色 ID
            collect(root, ids); // 递归扫描所有可疑字段
            return List.copyOf(ids); // 返回去重后的只读列表
        } catch (Exception e) { // 非法 JSON 不影响主流程
            return List.of(); // 解析失败时返回空列表
        } // 异常处理结束
    } // parse 结束
    /**
     * 递归提取整型角色 ID
     */
    private static void collect(JsonNode node, List<Integer> out) {
        if (node == null || node.isNull()) { // 空节点没有可用数据
            return; // 直接结束递归
        } // 空节点判断结束
        if (node.isArray()) { // 队伍成员常以数组表示
            for (JsonNode child : node) { // 逐个扫描数组元素
                collect(child, out); // 继续向下提取 ID
            } // 数组遍历结束
            return; // 数组节点处理完成
        } // 数组判断结束
        if (node.isIntegralNumber()) { // 纯数字节点可直接当作角色 ID
            int id = node.asInt(); // 读取整型值
            if (id > 0 && !out.contains(id)) { // 正整数且尚未收录才加入
                out.add(id); // 记录角色 ID
            } // 去重判断结束
            return; // 数字节点处理完成
        } // 数字判断结束
        if (node.isObject()) { // 对象节点里可能藏着角色字段
            if (node.has("avatarId") && node.get("avatarId").isIntegralNumber()) { // 优先识别 avatarId
                int id = node.get("avatarId").asInt(); // 读取 avatarId 值
                if (id > 0 && !out.contains(id)) { // 过滤非正数与重复项
                    out.add(id); // 记录 avatarId
                } // avatarId 去重结束
            } // avatarId 判断结束
            if (node.has("id") && node.get("id").isIntegralNumber() && !node.has("avatarId")) { // 兼容旧结构的 id 字段
                int id = node.get("id").asInt(); // 读取旧字段角色 ID
                if (id > 0 && !out.contains(id)) { // 正值且未收录才加入
                    out.add(id); // 记录旧结构 ID
                } // 旧结构去重结束
            } // 旧结构判断结束
            if (node.has("avatars")) { // 继续扫描队伍成员字段
                collect(node.get("avatars"), out); // 递归提取成员 ID
            } // avatars 字段结束
            if (node.has("avatarIds")) { // 兼容数组形式的角色 ID 字段
                collect(node.get("avatarIds"), out); // 递归处理 avatarIds
            } // avatarIds 字段结束
            if (node.has("members")) { // 继续扫描更深层成员集合
                collect(node.get("members"), out); // 递归处理 members
            } // members 字段结束
        } // 对象节点判断结束
    } // collect 结束
}
