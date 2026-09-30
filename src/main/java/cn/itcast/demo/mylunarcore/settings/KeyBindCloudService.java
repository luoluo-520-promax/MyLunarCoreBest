package cn.itcast.demo.mylunarcore.settings;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 键位云存档：优先 Redis，失败回退内存，换设备登录时下发。
 */
@Service
public class KeyBindCloudService {

    public record Bind(String actionId, String keyCode, String deviceType, int scale, float posX, float posY) {
        public Bind {
            actionId = actionId == null ? "" : actionId;
            keyCode = keyCode == null ? "" : keyCode;
            deviceType = deviceType == null || deviceType.isBlank() ? "pc" : deviceType;
            scale = scale <= 0 ? 100 : Math.min(200, Math.max(50, scale));
        }
    }

    private static final String KEY_PREFIX = "keybind:";
    private final StringRedisTemplate redis;
    private final Map<String, List<Bind>> memory = new ConcurrentHashMap<>();

    public KeyBindCloudService(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    public List<Bind> save(int playerId, String deviceType, List<Bind> binds) {
        if (playerId <= 0 || binds == null || binds.isEmpty()) {
            return List.of();
        }
        String device = deviceType == null || deviceType.isBlank() ? "pc" : deviceType;
        List<Bind> copy = new ArrayList<>();
        for (Bind b : binds) {
            if (b == null || b.actionId().isBlank()) {
                continue;
            }
            copy.add(new Bind(b.actionId(), b.keyCode(), device, b.scale(), b.posX(), b.posY()));
        }
        memory.put(memKey(playerId, device), List.copyOf(copy));
        if (redis != null) {
            try {
                StringBuilder sb = new StringBuilder();
                for (Bind b : copy) {
                    sb.append(b.actionId()).append('\u001f')
                            .append(b.keyCode()).append('\u001f')
                            .append(b.scale()).append('\u001f')
                            .append(b.posX()).append('\u001f')
                            .append(b.posY()).append('\n');
                }
                redis.opsForValue().set(KEY_PREFIX + playerId + ":" + device, sb.toString(), Duration.ofDays(90));
            } catch (Exception ignored) {
                // 仅内存
            }
        }
        return List.copyOf(copy);
    }

    public List<Bind> load(int playerId, String deviceType) {
        String device = deviceType == null || deviceType.isBlank() ? "pc" : deviceType;
        List<Bind> mem = memory.get(memKey(playerId, device));
        if (mem != null && !mem.isEmpty()) {
            return mem;
        }
        if (redis != null) {
            try {
                String raw = redis.opsForValue().get(KEY_PREFIX + playerId + ":" + device);
                if (raw != null && !raw.isBlank()) {
                    List<Bind> parsed = parse(raw, device);
                    memory.put(memKey(playerId, device), parsed);
                    return parsed;
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return List.of();
    }

    private static List<Bind> parse(String raw, String device) {
        List<Bind> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split("\u001f", -1);
            if (p.length < 2) {
                continue;
            }
            int scale = p.length > 2 ? parseInt(p[2], 100) : 100;
            float x = p.length > 3 ? parseFloat(p[3]) : 0f;
            float y = p.length > 4 ? parseFloat(p[4]) : 0f;
            out.add(new Bind(p[0], p[1], device, scale, x, y));
        }
        return List.copyOf(out);
    }

    private static int parseInt(String s, int dft) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return dft;
        }
    }

    private static float parseFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return 0f;
        }
    }

    private static String memKey(int playerId, String device) {
        return playerId + ":" + device;
    }
}
