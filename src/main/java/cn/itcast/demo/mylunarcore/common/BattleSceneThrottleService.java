package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU 时间片租约：BattleTick 队列积压超过阈值时，向场景侧下发 {@link CmdIds#THROTTLE_SC_NOTIFY}，
 * 降低 NPC 刷新率（默认 5Hz→2Hz），保战斗流畅。
 */
@Service
public class BattleSceneThrottleService {

    public static final int DEFAULT_SCENE_HZ = 5;
    public static final int THROTTLED_SCENE_HZ = 2;
    public static final long BACKLOG_THRESHOLD_MS = 50L;
    public static final long THROTTLE_HOLD_MS = 3_000L;

    private final GameSessionManager sessionManager;
    private final AtomicInteger currentSceneHz = new AtomicInteger(DEFAULT_SCENE_HZ);
    private final AtomicLong throttleUntilMs = new AtomicLong(0);
    private final AtomicLong lastBattleEnqueueAt = new AtomicLong(0);
    private final AtomicLong lastBattleStartAt = new AtomicLong(0);
    private volatile long lastObservedLagMs;

    public BattleSceneThrottleService(GameSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public void markBattleEnqueued(long nowMs) {
        lastBattleEnqueueAt.set(nowMs);
    }

    public void markBattleStarted(long nowMs) {
        lastBattleStartAt.set(nowMs);
        long enq = lastBattleEnqueueAt.get();
        if (enq > 0) {
            lastObservedLagMs = Math.max(0, nowMs - enq);
            if (lastObservedLagMs > BACKLOG_THRESHOLD_MS) {
                engageThrottle(nowMs, lastObservedLagMs);
            }
        }
    }

    public void engageThrottle(long nowMs, long lagMs) {
        throttleUntilMs.set(nowMs + THROTTLE_HOLD_MS);
        currentSceneHz.set(THROTTLED_SCENE_HZ);
        broadcastThrottle(THROTTLED_SCENE_HZ, "battle_backlog", lagMs, nowMs + THROTTLE_HOLD_MS);
    }

    public int resolveSceneHz(long nowMs) {
        if (nowMs >= throttleUntilMs.get()) {
            currentSceneHz.set(DEFAULT_SCENE_HZ);
            return DEFAULT_SCENE_HZ;
        }
        return currentSceneHz.get();
    }

    public boolean isThrottled(long nowMs) {
        return nowMs < throttleUntilMs.get();
    }

    public long getLastObservedLagMs() {
        return lastObservedLagMs;
    }

    private void broadcastThrottle(int hz, String reason, long lagMs, long untilMs) {
        if (sessionManager == null) {
            return;
        }
        SceneSystemProto.ThrottleScNotify notify = SceneSystemProto.ThrottleScNotify.newBuilder()
                .setSceneTickHz(hz)
                .setReason(reason == null ? "" : reason)
                .setBattleQueueLagMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, lagMs)))
                .setUntilMs(untilMs)
                .build();
        byte[] bytes = notify.toByteArray();
        for (GameSession session : sessionManager.snapshotSessions()) {
            Channel ch = session.getChannel();
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new GamePacket(CmdIds.THROTTLE_SC_NOTIFY, bytes));
            }
        }
    }
}
