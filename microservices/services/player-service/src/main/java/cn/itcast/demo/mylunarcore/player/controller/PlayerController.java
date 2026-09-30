package cn.itcast.demo.mylunarcore.player.controller;

import cn.itcast.demo.mylunarcore.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家 Profile / Session 独立查询接口（可中心化部署）。
 * <p>当前进程内 ConcurrentHashMap 模拟 Redis 中心化存储；接入 Redis 后替换实现即可，
 * 单体通过 HTTP/Feign 调用本接口，战斗服重启不丢失基础信息。
 */
@RestController
@RequestMapping("/players")
public class PlayerController {

    private final ConcurrentHashMap<Long, Map<String, Object>> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, Object>> sessions = new ConcurrentHashMap<>();

    @GetMapping("/{uid}")
    public ApiResponse<Map<String, Object>> getPlayer(@PathVariable("uid") Long uid) {
        Map<String, Object> profile = profiles.get(uid);
        if (profile == null) {
            profile = defaultProfile(uid);
            profiles.put(uid, profile);
        }
        return ApiResponse.ok(new LinkedHashMap<>(profile));
    }

    @PutMapping("/{uid}/profile")
    public ApiResponse<Map<String, Object>> putProfile(@PathVariable("uid") Long uid,
                                                       @RequestBody Map<String, Object> body) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("uid", uid);
        profile.put("nickname", String.valueOf(body.getOrDefault("nickname", "Trailblazer")));
        profile.put("level", toInt(body.get("level"), 1));
        profile.put("avatarId", toInt(body.get("avatarId"), 0));
        profile.put("service", "player-service");
        profile.put("scaffoldOnly", false);
        profile.put("source", "central-store");
        profile.put("updatedAt", System.currentTimeMillis());
        profiles.put(uid, profile);
        return ApiResponse.ok(profile);
    }

    @PutMapping("/{uid}/session")
    public ApiResponse<Map<String, Object>> putSession(@PathVariable("uid") Long uid,
                                                       @RequestBody Map<String, Object> body) {
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("uid", uid);
        session.put("sessionToken", String.valueOf(body.getOrDefault("sessionToken", "")));
        session.put("nodeId", String.valueOf(body.getOrDefault("nodeId", "")));
        session.put("updatedAt", System.currentTimeMillis());
        sessions.put(uid, session);
        return ApiResponse.ok(session);
    }

    @GetMapping("/{uid}/session")
    public ApiResponse<Map<String, Object>> getSession(@PathVariable("uid") Long uid) {
        Map<String, Object> session = sessions.get(uid);
        if (session == null) {
            return ApiResponse.ok(Map.of("uid", uid, "sessionToken", "", "source", "miss"));
        }
        Map<String, Object> out = new LinkedHashMap<>(session);
        out.put("source", "central-store");
        return ApiResponse.ok(out);
    }

    private static Map<String, Object> defaultProfile(Long uid) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("uid", uid);
        profile.put("nickname", "Trailblazer");
        profile.put("level", 1);
        profile.put("avatarId", 0);
        profile.put("service", "player-service");
        profile.put("scaffoldOnly", false);
        profile.put("source", "default");
        return profile;
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
