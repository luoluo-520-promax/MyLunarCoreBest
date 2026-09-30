package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 中心服场景注册表：Zone → 节点，带心跳租约与排水（draining）标记。
 */
@Component
public class SceneRegistry {

    /**
     * @param zoneId             planeId * 10000 + floorId
     * @param nodeId             承载节点
     * @param registeredAt       首次注册时间
     * @param lastHeartbeatAt    最近心跳
     * @param leaseUntilMillis   租约到期时间；超时未续约可摘除
     * @param draining           排水中：不再承接新玩家
     * @param playerCountHint    容量提示（计划迁移时优先低负载）
     */
    public record ZoneInfo(int zoneId, int planeId, int floorId, String nodeId,
                           long registeredAt, long lastHeartbeatAt, long leaseUntilMillis,
                           boolean draining, int playerCountHint) {

        ZoneInfo renew(long nowMillis, long leaseTtlMs, int playerCountHint) {
            return new ZoneInfo(zoneId, planeId, floorId, nodeId, registeredAt,
                    nowMillis, nowMillis + leaseTtlMs, draining, playerCountHint);
        }

        ZoneInfo withDraining(boolean draining) {
            return new ZoneInfo(zoneId, planeId, floorId, nodeId, registeredAt,
                    lastHeartbeatAt, leaseUntilMillis, draining, playerCountHint);
        }

        boolean isLeaseExpired(long nowMillis) {
            return nowMillis > leaseUntilMillis;
        }
    }

    private final Map<Integer, ZoneInfo> zones = new ConcurrentHashMap<>();
    private final long leaseTtlMs;

    /** 单测默认租约 15s。 */
    public SceneRegistry() {
        this.leaseTtlMs = 15_000L;
    }

    @Autowired
    public SceneRegistry(LunarCoreProperties properties) {
        long configured = properties.getCenter().getZoneLeaseMs();
        this.leaseTtlMs = configured > 0 ? configured : 15_000L;
    }

    public static int zoneId(int planeId, int floorId) {
        return planeId * 10000 + floorId;
    }

    public long leaseTtlMs() {
        return leaseTtlMs;
    }

    public ZoneInfo register(int planeId, int floorId) {
        return register(planeId, floorId, "local");
    }

    /**
     * 注册或续约 Zone。同 node 再次 register 会刷新心跳；异 node 在租约有效期内不抢占。
     */
    public ZoneInfo register(int planeId, int floorId, String nodeId) {
        int zoneId = zoneId(planeId, floorId);
        String resolvedNode = (nodeId == null || nodeId.isBlank()) ? "local" : nodeId.trim();
        long now = System.currentTimeMillis();
        return zones.compute(zoneId, (id, existing) -> {
            if (existing == null || existing.isLeaseExpired(now)) {
                return new ZoneInfo(id, planeId, floorId, resolvedNode,
                        now, now, now + leaseTtlMs, false, 0);
            }
            if (existing.nodeId().equals(resolvedNode)) {
                return existing.renew(now, leaseTtlMs, existing.playerCountHint());
            }
            // 租约仍有效且非本节点：保持原主（防双主）
            return existing;
        });
    }

    /** 本节点心跳续约；node 不匹配则失败。 */
    public ZoneInfo heartbeat(int zoneId, String nodeId, int playerCountHint) {
        long now = System.currentTimeMillis();
        String resolvedNode = (nodeId == null || nodeId.isBlank()) ? "local" : nodeId.trim();
        return zones.computeIfPresent(zoneId, (id, existing) -> {
            if (!existing.nodeId().equals(resolvedNode)) {
                return existing;
            }
            return existing.renew(now, leaseTtlMs, Math.max(0, playerCountHint));
        });
    }

    public ZoneInfo markDraining(int zoneId, boolean draining) {
        return zones.computeIfPresent(zoneId, (id, existing) -> existing.withDraining(draining));
    }

    public ZoneInfo find(int zoneId) {
        ZoneInfo info = zones.get(zoneId);
        if (info == null) {
            return null;
        }
        if (info.isLeaseExpired(System.currentTimeMillis())) {
            zones.remove(zoneId, info);
            return null;
        }
        return info;
    }

    /** 摘除所有租约过期的 Zone，返回摘除数量。 */
    public int evictExpired(long nowMillis) {
        int removed = 0;
        for (Map.Entry<Integer, ZoneInfo> entry : zones.entrySet()) {
            ZoneInfo info = entry.getValue();
            if (info.isLeaseExpired(nowMillis) && zones.remove(entry.getKey(), info)) {
                removed++;
            }
        }
        return removed;
    }

    /** 本节点持有的全部 Zone（含即将到期，用于心跳续约）。 */
    public List<ZoneInfo> listByNode(String nodeId) {
        String resolved = (nodeId == null || nodeId.isBlank()) ? "local" : nodeId.trim();
        List<ZoneInfo> result = new ArrayList<>();
        for (ZoneInfo info : zones.values()) {
            if (resolved.equals(info.nodeId())) {
                result.add(info);
            }
        }
        return result;
    }

    public int size() {
        return zones.size();
    }

    /** 全部 Zone 快照（指标 / 运维）。 */
    public List<ZoneInfo> listAll() {
        return List.copyOf(zones.values());
    }

    /** 所有 Zone 的 playerCountHint 之和。 */
    public int totalPlayerHint() {
        int sum = 0;
        for (ZoneInfo info : zones.values()) {
            sum += Math.max(0, info.playerCountHint());
        }
        return sum;
    }
}
