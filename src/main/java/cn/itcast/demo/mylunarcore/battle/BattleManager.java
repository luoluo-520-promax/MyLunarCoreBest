// 战斗运行时管理所在包
package cn.itcast.demo.mylunarcore.battle;

// Spring 组件扫描注册
import org.springframework.stereotype.Component;

// 线程安全哈希映射
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战局管理器：维护当前进行中战斗的内存索引，供协议处理与结算按 battleId 查找 {@link BattleContext}。
 * 关键路径同步调用 {@link BattleSnapshotService}，支撑断线重连热恢复。
 */
@Component // Spring Bean：战局运行时注册表
public class BattleManager {

    // battleId → 战局完整运行时上下文
    private final ConcurrentHashMap<Long, BattleContext> battles = new ConcurrentHashMap<>(); // 并发安全的进行中战局缓存
    private final BattleSnapshotService snapshotService;
    private final BattleInstancePool instancePool;
    private final org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.ops.ServerFreezeService> freezeProvider;

    public BattleManager(BattleSnapshotService snapshotService, BattleInstancePool instancePool,
                         org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.ops.ServerFreezeService> freezeProvider) {
        this.snapshotService = snapshotService;
        this.instancePool = instancePool;
        this.freezeProvider = freezeProvider;
    }

    /** 单测便捷：内建配额池。 */
    public BattleManager(BattleSnapshotService snapshotService) {
        this(snapshotService, new cn.itcast.demo.mylunarcore.config.LunarCoreProperties());
    }

    public BattleManager(BattleSnapshotService snapshotService, cn.itcast.demo.mylunarcore.config.LunarCoreProperties properties) {
        this(snapshotService, new BattleInstancePool(properties),
                new org.springframework.beans.factory.ObjectProvider<>() {
                    @Override
                    public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getObject() {
                        return null;
                    }

                    @Override
                    public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getIfAvailable() {
                        return null;
                    }

                    @Override
                    public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getIfUnique() {
                        return null;
                    }
                });
    }

    public BattleManager(BattleSnapshotService snapshotService, BattleInstancePool instancePool) {
        this(snapshotService, instancePool, new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getObject() {
                return null;
            }

            @Override
            public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getIfAvailable() {
                return null;
            }

            @Override
            public cn.itcast.demo.mylunarcore.ops.ServerFreezeService getIfUnique() {
                return null;
            }
        });
    }

    /**
     * 按战斗 ID 获取当前战局；不存在返回 null。
     */
    public BattleContext get(long battleId) { // 按 battleId 查询战局
        return battles.get(battleId); // 不存在则返回 null
    }

    /**
     * 注册一场新战斗到内存索引；受 {@link BattleInstancePool} 配额约束。
     *
     * @return true=已准入；false=排队或拒绝（调用方应返回满员/稍后重试）
     */
    public boolean put(BattleContext context) { // 注册新战局
        return put(context, BattleInstancePool.Priority.PLAYER_ACTIVE);
    }

    public boolean put(BattleContext context, BattleInstancePool.Priority priority) {
        if (context == null) {
            return false;
        }
        var freeze = freezeProvider.getIfAvailable();
        if (freeze != null && !freeze.acceptNewBattles()) {
            return false;
        }
        BattleInstancePool.AdmitOutcome admit = instancePool.tryAdmit(context.getBattleId(), priority);
        if (admit.result() == BattleInstancePool.AdmitResult.REJECTED) {
            return false;
        }
        if (admit.result() == BattleInstancePool.AdmitResult.QUEUED) {
            // 排队中仍挂入索引，但 Tick 调度会跳过直至出队
            battles.put(context.getBattleId(), context);
            snapshotService.saveAsync(context);
            return false;
        }
        battles.put(context.getBattleId(), context); // 以 battleId 为键存入
        snapshotService.saveAsync(context);
        return true;
    }

    /**
     * 回合推进等状态变更后刷新快照（异步）。
     */
    public void checkpoint(BattleContext context) {
        if (context != null) {
            snapshotService.saveAsync(context);
        }
    }

    /**
     * @return 当前内存中活跃战斗数量
     */
    public int activeBattleCount() {
        return battles.size();
    }

    public BattleInstancePool instancePool() {
        return instancePool;
    }

    /**
     * 结束并移除一场战斗，释放内存。
     */
    public void remove(long battleId) { // 移除已结束战局
        battles.remove(battleId); // 从索引中删除
        instancePool.release(battleId);
        snapshotService.remove(battleId);
    }

    /**
     * 回收超时未结束战局。
     *
     * @return 清理数量
     */
    public int evictExpired(long nowSeconds, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            return 0;
        }
        java.util.List<Long> toRemove = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, BattleContext> e : battles.entrySet()) {
            BattleContext ctx = e.getValue();
            if (ctx == null || ctx.isEnded()) {
                continue;
            }
            if (nowSeconds - ctx.getStartTimeSeconds() >= ttlSeconds) {
                toRemove.add(e.getKey());
            }
        }
        for (Long id : toRemove) {
            BattleContext ctx = battles.remove(id);
            if (ctx != null) {
                synchronized (ctx.getLock()) {
                    ctx.setEnded(true);
                }
                instancePool.release(id);
                snapshotService.remove(id);
            }
        }
        return toRemove.size();
    }

    /**
     * 查找玩家当前进行中的战局（未结束）；含多人共战参与者。
     * 内存未命中时从 Redis/内存快照 hydrate 并重新挂入索引。
     */
    public BattleContext findActiveByPlayerId(int playerId) {
        for (BattleContext ctx : battles.values()) {
            if (ctx != null && !ctx.isEnded() && ctx.isParticipant(playerId)) {
                return ctx;
            }
        }
        return snapshotService.hydrateByPlayer(playerId)
                .filter(ctx -> !ctx.isEnded())
                .map(ctx -> {
                    battles.put(ctx.getBattleId(), ctx);
                    instancePool.tryAdmit(ctx.getBattleId(), BattleInstancePool.Priority.PLAYER_ACTIVE);
                    return ctx;
                })
                .orElse(null);
    }

    /**
     * 跨节点迁移：导入快照载荷并挂入本节点战斗索引。
     */
    public BattleContext adoptMigrationPayload(String payloadJson) {
        return snapshotService.importMigrationPayload(payloadJson)
                .map(ctx -> {
                    battles.put(ctx.getBattleId(), ctx);
                    instancePool.tryAdmit(ctx.getBattleId(), BattleInstancePool.Priority.PLAYER_ACTIVE);
                    return ctx;
                })
                .orElse(null);
    }

    /**
     * 按玩家 id 移除其所有进行中的战局（返回主界面 / 退出游戏时清理）。
     *
     * @param playerId 玩家角色 id（与 uid 对齐的业务 id）
     * @return 移除的战局数量
     */
    public int removeByPlayerId(int playerId) {
        java.util.List<Long> toRemove = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Long, BattleContext> e : battles.entrySet()) {
            BattleContext ctx = e.getValue();
            if (ctx != null && ctx.isParticipant(playerId)) {
                toRemove.add(e.getKey());
            }
        }
        for (Long id : toRemove) {
            battles.remove(id);
            instancePool.release(id);
            snapshotService.remove(id);
        }
        return toRemove.size();
    }

    /**
     * 停机：将进行中战局标记结束并清空索引，避免半结算状态写入玩家数据。
     *
     * @return 被强制结束的战局数
     */
    public int abortAllForShutdown() {
        int n = 0;
        for (java.util.Map.Entry<Long, BattleContext> e : battles.entrySet()) {
            BattleContext ctx = e.getValue();
            if (ctx == null) {
                continue;
            }
            synchronized (ctx.getLock()) {
                if (!ctx.isEnded()) {
                    snapshotService.save(ctx);
                    ctx.setEnded(true);
                    n++;
                }
            }
            instancePool.release(ctx.getBattleId());
        }
        battles.clear();
        return n;
    }

    /**
     * @return 当前未结束战局数量
     */
    /** 进行中战局快照（供自动战斗定时器遍历）。 */
    public java.util.Collection<BattleContext> snapshotActive() {
        return java.util.List.copyOf(battles.values());
    }

    public int activeUnendedCount() {
        int n = 0;
        for (BattleContext ctx : battles.values()) {
            if (ctx != null && !ctx.isEnded()) {
                n++;
            }
        }
        return n;
    }
}
