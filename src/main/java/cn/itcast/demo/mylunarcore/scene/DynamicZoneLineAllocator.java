package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

/**
 * 动态分线：主城等同 Plane/Floor 人数接近容量时，按 lineId 开新实例，
 * 使单逻辑场景聚合可达 1000+，同时单线仍受 AOI/Tick 约束。
 * <p>
 * zoneId 编码：{@code baseZoneId + lineId * LINE_STRIDE}，LINE_STRIDE=10_000_000，
 * 与现有 {@code planeId*10000+floorId} 不冲突（plane/floor 远小于该跨度）。
 */
@Component
public class DynamicZoneLineAllocator {

    public static final int LINE_STRIDE = 10_000_000;

    private final boolean enabled;
    private final int maxLines;
    private final ZoneManager zoneManager;

    public DynamicZoneLineAllocator(ZoneManager zoneManager, LunarCoreProperties properties) {
        this.zoneManager = zoneManager;
        this.enabled = properties.getZone().isDynamicLineEnabled();
        this.maxLines = Math.max(1, properties.getZone().getMaxLines());
    }

    public static int encodeZoneId(int planeId, int floorId, int lineId) {
        int base = planeId * 10000 + floorId;
        return base + Math.max(0, lineId) * LINE_STRIDE;
    }

    public static int lineIdOf(int zoneId) {
        return Math.max(0, zoneId / LINE_STRIDE);
    }

    public static int baseZoneIdOf(int zoneId) {
        return zoneId % LINE_STRIDE;
    }

    /**
     * 选择可加入的分线：优先 line=0，满员则递增；全部满则返回 null（调用方判 FULL）。
     */
    public Integer pickJoinableLine(int planeId, int floorId, long playerUid) {
        if (!enabled) {
            ZoneManager.JoinResult r = zoneManager.checkCanJoin(planeId, floorId, playerUid);
            return r == ZoneManager.JoinResult.OK ? 0 : null;
        }
        for (int line = 0; line < maxLines; line++) {
            int zoneId = encodeZoneId(planeId, floorId, line);
            ZoneContext zone = zoneManager.get(zoneId);
            if (zone == null) {
                return line;
            }
            if (zone.getPlayerUids().contains(playerUid)) {
                return line;
            }
            int cap = zoneManager.effectiveMaxPlayers();
            if (cap <= 0 || zone.getPlayerUids().size() < cap) {
                return line;
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxLines() {
        return maxLines;
    }
}
