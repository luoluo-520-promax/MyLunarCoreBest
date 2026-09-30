package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 维护模式：拒绝新登录、保留 Redis 战场快照，维护结束后玩家可无感重连回战斗。
 */
@Service
public class MaintenanceModeService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, MaintenanceModeService.class);

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>("");
    private final AtomicReference<Instant> since = new AtomicReference<>(null);
    private final BattleManager battleManager;
    private final BattleSnapshotService battleSnapshotService;
    private final AlertWebhookNotifier alertWebhookNotifier;

    public MaintenanceModeService(BattleManager battleManager,
                                  BattleSnapshotService battleSnapshotService,
                                  AlertWebhookNotifier alertWebhookNotifier) {
        this.battleManager = battleManager;
        this.battleSnapshotService = battleSnapshotService;
        this.alertWebhookNotifier = alertWebhookNotifier;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public synchronized Map<String, Object> enable(String reasonText) {
        enabled.set(true);
        reason.set(reasonText == null ? "maintenance" : reasonText);
        since.set(Instant.now());
        // 停机前确保进行中战斗已落 Redis，便于重启后 hydrate
        int aborted = battleManager.abortAllForShutdown();
        log.warn("maintenance ON reason={} abortedBattles={}", reason.get(), aborted);
        alertWebhookNotifier.notifyWarn("maintenance_on", reason.get() + " aborted=" + aborted);
        return status();
    }

    public synchronized Map<String, Object> disable() {
        enabled.set(false);
        log.warn("maintenance OFF");
        alertWebhookNotifier.notifyWarn("maintenance_off", "reopen");
        return status();
    }

    /** 登录门禁：维护中拒绝（内部运维白名单可另开）。 */
    public boolean rejectLogin() {
        return enabled.get();
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", enabled.get(),
                "reason", reason.get() == null ? "" : reason.get(),
                "since", since.get() == null ? "" : since.get().toString(),
                "snapshotEnabled", battleSnapshotService != null);
    }
}
