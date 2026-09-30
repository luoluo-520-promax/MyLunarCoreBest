package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态 Zone 分裂：活跃人数超过阈值时创建 split Zone，并按 UID 哈希迁移部分玩家（软过渡预加载）。
 */
@Service
public class DynamicZoneSplitService {

    private static final Logger log = LoggerFactory.getLogger(DynamicZoneSplitService.class);

    public record SplitEvent(int sourceZoneId, int newZoneId, String splitName, List<Long> migratedUids,
                             long atMs) {}

    private final ZoneManager zoneManager;
    private final GameSessionManager sessionManager;
    private final int splitThreshold;
    private final int retainRatioPercent;
    private final Map<Integer, Integer> splitOf = new ConcurrentHashMap<>();
    private final List<SplitEvent> recent = new ArrayList<>();

    public DynamicZoneSplitService(ZoneManager zoneManager,
                                   ObjectProvider<GameSessionManager> sessionProvider,
                                   cn.itcast.demo.mylunarcore.config.LunarCoreProperties properties) {
        this.zoneManager = zoneManager;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        int thr = properties.getZone().getSplitThreshold();
        this.splitThreshold = thr > 0 ? thr : 250;
        int retain = properties.getZone().getSplitRetainPercent();
        this.retainRatioPercent = Math.max(0, Math.min(99, retain));
    }

    /** 单测构造。 */
    public DynamicZoneSplitService(ZoneManager zoneManager, int splitThreshold) {
        this.zoneManager = zoneManager;
        this.sessionManager = null;
        this.splitThreshold = Math.max(1, splitThreshold);
        this.retainRatioPercent = 50;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void scanAndSplit() {
        for (ZoneContext zone : zoneManager.snapshotZones()) {
            if (zone == null) {
                continue;
            }
            if (splitOf.containsKey(zone.getZoneId())) {
                continue;
            }
            int size = zone.getPlayerUids().size();
            if (size < splitThreshold) {
                continue;
            }
            SplitEvent ev = splitZone(zone);
            if (ev != null) {
                synchronized (recent) {
                    recent.add(ev);
                    if (recent.size() > 50) {
                        recent.remove(0);
                    }
                }
            }
        }
    }

    public SplitEvent splitZone(ZoneContext source) {
        if (source == null) {
            return null;
        }
        int srcId = source.getZoneId();
        if (splitOf.containsKey(srcId)) {
            return null;
        }
        long ts = System.currentTimeMillis();
        String name = "Zone-" + srcId + "-split-" + ts;
        // 使用新 line：在原 plane/floor 上开更高 lineId
        int line = DynamicZoneLineAllocator.lineIdOf(srcId) + 1;
        int plane = source.getPlaneId();
        int floor = source.getFloorId();
        ZoneContext target = zoneManager.getOrCreate(plane, floor, line);
        int newZoneId = target.getZoneId();
        splitOf.put(srcId, newZoneId);

        List<Long> uids = new ArrayList<>(source.getPlayerUids());
        List<Long> migrated = new ArrayList<>();
        for (Long uid : uids) {
            if (uid == null) {
                continue;
            }
            // 按 UID 哈希：一半迁移
            if (Math.floorMod(uid.hashCode(), 100) < retainRatioPercent) {
                continue;
            }
            SceneContext.ScenePos pos = source.getPlayerPositions().get(uid);
            if (pos == null) {
                pos = new SceneContext.ScenePos(0, 0, 0);
            }
            pushPreload(uid, plane, floor, newZoneId, name);
            zoneManager.leaveZone(srcId, uid);
            zoneManager.joinZone(plane, floor, line, uid, pos);
            migrated.add(uid);
        }
        log.info("zone_split source={} new={} name={} migrated={}/{}",
                srcId, newZoneId, name, migrated.size(), uids.size());
        return new SplitEvent(srcId, newZoneId, name, migrated, ts);
    }

    private void pushPreload(long uid, int planeId, int floorId, int zoneId, String splitName) {
        if (sessionManager == null) {
            return;
        }
        String json = "{\"planeId\":" + planeId + ",\"floorId\":" + floorId
                + ",\"zoneId\":" + zoneId + ",\"splitName\":\"" + splitName
                + "\",\"softTransition\":true}";
        GameSession s = sessionManager.getOrNull(uid);
        if (s != null) {
            s.send(new GamePacket(CmdIds.SCENE_PRELOAD_PUSH_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    public List<SplitEvent> recentSplits() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    public Integer splitTargetOf(int sourceZoneId) {
        return splitOf.get(sourceZoneId);
    }
}
