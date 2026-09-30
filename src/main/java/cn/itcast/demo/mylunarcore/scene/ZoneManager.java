package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zone 运行时管理器：按 planeId + floorId（及可选 lineId）懒创建 {@link ZoneContext}，
 * 并代理玩家的进出与坐标更新。支持 Actor 邮箱串行化与动态分线编码。
 */
@Component
public class ZoneManager {

    public enum JoinResult {
        OK,
        FULL,
        DRAINING
    }

    private final SceneRegistry sceneRegistry;
    private final SceneEntityIdAllocator entityIdAllocator;
    private final LunarCoreProperties properties;
    private final String localNodeId;
    private final int maxPlayers;
    private final boolean rejectDraining;
    private final boolean adaptiveCapacityEnabled;
    private final long adaptiveTickBudgetMs;
    private final Map<Integer, ZoneContext> zones = new ConcurrentHashMap<>();
    /** 由 ZoneTickService 上报的最近 tick 耗时（ms），用于自适应容量。 */
    private volatile long lastTickDurationMs;
    private final ZoneActorMailbox actorMailbox;

    public ZoneManager(SceneRegistry sceneRegistry,
                       SceneEntityIdAllocator entityIdAllocator,
                       LunarCoreProperties properties) {
        this.sceneRegistry = sceneRegistry;
        this.entityIdAllocator = entityIdAllocator;
        this.properties = properties;
        String configured = properties.getCenter().getLocalNodeId();
        this.localNodeId = (configured == null || configured.isBlank()) ? "local" : configured.trim();
        this.maxPlayers = Math.max(0, properties.getZone().getMaxPlayers());
        this.rejectDraining = properties.getZone().isRejectDraining();
        this.adaptiveCapacityEnabled = properties.getZone().isAdaptiveCapacityEnabled();
        this.adaptiveTickBudgetMs = Math.max(1L, properties.getZone().getAdaptiveTickBudgetMs());
        this.actorMailbox = new ZoneActorMailbox(properties.getZone().isActorMailboxEnabled());
    }

    /** 单测便捷构造。 */
    public ZoneManager(SceneRegistry sceneRegistry, SceneEntityIdAllocator entityIdAllocator) {
        this.sceneRegistry = sceneRegistry;
        this.entityIdAllocator = entityIdAllocator;
        this.properties = new LunarCoreProperties();
        this.localNodeId = "local";
        this.maxPlayers = 0;
        this.rejectDraining = false;
        this.adaptiveCapacityEnabled = false;
        this.adaptiveTickBudgetMs = 8L;
        this.actorMailbox = new ZoneActorMailbox(false);
    }

    public void reportTickDurationMs(long durationMs) {
        this.lastTickDurationMs = Math.max(0L, durationMs);
    }

    /** 有效人数上限：tick 超预算时按比例下调（最低 50 或配置上限的 1/4）。 */
    public int effectiveMaxPlayers() {
        if (maxPlayers <= 0 || !adaptiveCapacityEnabled) {
            return maxPlayers;
        }
        long tick = lastTickDurationMs;
        if (tick <= adaptiveTickBudgetMs) {
            return maxPlayers;
        }
        double ratio = (double) adaptiveTickBudgetMs / (double) tick;
        int scaled = (int) Math.floor(maxPlayers * Math.max(0.25, Math.min(1.0, ratio)));
        return Math.max(50, Math.min(maxPlayers, scaled));
    }

    public ZoneContext getOrCreate(int planeId, int floorId) {
        return getOrCreate(planeId, floorId, 0);
    }

    public ZoneContext getOrCreate(int planeId, int floorId, int lineId) {
        int zoneId = DynamicZoneLineAllocator.encodeZoneId(planeId, floorId, lineId);
        return zones.computeIfAbsent(zoneId, id -> {
            // 注册表仍按逻辑 plane/floor 心跳；分线用 zoneId 区分运行时
            sceneRegistry.register(planeId, floorId, localNodeId);
            float cell = properties.getZone().getAoiCellSize();
            return new ZoneContext(zoneId, planeId, floorId, cell);
        });
    }

    /**
     * 按格子密度动态收缩 AOI：密度过高时缩小 cellSize，减少单次广播实体数。
     */
    public void densifyAoiIfNeeded(ZoneContext zone) {
        if (zone == null) {
            return;
        }
        AoiGrid grid = zone.getAoiGrid();
        int dense = properties.getZone().getAoiDensePlayersPerCell();
        if (grid.maxPlayersInAnyCell() < dense) {
            return;
        }
        float min = properties.getZone().getAoiMinCellSize();
        float next = Math.max(min, grid.getCellSize() * 0.75f);
        Map<Long, float[]> xz = new java.util.HashMap<>();
        zone.getPlayerPositions().forEach((uid, pos) -> {
            if (pos != null) {
                xz.put(uid, new float[]{pos.getX(), pos.getZ()});
            }
        });
        grid.adjustCellSize(next, xz);
    }

    public ZoneContext get(int zoneId) {
        return zones.get(zoneId);
    }

    public Collection<ZoneContext> snapshotZones() {
        return List.copyOf(zones.values());
    }

    public ZoneActorMailbox actorMailbox() {
        return actorMailbox;
    }

    /**
     * 仅校验是否可加入，不修改状态。
     */
    public JoinResult checkCanJoin(int planeId, int floorId, long playerUid) {
        return checkCanJoin(planeId, floorId, 0, playerUid);
    }

    public JoinResult checkCanJoin(int planeId, int floorId, int lineId, long playerUid) {
        ZoneContext zone = getOrCreate(planeId, floorId, lineId);
        if (rejectDraining) {
            SceneRegistry.ZoneInfo info = sceneRegistry.find(DynamicZoneLineAllocator.baseZoneIdOf(zone.getZoneId()));
            if (info != null && info.draining()) {
                return JoinResult.DRAINING;
            }
        }
        if (zone.getPlayerUids().contains(playerUid)) {
            return JoinResult.OK;
        }
        int cap = effectiveMaxPlayers();
        if (cap > 0 && zone.getPlayerUids().size() >= cap) {
            return JoinResult.FULL;
        }
        return JoinResult.OK;
    }

    /**
     * 尝试加入 Zone：校验排水与人数上限；已在区内则仅刷新坐标。
     */
    public JoinResult tryJoinZone(int planeId, int floorId, long playerUid, SceneContext.ScenePos pos) {
        return tryJoinZone(planeId, floorId, 0, playerUid, pos);
    }

    public JoinResult tryJoinZone(int planeId, int floorId, int lineId, long playerUid, SceneContext.ScenePos pos) {
        JoinResult check = checkCanJoin(planeId, floorId, lineId, playerUid);
        if (check != JoinResult.OK) {
            return check;
        }
        joinZone(planeId, floorId, lineId, playerUid, pos);
        return JoinResult.OK;
    }

    public void joinZone(int planeId, int floorId, long playerUid, SceneContext.ScenePos pos) {
        joinZone(planeId, floorId, 0, playerUid, pos);
    }

    public void joinZone(int planeId, int floorId, int lineId, long playerUid, SceneContext.ScenePos pos) {
        ZoneContext zone = getOrCreate(planeId, floorId, lineId);
        // 进出场必须同步完成，避免 check→join 竞态导致超员
        if (zone.getPlayerUids().contains(playerUid)) {
            zone.updatePlayerPos(playerUid, pos);
            return;
        }
        entityIdAllocator.allocateForPlayer(playerUid);
        zone.addPlayer(playerUid, pos);
        sceneRegistry.heartbeat(DynamicZoneLineAllocator.baseZoneIdOf(zone.getZoneId()),
                localNodeId, zone.getPlayerUids().size());
    }

    public void leaveZone(int zoneId, long playerUid) {
        ZoneContext zone = zones.get(zoneId);
        if (zone != null) {
            zone.removePlayer(playerUid);
            sceneRegistry.heartbeat(DynamicZoneLineAllocator.baseZoneIdOf(zoneId),
                    localNodeId, zone.getPlayerUids().size());
        }
        entityIdAllocator.release(playerUid);
    }

    public void updatePlayerPos(int zoneId, long playerUid, SceneContext.ScenePos pos) {
        ZoneContext zone = zones.get(zoneId);
        if (zone != null) {
            // 高频坐标更新走 Actor 邮箱，与广播异步化配合降低锁竞争
            actorMailbox.execute(zoneId, () -> zone.updatePlayerPos(playerUid, pos));
        }
    }

    @PreDestroy
    public void shutdownActors() {
        actorMailbox.close();
    }
}
