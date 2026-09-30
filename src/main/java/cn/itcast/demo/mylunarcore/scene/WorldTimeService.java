package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局箱庭时间：Tick 驱动预设时段（黎明/正午/黄昏/深夜），内存开销极小。
 */
@Service
public class WorldTimeService {

    public enum Period {
        DAWN(1, "dawn", 6 * 60_000L),
        NOON(2, "noon", 8 * 60_000L),
        DUSK(3, "dusk", 6 * 60_000L),
        NIGHT(4, "night", 8 * 60_000L);

        private final int wire;
        private final String skybox;
        private final long durationMs;

        Period(int wire, String skybox, long durationMs) {
            this.wire = wire;
            this.skybox = skybox;
            this.durationMs = durationMs;
        }

        public int wire() {
            return wire;
        }

        public String skybox() {
            return skybox;
        }

        public long durationMs() {
            return durationMs;
        }

        public static Period fromWire(int wire) {
            for (Period p : values()) {
                if (p.wire == wire) {
                    return p;
                }
            }
            return NOON;
        }

        public Period next() {
            return switch (this) {
                case DAWN -> NOON;
                case NOON -> DUSK;
                case DUSK -> NIGHT;
                case NIGHT -> DAWN;
            };
        }
    }

    public record Snapshot(long worldTimeMs, Period period, long periodElapsedMs, long periodDurationMs) {}

    private final AtomicLong worldTimeMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicReference<Period> period = new AtomicReference<>(Period.NOON);
    private final AtomicLong periodStartedAt = new AtomicLong(System.currentTimeMillis());
    private final GameSessionManager sessionManager;
    private final ObjectProvider<WorldTimePeriodListener> periodListeners;

    public WorldTimeService(GameSessionManager sessionManager,
                            ObjectProvider<WorldTimePeriodListener> periodListeners) {
        this.sessionManager = sessionManager;
        this.periodListeners = periodListeners;
    }

    /** 兼容旧测试：无时段监听。 */
    public WorldTimeService(GameSessionManager sessionManager) {
        this(sessionManager, new ObjectProvider<>() {
            @Override
            public WorldTimePeriodListener getObject(Object... args) {
                return null;
            }

            @Override
            public WorldTimePeriodListener getObject() {
                return null;
            }

            @Override
            public WorldTimePeriodListener getIfAvailable() {
                return null;
            }

            @Override
            public WorldTimePeriodListener getIfUnique() {
                return null;
            }

            @Override
            public java.util.stream.Stream<WorldTimePeriodListener> stream() {
                return java.util.stream.Stream.empty();
            }

            @Override
            public java.util.stream.Stream<WorldTimePeriodListener> orderedStream() {
                return java.util.stream.Stream.empty();
            }
        });
    }

    public Snapshot snapshot() {
        Period p = period.get();
        long elapsed = Math.max(0, System.currentTimeMillis() - periodStartedAt.get());
        return new Snapshot(worldTimeMs.get(), p, Math.min(elapsed, p.durationMs()), p.durationMs());
    }

    /** 每日任务等玩法挂钩：是否处于指定时段（any/空=全天）。 */
    public boolean matchesPeriodFilter(String filter) {
        if (filter == null || filter.isBlank() || "any".equalsIgnoreCase(filter)) {
            return true;
        }
        return period.get().skybox().equalsIgnoreCase(filter.trim());
    }

    public Period currentPeriod() {
        return period.get();
    }

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        long now = System.currentTimeMillis();
        worldTimeMs.set(now);
        Period cur = period.get();
        long elapsed = now - periodStartedAt.get();
        if (elapsed < cur.durationMs()) {
            return;
        }
        Period next = cur.next();
        period.set(next);
        periodStartedAt.set(now);
        broadcast(next);
        notifyPeriodListeners(cur, next);
    }

    /** 测试/运维：强制切到指定时段并触发监听与广播。 */
    public void forcePeriod(Period next) {
        if (next == null) {
            return;
        }
        Period prev = period.getAndSet(next);
        periodStartedAt.set(System.currentTimeMillis());
        broadcast(next);
        if (prev != next) {
            notifyPeriodListeners(prev, next);
        }
    }

    private void notifyPeriodListeners(Period previous, Period next) {
        periodListeners.orderedStream().forEach(listener -> {
            try {
                listener.onPeriodChanged(previous, next);
            } catch (Exception ignored) {
                // 单个作息监听失败不影响天空盒广播
            }
        });
    }

    private void broadcast(Period next) {
        SceneSystemProto.WorldTimeScNotify notify = SceneSystemProto.WorldTimeScNotify.newBuilder()
                .setWorldTimeMs(worldTimeMs.get())
                .setPeriod(toProto(next))
                .setSkyboxPreset(next.skybox())
                .setTransitionMs(2500)
                .build();
        GamePacket packet = new GamePacket(CmdIds.WORLD_TIME_SC_NOTIFY, notify.toByteArray());
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session != null) {
                session.send(packet);
            }
        }
    }

    public static SceneSystemProto.WorldTimePeriod toProto(Period p) {
        SceneSystemProto.WorldTimePeriod mapped = SceneSystemProto.WorldTimePeriod.forNumber(p.wire());
        return mapped == null ? SceneSystemProto.WorldTimePeriod.NOON : mapped;
    }
}
