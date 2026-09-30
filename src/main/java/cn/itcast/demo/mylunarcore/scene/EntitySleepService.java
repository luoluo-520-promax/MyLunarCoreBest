package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远离玩家的 NPC/怪物休眠与降频：无邻近玩家时挂起 AI，仅 1Hz 心跳。
 */
@Component
public class EntitySleepService {

    public enum Mode { ACTIVE, THROTTLED, SLEEPING }

    private final LunarCoreProperties properties;
    private final ConcurrentHashMap<Long, Long> lastHeartbeatMs = new ConcurrentHashMap<>();

    public EntitySleepService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public Mode resolve(ZoneContext zone, float entityX, float entityZ, long nowMs) {
        if (zone == null || zone.getPlayerUids().isEmpty()) {
            return Mode.SLEEPING;
        }
        float sleepDist = properties.getZone().getEntitySleepDistance();
        float sleepDistSq = sleepDist * sleepDist;
        boolean near = false;
        boolean mid = false;
        float midDistSq = (sleepDist * 0.5f) * (sleepDist * 0.5f);
        for (Map.Entry<Long, SceneContext.ScenePos> e : zone.getPlayerPositions().entrySet()) {
            SceneContext.ScenePos p = e.getValue();
            if (p == null) {
                continue;
            }
            float dx = p.getX() - entityX;
            float dz = p.getZ() - entityZ;
            float d2 = dx * dx + dz * dz;
            if (d2 <= midDistSq) {
                near = true;
                break;
            }
            if (d2 <= sleepDistSq) {
                mid = true;
            }
        }
        if (near) {
            return Mode.ACTIVE;
        }
        if (mid) {
            return Mode.THROTTLED;
        }
        return Mode.SLEEPING;
    }

    /** 是否允许本帧执行 AI/刷新；SLEEPING 按 sleepHz 放行心跳。 */
    public boolean shouldUpdate(ZoneContext zone, int entityId, float x, float z, long nowMs) {
        Mode mode = resolve(zone, x, z, nowMs);
        if (mode == Mode.ACTIVE) {
            return true;
        }
        long key = ((long) zone.getZoneId() << 32) | Integer.toUnsignedLong(entityId);
        long interval = mode == Mode.THROTTLED ? 500L
                : (long) (1000.0 / Math.max(0.2, properties.getZone().getEntitySleepHz()));
        Long last = lastHeartbeatMs.get(key);
        if (last == null || nowMs - last >= interval) {
            lastHeartbeatMs.put(key, nowMs);
            return true;
        }
        return false;
    }

    public void clearZone(int zoneId) {
        long prefix = (long) zoneId << 32;
        lastHeartbeatMs.keySet().removeIf(k -> (k & 0xFFFFFFFF00000000L) == prefix);
    }
}
