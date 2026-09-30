package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.economy.WalletWalService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务端暂停（Server Freeze）：发版前推送维护公告 → 拒新战斗 → 强制同步落盘 → 清 session_dirty。
 */
@Service
public class ServerFreezeService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ServerFreezeService.class);

    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean acceptingNewBattles = new AtomicBoolean(true);
    private final AtomicReference<String> reason = new AtomicReference<>("");
    private final AtomicReference<Instant> since = new AtomicReference<>(null);
    private final AtomicInteger estimatedMinutes = new AtomicInteger(5);

    private final GameSessionManager sessionManager;
    private final BattleManager battleManager;
    private final BattleSnapshotService battleSnapshotService;
    private final ObjectProvider<WalletWalService> walletWalProvider;
    private final ObjectProvider<MaintenanceModeService> maintenanceProvider;
    private final AlertWebhookNotifier alertWebhookNotifier;

    public ServerFreezeService(GameSessionManager sessionManager,
                               BattleManager battleManager,
                               BattleSnapshotService battleSnapshotService,
                               ObjectProvider<WalletWalService> walletWalProvider,
                               ObjectProvider<MaintenanceModeService> maintenanceProvider,
                               AlertWebhookNotifier alertWebhookNotifier) {
        this.sessionManager = sessionManager;
        this.battleManager = battleManager;
        this.battleSnapshotService = battleSnapshotService;
        this.walletWalProvider = walletWalProvider;
        this.maintenanceProvider = maintenanceProvider;
        this.alertWebhookNotifier = alertWebhookNotifier;
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    public boolean acceptNewBattles() {
        return acceptingNewBattles.get() && !frozen.get();
    }

    /**
     * 进入冻结：推送 ServerMaintenancePush → 拒新战斗 → 同步落盘进行中战斗与钱包 WAL → 清 dirty。
     */
    public synchronized Map<String, Object> freeze(String reasonText, int etaMinutes) {
        frozen.set(true);
        acceptingNewBattles.set(false);
        reason.set(reasonText == null ? "version_update" : reasonText);
        since.set(Instant.now());
        estimatedMinutes.set(Math.max(1, etaMinutes));

        int pushed = pushMaintenanceNotify();
        int synced = forceSyncBattles();
        int dirtyCleared = clearSessionDirty();
        WalletWalService wal = walletWalProvider.getIfAvailable();
        int walFlushed = 0;
        if (wal != null) {
            walFlushed = wal.flushOnce();
            while (wal.flushOnce() > 0) {
                walFlushed++;
            }
        }

        log.warn("SERVER FREEZE ON reason={} etaMin={} pushed={} battlesSynced={} dirtyCleared={} wal={}",
                reason.get(), estimatedMinutes.get(), pushed, synced, dirtyCleared, walFlushed);
        alertWebhookNotifier.notifyWarn("server_freeze",
                reason.get() + " eta=" + estimatedMinutes.get() + "m syncedBattles=" + synced);

        Map<String, Object> out = new LinkedHashMap<>(status());
        out.put("pushed", pushed);
        out.put("battlesSynced", synced);
        out.put("dirtyCleared", dirtyCleared);
        out.put("walFlushed", walFlushed);
        return out;
    }

    /** 冻结后进入完整维护（拒登录），供发布脚本第二阶段调用。 */
    public synchronized Map<String, Object> escalateToMaintenance() {
        MaintenanceModeService m = maintenanceProvider.getIfAvailable();
        Map<String, Object> maint = m == null ? Map.of() : m.enable("freeze:" + reason.get());
        Map<String, Object> out = new LinkedHashMap<>(status());
        out.put("maintenance", maint);
        return out;
    }

    public synchronized Map<String, Object> unfreeze() {
        frozen.set(false);
        acceptingNewBattles.set(true);
        reason.set("");
        since.set(null);
        log.warn("SERVER FREEZE OFF");
        alertWebhookNotifier.notifyWarn("server_freeze_off", "reopen");
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("frozen", frozen.get());
        m.put("acceptingNewBattles", acceptNewBattles());
        m.put("reason", reason.get());
        m.put("since", since.get() == null ? "" : since.get().toString());
        m.put("etaMinutes", estimatedMinutes.get());
        m.put("activeBattles", battleManager.activeUnendedCount());
        return m;
    }

    private int pushMaintenanceNotify() {
        // 轻量 JSON 载荷：客户端按 SERVER_MAINTENANCE_PUSH 跳转公告页
        String payload = "{\"etaMinutes\":" + estimatedMinutes.get()
                + ",\"reason\":\"" + escapeJson(reason.get()) + "\",\"at\":\"" + Instant.now() + "\"}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int n = 0;
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session != null && session.getChannel() != null && session.getChannel().isActive()) {
                session.send(new GamePacket(CmdIds.SERVER_MAINTENANCE_PUSH, bytes));
                n++;
            }
        }
        return n;
    }

    private int forceSyncBattles() {
        int n = 0;
        for (BattleContext ctx : battleManager.snapshotActive()) {
            if (ctx == null || ctx.isEnded()) {
                continue;
            }
            synchronized (ctx.getLock()) {
                battleSnapshotService.save(ctx);
                n++;
            }
        }
        return n;
    }

    private int clearSessionDirty() {
        int n = 0;
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session != null && session.isDirty()) {
                session.clearDirty();
                n++;
            }
        }
        return n;
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
