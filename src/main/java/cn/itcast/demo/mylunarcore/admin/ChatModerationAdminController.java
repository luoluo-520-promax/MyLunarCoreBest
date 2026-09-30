package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.hall.ChatModerationService;
import cn.itcast.demo.mylunarcore.hall.ChatSensitiveWordFilter;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天运营：禁言 / 解禁 / 踢下线 / 敏感词热更。
 */
@RestController
@RequestMapping("/api/admin/chat")
public class ChatModerationAdminController {

    private final ChatModerationService moderationService;
    private final ChatSensitiveWordFilter sensitiveWordFilter;
    private final GameSessionManager sessionManager;

    public ChatModerationAdminController(ChatModerationService moderationService,
                                         ChatSensitiveWordFilter sensitiveWordFilter,
                                         GameSessionManager sessionManager) {
        this.moderationService = moderationService;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.sessionManager = sessionManager;
    }

    @PostMapping("/mute")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> mute(@RequestParam int playerId,
                                    @RequestParam(defaultValue = "3600000") long durationMs,
                                    @RequestParam(defaultValue = "admin") String operator,
                                    @RequestParam(defaultValue = "") String reason) {
        moderationService.mute(playerId, durationMs, operator, reason);
        return ok("muted");
    }

    @PostMapping("/unmute")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> unmute(@RequestParam int playerId,
                                      @RequestParam(defaultValue = "admin") String operator) {
        moderationService.unmute(playerId, operator);
        return ok("unmuted");
    }

    @PostMapping("/kick")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> kick(@RequestParam int playerId,
                                    @RequestParam(defaultValue = "") String reason) {
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null && session.getChannel() != null) {
            session.getChannel().close();
            moderationService.audit(0, playerId, "kick", reason);
            return ok("kicked");
        }
        Map<String, Object> out = ok("offline");
        out.put("message", "player not online");
        return out;
    }

    @PostMapping("/reload-words")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reloadWords() {
        sensitiveWordFilter.reload();
        return ok("reloaded");
    }

    @PostMapping("/report")
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> report(@RequestParam int reporterId,
                                      @RequestParam int targetId,
                                      @RequestParam(defaultValue = "") String content,
                                      @RequestParam(defaultValue = "") String reason) {
        moderationService.report(reporterId, targetId, content, reason);
        return ok("reported");
    }

    private static Map<String, Object> ok(String action) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("action", action);
        return out;
    }
}
