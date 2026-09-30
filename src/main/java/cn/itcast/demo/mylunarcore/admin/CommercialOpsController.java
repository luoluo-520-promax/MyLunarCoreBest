package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService;
import cn.itcast.demo.mylunarcore.common.ConfigOverrideHotfixService;
import cn.itcast.demo.mylunarcore.economy.NegativeGrantService;
import cn.itcast.demo.mylunarcore.economy.WalletRollbackSnapshotService;
import cn.itcast.demo.mylunarcore.ops.ServerClockService;
import cn.itcast.demo.mylunarcore.ops.ServerFreezeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 商业化运维增强：热补丁覆写、熔断、时间旅行、Server Freeze、负向发放、钱包快照。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class CommercialOpsController {

    private final ConfigOverrideHotfixService overrideHotfixService;
    private final ActivityCircuitBreakerService circuitBreakerService;
    private final ServerClockService serverClockService;
    private final ServerFreezeService serverFreezeService;
    private final NegativeGrantService negativeGrantService;
    private final WalletRollbackSnapshotService walletRollbackSnapshotService;
    private final ActivityScriptEngine activityScriptEngine;
    private final ActivityVisibilityService activityVisibilityService;

    public CommercialOpsController(ConfigOverrideHotfixService overrideHotfixService,
                                   ActivityCircuitBreakerService circuitBreakerService,
                                   ServerClockService serverClockService,
                                   ServerFreezeService serverFreezeService,
                                   NegativeGrantService negativeGrantService,
                                   WalletRollbackSnapshotService walletRollbackSnapshotService,
                                   ActivityScriptEngine activityScriptEngine,
                                   ActivityVisibilityService activityVisibilityService) {
        this.overrideHotfixService = overrideHotfixService;
        this.circuitBreakerService = circuitBreakerService;
        this.serverClockService = serverClockService;
        this.serverFreezeService = serverFreezeService;
        this.negativeGrantService = negativeGrantService;
        this.walletRollbackSnapshotService = walletRollbackSnapshotService;
        this.activityScriptEngine = activityScriptEngine;
        this.activityVisibilityService = activityVisibilityService;
    }

    // ---- 1/4 Override Hotfix ----
    @PostMapping("/hotfix/override")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> hotfixOverride(@RequestBody Map<String, Object> body) {
        String configId = String.valueOf(body.getOrDefault("configId", ""));
        String reason = String.valueOf(body.getOrDefault("reason", "ops_override"));
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = body.get("paths") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        // 兼容 gacha.base_probability=0.006 扁平写法
        if (paths.isEmpty() && body.containsKey("path")) {
            paths = Map.of(String.valueOf(body.get("path")), body.get("value"));
        }
        return overrideHotfixService.applyOverride(configId, paths, reason);
    }

    @GetMapping("/hotfix/override")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> listOverrides() {
        return overrideHotfixService.snapshot();
    }

    @PostMapping("/hotfix/override/clear")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> clearOverride(@RequestParam(required = false) String configId) {
        return overrideHotfixService.clearOverride(configId);
    }

    // ---- 4 Circuit breaker ----
    @PostMapping("/circuit/trip")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> tripCircuit(@RequestParam(defaultValue = "ops_emergency") String reason) {
        return circuitBreakerService.trip(reason);
    }

    @PostMapping("/circuit/reset")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> resetCircuit() {
        return circuitBreakerService.reset();
    }

    @GetMapping("/circuit")
    public Map<String, Object> circuitStatus() {
        return circuitBreakerService.status();
    }

    // ---- 2 Time Travel ----
    @PostMapping("/time-travel")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> timeTravel(@RequestBody Map<String, Object> body) {
        String at = String.valueOf(body.getOrDefault("at", ""));
        if (at.isBlank() || "reset".equalsIgnoreCase(at) || "null".equalsIgnoreCase(at)) {
            return serverClockService.reset();
        }
        Instant target = Instant.parse(at);
        return serverClockService.travelTo(target, String.valueOf(body.getOrDefault("operator", "admin")));
    }

    @GetMapping("/time-travel")
    public Map<String, Object> timeTravelStatus() {
        return serverClockService.status();
    }

    // ---- 5 Server Freeze ----
    @PostMapping("/freeze")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> freeze(@RequestBody(required = false) Map<String, Object> body) {
        String reason = body == null ? "version_update" : String.valueOf(body.getOrDefault("reason", "version_update"));
        int eta = body != null && body.get("etaMinutes") instanceof Number n ? n.intValue() : 5;
        return serverFreezeService.freeze(reason, eta);
    }

    @PostMapping("/freeze/escalate")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> escalateFreeze() {
        return serverFreezeService.escalateToMaintenance();
    }

    @PostMapping("/freeze/off")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> unfreeze() {
        return serverFreezeService.unfreeze();
    }

    @GetMapping("/freeze")
    public Map<String, Object> freezeStatus() {
        return serverFreezeService.status();
    }

    // ---- 7 Negative grant + wallet snapshot ----
    @PostMapping("/grant/negative")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> negativeGrant(@RequestBody Map<String, Object> body) {
        int uid = body.get("uid") instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(body.get("uid")));
        int itemId = body.get("item_id") instanceof Number n ? n.intValue()
                : body.get("itemId") instanceof Number n2 ? n2.intValue()
                : Integer.parseInt(String.valueOf(body.getOrDefault("item_id", body.get("itemId"))));
        long count = body.get("count") instanceof Number n ? n.longValue()
                : Long.parseLong(String.valueOf(body.get("count")));
        boolean currency = Boolean.parseBoolean(String.valueOf(body.getOrDefault("currency", false)));
        String reason = String.valueOf(body.getOrDefault("reason", "ops_negative_grant"));
        return negativeGrantService.grant(uid, itemId, count, currency, reason);
    }

    @PostMapping("/wallet/snapshot")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> walletSnapshot(@RequestBody(required = false) Map<String, Object> body) {
        String label = body == null ? "" : String.valueOf(body.getOrDefault("label", ""));
        if (body != null && body.get("uid") instanceof Number n) {
            return walletRollbackSnapshotService.snapshotPlayer(n.intValue(), label);
        }
        return walletRollbackSnapshotService.createSnapshot(label);
    }

    @PostMapping("/wallet/rollback")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> walletRollback(@RequestBody Map<String, Object> body) {
        String label = String.valueOf(body.getOrDefault("label", ""));
        boolean dryRun = Boolean.parseBoolean(String.valueOf(body.getOrDefault("dryRun", "true")));
        String confirm = String.valueOf(body.getOrDefault("confirm", ""));
        if (!dryRun && !"ROLLBACK_WALLET".equals(confirm)) {
            return Map.of("ok", false, "message", "confirm=ROLLBACK_WALLET required when dryRun=false");
        }
        return walletRollbackSnapshotService.rollbackTo(label, dryRun);
    }

    @GetMapping("/wallet/snapshots")
    public Map<String, Object> listWalletSnapshots() {
        return walletRollbackSnapshotService.listSnapshots();
    }

    // ---- scripts + QA ----
    @PostMapping("/scripts/reload")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reloadScripts() {
        int n = activityScriptEngine.reloadAll();
        Map<String, Object> out = new LinkedHashMap<>(activityScriptEngine.snapshot());
        out.put("ok", true);
        out.put("loaded", n);
        return out;
    }

    @GetMapping("/scripts")
    public Map<String, Object> scripts() {
        return activityScriptEngine.snapshot();
    }

    @GetMapping("/visibility")
    public Map<String, Object> visibility() {
        return activityVisibilityService.status();
    }

    @PostMapping("/visibility/qa")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> markQa(@RequestBody Map<String, Object> body) {
        long uid = body.get("uid") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(body.get("uid")));
        boolean qa = Boolean.parseBoolean(String.valueOf(body.getOrDefault("qa", true)));
        activityVisibilityService.markQa(uid, qa);
        return activityVisibilityService.status();
    }
}
