package cn.itcast.demo.mylunarcore.rogue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 模拟宇宙程序化地图：基于 {@code data/RogueMapGen.json} 房间池 + 种子生成每局拓扑。
 */
@Service
public class RogueMapGenerator {

    public record RoomNode(int roomId, String type, List<Integer> nextRoomIds) {}

    public record GeneratedMap(long seed, int floors, List<List<RoomNode>> layers) {}

    private final ObjectMapper objectMapper;
    private final Map<String, List<Integer>> poolsByType = new HashMap<>();

    public RogueMapGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        try {
            Path p = Path.of("data/RogueMapGen.json");
            if (!Files.exists(p)) {
                return;
            }
            JsonNode root = objectMapper.readTree(Files.readString(p));
            poolsByType.clear();
            root.fields().forEachRemaining(e -> {
                List<Integer> ids = new ArrayList<>();
                e.getValue().forEach(n -> ids.add(n.asInt()));
                poolsByType.put(e.getKey(), ids);
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * @param seed       局内种子；相同种子可复现
     * @param floorCount 层数
     * @param roomsPerFloor 每层房间数（含起点/终点）
     */
    public GeneratedMap generate(long seed, int floorCount, int roomsPerFloor) {
        Random rng = new Random(seed);
        int floors = Math.max(1, Math.min(12, floorCount));
        int width = Math.max(3, Math.min(7, roomsPerFloor));
        List<List<RoomNode>> layers = new ArrayList<>();
        int nextId = 1;
        for (int f = 0; f < floors; f++) {
            List<RoomNode> layer = new ArrayList<>();
            List<Integer> roomIds = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                roomIds.add(nextId++);
            }
            for (int i = 0; i < width; i++) {
                String type = pickType(f, i, width, rng);
                int template = pickTemplate(type, rng);
                List<Integer> next = new ArrayList<>();
                if (f < floors - 1) {
                    // 连接到下层 1～2 个房间
                    int links = 1 + rng.nextInt(2);
                    for (int k = 0; k < links; k++) {
                        int targetIndex = Math.min(width - 1, Math.max(0, i + rng.nextInt(3) - 1));
                        // 下层 id 尚未分配：用占位偏移，生成完再回填
                        next.add(targetIndex);
                    }
                }
                layer.add(new RoomNode(roomIds.get(i), type + ":" + template, next));
            }
            layers.add(layer);
        }
        // 回填下层真实 roomId
        for (int f = 0; f < floors - 1; f++) {
            List<RoomNode> cur = layers.get(f);
            List<RoomNode> nxt = layers.get(f + 1);
            List<RoomNode> rewritten = new ArrayList<>();
            for (RoomNode node : cur) {
                List<Integer> realNext = new ArrayList<>();
                for (Integer idx : node.nextRoomIds()) {
                    realNext.add(nxt.get(Math.min(nxt.size() - 1, Math.max(0, idx))).roomId());
                }
                rewritten.add(new RoomNode(node.roomId(), node.type(), List.copyOf(realNext)));
            }
            layers.set(f, rewritten);
        }
        return new GeneratedMap(seed, floors, layers);
    }

    public Map<String, Object> toClientMap(GeneratedMap map) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seed", map.seed());
        out.put("floors", map.floors());
        List<Map<String, Object>> layers = new ArrayList<>();
        for (List<RoomNode> layer : map.layers()) {
            List<Map<String, Object>> rooms = new ArrayList<>();
            for (RoomNode r : layer) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("roomId", r.roomId());
                m.put("type", r.type());
                m.put("next", r.nextRoomIds());
                rooms.add(m);
            }
            layers.add(Map.of("rooms", rooms));
        }
        out.put("layers", layers);
        return out;
    }

    private String pickType(int floor, int index, int width, Random rng) {
        if (index == 0 && floor == 0) {
            return "start";
        }
        if (index == width / 2 && floor > 0 && floor % 3 == 0) {
            return "elite";
        }
        if (index == width - 1 && floor == width % 2) {
            return "event";
        }
        String[] types = {"combat", "combat", "event", "shop", "respite", "blessing"};
        return types[rng.nextInt(types.length)];
    }

    private int pickTemplate(String type, Random rng) {
        // RogueMapGen.json 的 key 为房间类型编号；这里按哈希映射到池
        if (poolsByType.isEmpty()) {
            return 100000 + rng.nextInt(900000);
        }
        List<String> keys = new ArrayList<>(poolsByType.keySet());
        Collections.sort(keys);
        String key = keys.get(Math.floorMod(type.hashCode(), keys.size()));
        List<Integer> pool = poolsByType.get(key);
        if (pool == null || pool.isEmpty()) {
            return 100000 + rng.nextInt(900000);
        }
        return pool.get(rng.nextInt(pool.size()));
    }
}
