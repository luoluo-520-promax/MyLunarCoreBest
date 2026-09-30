package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * 战斗审计：热路径仅入队，后台批量刷日志，避免阻塞 Tick。
 */
@Component
public class BattleAuditService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleAuditService.class);

    private enum Kind { FIGHT_END, DAMAGE, REJECT }

    private record AuditEvent(Kind kind, long battleId, int playerId, int endStatus, int clientEndStatus,
                              String reason, int skillId, int targetEntityId, int hpBefore, int hpAfter, int delta) {
    }

    private final BlockingQueue<AuditEvent> queue = new ArrayBlockingQueue<>(8192);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LongAdder dropped = new LongAdder();
    private Thread worker;

    @PostConstruct
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::drainLoop, "battle-audit-flush");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        flushRemaining();
    }

    public void recordFightEnd(long battleId, int playerId, int endStatus, int clientEndStatus, String reason) {
        offer(new AuditEvent(Kind.FIGHT_END, battleId, playerId, endStatus, clientEndStatus,
                reason, 0, 0, 0, 0, 0));
    }

    public void recordDamage(long battleId, int playerId, int skillId, int targetEntityId,
                             int hpBefore, int hpAfter, int delta) {
        offer(new AuditEvent(Kind.DAMAGE, battleId, playerId, 0, 0, null,
                skillId, targetEntityId, hpBefore, hpAfter, delta));
    }

    public void recordReject(long battleId, int playerId, String reason) {
        offer(new AuditEvent(Kind.REJECT, battleId, playerId, 0, 0, reason,
                0, 0, 0, 0, 0));
    }

    private void offer(AuditEvent event) {
        if (!queue.offer(event)) {
            dropped.increment();
        }
    }

    private void drainLoop() {
        List<AuditEvent> batch = new ArrayList<>(64);
        while (running.get() || !queue.isEmpty()) {
            try {
                AuditEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 63);
                }
                if (!batch.isEmpty()) {
                    for (AuditEvent e : batch) {
                        writeOne(e);
                    }
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("battle_audit flush error: {}", e.toString());
            }
        }
        flushRemaining();
    }

    private void flushRemaining() {
        List<AuditEvent> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (AuditEvent e : rest) {
            writeOne(e);
        }
    }

    private void writeOne(AuditEvent e) {
        switch (e.kind()) {
            case FIGHT_END -> log.info(
                    "battle_audit end battleId={} playerId={} endStatus={} clientEndStatus={} reason={}",
                    e.battleId(), e.playerId(), e.endStatus(), e.clientEndStatus(), e.reason());
            case DAMAGE -> log.info(
                    "battle_audit dmg battleId={} playerId={} skillId={} target={} hpBefore={} hpAfter={} delta={}",
                    e.battleId(), e.playerId(), e.skillId(), e.targetEntityId(),
                    e.hpBefore(), e.hpAfter(), e.delta());
            case REJECT -> log.warn("battle_audit reject battleId={} playerId={} reason={}",
                    e.battleId(), e.playerId(), e.reason());
        }
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public int queueDepth() {
        return queue.size();
    }
}
