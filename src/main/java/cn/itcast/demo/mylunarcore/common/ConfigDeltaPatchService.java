package cn.itcast.demo.mylunarcore.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置 Delta 增量补丁：按 JSON Path 做指针级替换，避免热更时全量反序列化导致 CPU/GC 毛刺。
 */
@Service
public class ConfigDeltaPatchService {

    private final ObjectMapper mapper = new ObjectMapper();
    /** configKey → 当前生效 JSON 树（可被指针替换）。 */
    private final ConcurrentHashMap<String, JsonNode> snapshots = new ConcurrentHashMap<>();

    public void putSnapshot(String configKey, String json) {
        try {
            snapshots.put(configKey, mapper.readTree(json == null ? "{}" : json));
        } catch (Exception e) {
            snapshots.put(configKey, mapper.createObjectNode());
        }
    }

    public JsonNode getSnapshot(String configKey) {
        return snapshots.get(configKey);
    }

    /**
     * 应用增量补丁：paths 形如 {"banners[0].cost": 160, "pity.max": 90}。
     * 返回应用后的完整 JSON 字符串。
     */
    public String applyDelta(String configKey, Map<String, Object> pathValues) {
        ObjectNode mutable = (ObjectNode) snapshots.compute(configKey, (k, existing) -> {
            if (existing instanceof ObjectNode on) {
                return on;
            }
            return mapper.createObjectNode();
        });
        if (pathValues != null) {
            for (Map.Entry<String, Object> e : pathValues.entrySet()) {
                setByPath(mutable, e.getKey(), mapper.valueToTree(e.getValue()));
            }
        }
        snapshots.put(configKey, mutable);
        return mutable.toString();
    }

    /** 计算两个 JSON 的浅层路径差异（用于运维 diff / 客户端补丁下发）。 */
    public Map<String, Object> diff(String oldJson, String newJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode a = mapper.readTree(oldJson == null ? "{}" : oldJson);
            JsonNode b = mapper.readTree(newJson == null ? "{}" : newJson);
            collectDiff("", a, b, out);
        } catch (Exception ignored) {
            // ignore
        }
        return out;
    }

    public byte[] toBinarySnapshot(String configKey) {
        JsonNode node = snapshots.getOrDefault(configKey, mapper.createObjectNode());
        try {
            // 预编译二进制快照：长度前缀 + UTF-8 JSON（后续可换 ProtoBuf 同布局）
            byte[] body = mapper.writeValueAsBytes(node);
            byte[] header = ("LC1|" + sha256(body) + "|").getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[header.length + body.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(body, 0, out, header.length, body.length);
            return out;
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private void setByPath(ObjectNode root, String path, JsonNode value) {
        if (path == null || path.isBlank()) {
            return;
        }
        String[] parts = path.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            JsonNode child = cur.get(p);
            if (!(child instanceof ObjectNode)) {
                ObjectNode created = mapper.createObjectNode();
                cur.set(p, created);
                cur = created;
            } else {
                cur = (ObjectNode) child;
            }
        }
        cur.set(parts[parts.length - 1], value);
    }

    private void collectDiff(String prefix, JsonNode a, JsonNode b, Map<String, Object> out) {
        if (a == null || b == null || a.equals(b)) {
            if (a == null && b != null) {
                out.put(prefix.isEmpty() ? "$" : prefix, mapper.convertValue(b, Object.class));
            }
            return;
        }
        if (!a.isObject() || !b.isObject()) {
            out.put(prefix.isEmpty() ? "$" : prefix, mapper.convertValue(b, Object.class));
            return;
        }
        Iterator<String> names = b.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            String next = prefix.isEmpty() ? name : prefix + "." + name;
            collectDiff(next, a.get(name), b.get(name), out);
        }
    }

    private static String sha256(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (Exception e) {
            return "0";
        }
    }
}
