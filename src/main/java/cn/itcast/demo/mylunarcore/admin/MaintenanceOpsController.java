package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.ops.AlertWebhookNotifier;
import cn.itcast.demo.mylunarcore.ops.BattleLogSampler;
import cn.itcast.demo.mylunarcore.ops.MaintenanceModeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 维护模式 / 告警 / 日志采样运维接口。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class MaintenanceOpsController {

    private final MaintenanceModeService maintenanceModeService;
    private final AlertWebhookNotifier alertWebhookNotifier;
    private final BattleLogSampler battleLogSampler;

    public MaintenanceOpsController(MaintenanceModeService maintenanceModeService,
                                    AlertWebhookNotifier alertWebhookNotifier,
                                    BattleLogSampler battleLogSampler) {
        this.maintenanceModeService = maintenanceModeService;
        this.alertWebhookNotifier = alertWebhookNotifier;
        this.battleLogSampler = battleLogSampler;
    }

    @GetMapping("/maintenance")
    public Map<String, Object> status() {
        return maintenanceModeService.status();
    }

    @PostMapping("/maintenance/enable")
    public Map<String, Object> enable(@RequestParam(defaultValue = "scheduled") String reason) {
        return maintenanceModeService.enable(reason);
    }

    @PostMapping("/maintenance/disable")
    public Map<String, Object> disable() {
        return maintenanceModeService.disable();
    }

    @GetMapping("/alert-webhook")
    public Map<String, Object> alertStatus() {
        return alertWebhookNotifier.status();
    }

    @PostMapping("/alert-webhook/test")
    public Map<String, Object> alertTest(@RequestBody(required = false) Map<String, Object> body) {
        String title = body == null ? "test" : String.valueOf(body.getOrDefault("title", "test"));
        String detail = body == null ? "ping" : String.valueOf(body.getOrDefault("detail", "ping"));
        alertWebhookNotifier.notifyWarn(title, detail);
        return Map.of("ok", true, "status", alertWebhookNotifier.status());
    }

    @GetMapping("/log-sampling")
    public Map<String, Object> logSampling() {
        battleLogSampler.refreshAllowlist();
        return Map.of(
                "dropped", battleLogSampler.droppedCount(),
                "allowed", battleLogSampler.allowedCount());
    }
}
