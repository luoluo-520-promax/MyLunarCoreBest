package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;

/**
 * 战区（Shard）分配：登录/进场景时按服务器负载与玩家地域哈希选择最优 Zone/分线。
 */
@Service
public class ShardAllocationService {

    public record ShardChoice(int planeId, int floorId, int lineId, int zoneId, String reason) {}

    private final ZoneManager zoneManager;
    private final DynamicZoneLineAllocator lineAllocator;
    private final SceneRegistry sceneRegistry;
    private final boolean preferLowLoad;

    public ShardAllocationService(ZoneManager zoneManager,
                                  DynamicZoneLineAllocator lineAllocator,
                                  SceneRegistry sceneRegistry,
                                  LunarCoreProperties properties) {
        this.zoneManager = zoneManager;
        this.lineAllocator = lineAllocator;
        this.sceneRegistry = sceneRegistry;
        this.preferLowLoad = properties.getZone().isShardPreferLowLoad();
    }

    /**
     * @param regionHint 可选地域标签（如 "cn-east"），参与哈希打散
     */
    public ShardChoice allocate(long playerUid, int preferredPlaneId, int preferredFloorId, String regionHint) {
        int plane = preferredPlaneId > 0 ? preferredPlaneId : 1;
        int floor = preferredFloorId > 0 ? preferredFloorId : 1;
        Integer line = lineAllocator.pickJoinableLine(plane, floor, playerUid);
        if (line == null) {
            // 全部满：按地域哈希落到稳定 line，由上层处理 FULL
            int hashed = Math.floorMod((int) (playerUid ^ (regionHint == null ? 0 : regionHint.hashCode())),
                    Math.max(1, lineAllocator.getMaxLines()));
            int zoneId = DynamicZoneLineAllocator.encodeZoneId(plane, floor, hashed);
            return new ShardChoice(plane, floor, hashed, zoneId, "hash_fallback_full");
        }
        if (preferLowLoad) {
            ShardChoice better = pickLowestLoad(plane, floor, playerUid, regionHint);
            if (better != null) {
                return better;
            }
        }
        int zoneId = DynamicZoneLineAllocator.encodeZoneId(plane, floor, line);
        String reason = regionHint == null || regionHint.isBlank() ? "line_allocator" : "line_allocator+region";
        return new ShardChoice(plane, floor, line, zoneId, reason);
    }

    private ShardChoice pickLowestLoad(int plane, int floor, long playerUid, String regionHint) {
        Collection<ZoneContext> zones = zoneManager.snapshotZones();
        ZoneContext best = zones.stream()
                .filter(z -> z.getPlaneId() == plane && z.getFloorId() == floor)
                .filter(z -> {
                    int cap = zoneManager.effectiveMaxPlayers();
                    return cap <= 0 || z.getPlayerUids().size() < cap
                            || z.getPlayerUids().contains(playerUid);
                })
                .min(Comparator.comparingInt(z -> z.getPlayerUids().size()))
                .orElse(null);
        if (best == null) {
            return null;
        }
        // 同负载时用地域打散，避免全挤 line0
        if (regionHint != null && !regionHint.isBlank()
                && best.getPlayerUids().size() > 0
                && Math.floorMod(regionHint.hashCode() + (int) playerUid, 2) == 1) {
            Integer alt = lineAllocator.pickJoinableLine(plane, floor, playerUid ^ 0x5f3759dfL);
            if (alt != null && alt != DynamicZoneLineAllocator.lineIdOf(best.getZoneId())) {
                return new ShardChoice(plane, floor, alt,
                        DynamicZoneLineAllocator.encodeZoneId(plane, floor, alt), "low_load_region_spread");
            }
        }
        return new ShardChoice(plane, floor, DynamicZoneLineAllocator.lineIdOf(best.getZoneId()),
                best.getZoneId(), "lowest_load");
    }

    /** 注册表视角的节点负载（人数），供跨进程选址扩展。 */
    public int registryPlayerCount(int planeId, int floorId) {
        SceneRegistry.ZoneInfo info = sceneRegistry.find(planeId * 10000 + floorId);
        return info == null ? 0 : info.playerCountHint();
    }
}
