package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服/测试：玩家存档导出与导入（问题复现、迁移）。
 * 全量导出需敏感操作审批票据（{@code approvalTicket}）。
 */
@RestController
@RequestMapping("/api/admin/ops/player-data")
public class PlayerDataExportController {

    public static final String OP_EXPORT_PLAYER = "export_player_data";

    private final PlayerDataRepository playerDataRepository;
    private final HomeBaseService homeBaseService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AdminAuditLogService auditLogService;
    private final SensitiveOpApprovalService approvalService;

    public PlayerDataExportController(PlayerDataRepository playerDataRepository,
                                      HomeBaseService homeBaseService,
                                      JdbcTemplate jdbc,
                                      ObjectMapper objectMapper,
                                      AdminAuditLogService auditLogService,
                                      SensitiveOpApprovalService approvalService) {
        this.playerDataRepository = playerDataRepository;
        this.homeBaseService = homeBaseService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.approvalService = approvalService;
    }

    @GetMapping("/{uid}/export")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> export(@PathVariable long uid,
                                      @RequestParam(required = false) String approvalTicket) throws Exception {
        if (approvalTicket == null || approvalTicket.isBlank()
                || !approvalService.consumeIfApproved(approvalTicket, OP_EXPORT_PLAYER)) {
            return Map.of("ok", false, "retcode", 403,
                    "message", "sensitive export requires approved ticket; POST /api/admin/ops/approvals first");
        }
        PlayerData data = playerDataRepository.loadAllData(uid);
        if (data == null || data.getPlayer() == null) {
            return Map.of("ok", false, "retcode", 1, "message", "player not found");
        }
        Map<String, Object> dump = new HashMap<>();
        dump.put("schemaVersion", 1);
        dump.put("exportedAt", System.currentTimeMillis());
        dump.put("player", data.getPlayer());
        dump.put("avatars", data.getAvatars());
        dump.put("lineups", data.getLineups());
        dump.put("items", data.getItems());
        dump.put("friends", data.getFriends());
        dump.put("challenges", data.getChallenges());
        dump.put("rogues", data.getRogues());
        dump.put("home", homeBaseService.getOrCreate((int) uid));
        try {
            dump.put("dialogueProgress", jdbc.queryForList(
                    "SELECT * FROM dialogue_progress WHERE player_id = ?", uid));
        } catch (Exception e) {
            dump.put("dialogueProgress", List.of());
        }
        try {
            dump.put("cutsceneProgress", jdbc.queryForList(
                    "SELECT * FROM cutscene_progress WHERE player_id = ?", uid));
        } catch (Exception e) {
            dump.put("cutsceneProgress", List.of());
        }
        try {
            dump.put("mails", jdbc.queryForList(
                    "SELECT * FROM mail WHERE player_id = ? ORDER BY id DESC LIMIT 200", uid));
        } catch (Exception e) {
            dump.put("mails", List.of());
        }
        auditLogService.record("system", "player_export", "uid=" + uid, "{\"uid\":" + uid + "}", true);
        return Map.of("ok", true, "data", dump);
    }

    @PostMapping("/{uid}/import")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> importData(@PathVariable long uid,
                                          @RequestBody Map<String, Object> body) throws Exception {
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = body.get("data") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : body;
        if (data.get("player") == null) {
            return Map.of("ok", false, "retcode", 2, "message", "missing player");
        }
        if (dryRun) {
            return Map.of("ok", true, "dryRun", true, "uid", uid,
                    "keys", data.keySet());
        }
        // 保守导入：仅覆盖 currency / stamina / nickname / level（完整子表导入需专用迁移工具）
        @SuppressWarnings("unchecked")
        Map<String, Object> player = (Map<String, Object>) data.get("player");
        if (player != null) {
            jdbc.update("""
                    UPDATE player SET nickname=COALESCE(?, nickname), level=COALESCE(?, level),
                      stamina=COALESCE(?, stamina), currency=COALESCE(?, currency)
                    WHERE uid=?
                    """,
                    player.get("nickname"),
                    player.get("level") == null ? null : ((Number) player.get("level")).intValue(),
                    player.get("stamina") == null ? null : ((Number) player.get("stamina")).intValue(),
                    player.get("currencyJson") != null ? String.valueOf(player.get("currencyJson"))
                            : player.get("currency") == null ? null : String.valueOf(player.get("currency")),
                    uid);
        }
        if (data.get("home") != null) {
            homeBaseService.getOrCreate((int) uid);
        }
        auditLogService.record("system", "player_import", "uid=" + uid, "{\"uid\":" + uid + "}", true);
        return Map.of("ok", true, "uid", uid, "imported", objectMapper.writeValueAsString(data.keySet()));
    }
}
