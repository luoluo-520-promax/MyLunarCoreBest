package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * Zone 级时钟：每个 Zone 每帧只刷新一次世界怪，再对区内玩家做仇恨判定，并推进 NPC 作息。
 */
@Component
public class ZoneTickService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, ZoneTickService.class);

    private final ZoneManager zoneManager;
    private final ZoneWorldService zoneWorldService;
    private final WorldEncounterService worldEncounterService;
    private final EntitySleepService entitySleepService;
    private final EntityBehaviorService entityBehaviorService;

    public ZoneTickService(ZoneManager zoneManager,
                           ZoneWorldService zoneWorldService,
                           WorldEncounterService worldEncounterService,
                           EntitySleepService entitySleepService,
                           EntityBehaviorService entityBehaviorService) {
        this.zoneManager = zoneManager;
        this.zoneWorldService = zoneWorldService;
        this.worldEncounterService = worldEncounterService;
        this.entitySleepService = entitySleepService;
        this.entityBehaviorService = entityBehaviorService;
    }

    public void onZoneTick(long nowMillis, long deltaMillis) {
        long started = System.nanoTime();
        for (ZoneContext zone : zoneManager.snapshotZones()) {
            try {
                if (zone.getPlayerUids().isEmpty()) {
                    continue; // 无人休眠：跳过刷新与仇恨
                }
                zoneManager.densifyAoiIfNeeded(zone);
                zoneWorldService.tickRefresh(zone, nowMillis);
                entityBehaviorService.tickZone(zone, nowMillis, deltaMillis);
                // 仅对 ACTIVE/THROTTLED 窗口内的怪做仇恨，SLEEPING 跳过
                boolean anyActiveMonster = false;
                for (ZoneContext.ZoneMonster m : zone.getMonsters().values()) {
                    if (m == null || m.getPos() == null) {
                        continue;
                    }
                    if (entitySleepService.shouldUpdate(zone, m.getEntityId(),
                            m.getPos().getX(), m.getPos().getZ(), nowMillis)) {
                        anyActiveMonster = true;
                        break;
                    }
                }
                if (anyActiveMonster || !zone.getMonsters().isEmpty()) {
                    // 有近场活跃实体时走完整仇恨；全休眠则跳过本帧遭遇
                    EntitySleepService.Mode sample = EntitySleepService.Mode.SLEEPING;
                    for (ZoneContext.ZoneMonster m : zone.getMonsters().values()) {
                        if (m == null || m.getPos() == null) {
                            continue;
                        }
                        sample = entitySleepService.resolve(zone, m.getPos().getX(), m.getPos().getZ(), nowMillis);
                        if (sample != EntitySleepService.Mode.SLEEPING) {
                            break;
                        }
                    }
                    if (sample != EntitySleepService.Mode.SLEEPING) {
                        worldEncounterService.onZoneTick(zone, nowMillis);
                    }
                }
            } catch (Exception e) {
                log.warn("Zone tick failed, zoneId={}", zone.getZoneId(), e);
            }
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        zoneManager.reportTickDurationMs(elapsedMs);
    }
}
