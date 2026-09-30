package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.battle.BattleInstancePool;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 将战斗池使用率 / 队列深度定期写入 Prometheus，供 K8s HPA 消费。
 */
@Component
public class BattlePoolMetricsReporter {

    private final BattleInstancePool pool;
    private final BusinessMetrics metrics;

    public BattlePoolMetricsReporter(BattleInstancePool pool, BusinessMetrics metrics) {
        this.pool = pool;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 2_000L)
    public void report() {
        double usage = pool.usageRatio();
        metrics.setBattlePoolUsage(usage);
        metrics.setActiveBattles(pool.activeCount());
        metrics.setMatchQueueDepth(pool.queueDepth());
    }
}
