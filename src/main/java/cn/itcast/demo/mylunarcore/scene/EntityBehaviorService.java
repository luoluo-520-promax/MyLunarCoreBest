package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

/**
 * 场景 NPC/生物独立日程：挂载 {@link DailyRoutineBehavior}，时段切换推送 SceneNpcBehaviorScNotify。
 */
@Service
public class EntityBehaviorService implements WorldTimePeriodListener {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, EntityBehaviorService.class);
    private static final long DEFAULT_TRANSITION_MS = 800L;

    private final NpcScheduleConfigRepository scheduleRepo;
    private final WorldTimeService worldTimeService;
    private final ZoneManager zoneManager;
    private final SceneManager sceneManager;
    private final SceneSyncBroadcaster sceneSyncBroadcaster;
    private final EntitySleepService entitySleepService;

    public EntityBehaviorService(NpcScheduleConfigRepository scheduleRepo,
                                 WorldTimeService worldTimeService,
                                 ZoneManager zoneManager,
                                 SceneManager sceneManager,
                                 SceneSyncBroadcaster sceneSyncBroadcaster,
                                 EntitySleepService entitySleepService) {
        this.scheduleRepo = scheduleRepo;
        this.worldTimeService = worldTimeService;
        this.zoneManager = zoneManager;
        this.sceneManager = sceneManager;
        this.sceneSyncBroadcaster = sceneSyncBroadcaster;
        this.entitySleepService = entitySleepService;
    }

    /** 播种后绑定：记录出生点并立即套用当前时段行为。 */
    public void bindNpc(ZoneContext zone, ZoneContext.ZoneNpc npc) {
        if (zone == null || npc == null) {
            return;
        }
        if (npc.getHomePos() == null && npc.getPos() != null) {
            npc.setHomePos(new SceneContext.ScenePos(
                    npc.getPos().getX(), npc.getPos().getY(), npc.getPos().getZ()));
        }
        applyPeriod(zone, npc, worldTimeService.currentPeriod(), true);
    }

    @Override
    public void onPeriodChanged(WorldTimeService.Period previous, WorldTimeService.Period next) {
        for (ZoneContext zone : zoneManager.snapshotZones()) {
            for (ZoneContext.ZoneNpc npc : zone.getNpcs().values()) {
                try {
                    applyPeriod(zone, npc, next, true);
                } catch (Exception e) {
                    log.warn("npc period apply failed zoneId={} entityId={}",
                            zone.getZoneId(), npc.getEntityId(), e);
                }
            }
        }
    }

    /** ZoneTick：推进巡逻等持续行为（受 EntitySleep 距离门控）。 */
    public void tickZone(ZoneContext zone, long nowMs, long deltaMs) {
        if (zone == null || zone.getPlayerUids().isEmpty()) {
            return;
        }
        WorldTimeService.Period period = worldTimeService.currentPeriod();
        for (ZoneContext.ZoneNpc npc : zone.getNpcs().values()) {
            if (npc == null || npc.getPos() == null) {
                continue;
            }
            NpcScheduleConfigRepository.ScheduleCfg schedule = scheduleRepo.find(npc.getNpcId());
            if (schedule == null) {
                continue;
            }
            if (!npc.isVisible() && npc.getBehavior() == DailyRoutineBehavior.Kind.DESPAWN) {
                continue;
            }
            if (!entitySleepService.shouldUpdate(zone, npc.getEntityId(),
                    npc.getPos().getX(), npc.getPos().getZ(), nowMs)) {
                continue;
            }
            if (npc.getBehavior() != DailyRoutineBehavior.Kind.PATROL) {
                continue;
            }
            String group = npc.getWaypointGroup();
            boolean moved = DailyRoutineBehavior.stepWaypointMove(
                    npc, schedule, group, schedule.moveSpeed(), Math.max(16L, deltaMs));
            if (moved) {
                syncNpcPosToScenes(zone, npc);
                // 到达切换路点时刷新客户端目标，避免仅靠本地插值漂移
                if (npc.getWaypointIndex() != npc.getLastNotifiedWaypointIndex()) {
                    npc.setLastNotifiedWaypointIndex(npc.getWaypointIndex());
                    pushBehavior(zone, npc, period, false);
                }
            }
        }
    }

    private void applyPeriod(ZoneContext zone, ZoneContext.ZoneNpc npc,
                             WorldTimeService.Period period, boolean notify) {
        NpcScheduleConfigRepository.ScheduleCfg schedule = scheduleRepo.find(npc.getNpcId());
        if (schedule == null) {
            return;
        }
        DailyRoutineBehavior.Applied applied = DailyRoutineBehavior.resolve(schedule, period);
        DailyRoutineBehavior.Kind prev = npc.getBehavior();
        npc.setBehavior(applied.kind());
        npc.setAnimHint(applied.animHint());
        npc.setInteractionSpot(applied.interactionSpot());
        npc.setVisible(applied.visible());
        npc.setWaypointIndex(0);
        npc.setLastNotifiedWaypointIndex(-1);

        NpcScheduleConfigRepository.PeriodCfg matched = null;
        for (NpcScheduleConfigRepository.PeriodCfg p : schedule.periods()) {
            if (p != null && NpcScheduleConfigRepository.normalizePeriodKey(p.period())
                    .equalsIgnoreCase(period.skybox())) {
                matched = p;
                break;
            }
        }
        npc.setWaypointGroup(matched != null ? matched.waypointGroup() : null);

        if (applied.targetPos() != null) {
            npc.setTargetX(applied.targetPos().getX());
            npc.setTargetY(applied.targetPos().getY());
            npc.setTargetZ(applied.targetPos().getZ());
            if (applied.snapToTarget() || applied.kind() == DailyRoutineBehavior.Kind.DESPAWN) {
                npc.getPos().setX(applied.targetPos().getX());
                npc.getPos().setY(applied.targetPos().getY());
                npc.getPos().setZ(applied.targetPos().getZ());
            }
        } else if (applied.kind() == DailyRoutineBehavior.Kind.IDLE && npc.getHomePos() != null) {
            // 无目标时保持原地；可选回出生点
        }

        projectVisibility(zone, npc);
        syncNpcPosToScenes(zone, npc);

        if (notify && (prev != applied.kind() || applied.kind() == DailyRoutineBehavior.Kind.PATROL)) {
            pushBehavior(zone, npc, period, true);
        }
    }

    private void projectVisibility(ZoneContext zone, ZoneContext.ZoneNpc npc) {
        for (Long uid : zone.getPlayerUids()) {
            SceneContext scene = sceneManager.getByPlayerUid(uid);
            if (scene == null) {
                continue;
            }
            if (npc.isVisible()) {
                SceneContext.NpcState existing = scene.getNpc(npc.getEntityId());
                if (existing == null) {
                    scene.addNpc(npc.toSceneState());
                }
            } else {
                scene.removeNpc(npc.getEntityId());
            }
        }
    }

    private void syncNpcPosToScenes(ZoneContext zone, ZoneContext.ZoneNpc npc) {
        if (!npc.isVisible()) {
            return;
        }
        for (Long uid : zone.getPlayerUids()) {
            SceneContext scene = sceneManager.getByPlayerUid(uid);
            if (scene == null) {
                continue;
            }
            SceneContext.NpcState local = scene.getNpc(npc.getEntityId());
            if (local == null) {
                scene.addNpc(npc.toSceneState());
                continue;
            }
            if (local.getPos() != null && npc.getPos() != null) {
                local.getPos().setX(npc.getPos().getX());
                local.getPos().setY(npc.getPos().getY());
                local.getPos().setZ(npc.getPos().getZ());
            }
        }
    }

    private void pushBehavior(ZoneContext zone, ZoneContext.ZoneNpc npc,
                              WorldTimeService.Period period, boolean periodSwitch) {
        SceneSystemProto.SceneNpcBehaviorScNotify.Builder b =
                SceneSystemProto.SceneNpcBehaviorScNotify.newBuilder()
                        .setEntityId(npc.getEntityId())
                        .setNpcId(npc.getNpcId())
                        .setBehavior(npc.getBehavior() != null
                                ? npc.getBehavior().toProto()
                                : SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_IDLE)
                        .setPeriod(WorldTimeService.toProto(period))
                        .setAnimHint(npc.getAnimHint() != null ? npc.getAnimHint() : "")
                        .setTransitionMs((int) (periodSwitch ? 1200 : DEFAULT_TRANSITION_MS))
                        .setVisible(npc.isVisible());
        if (npc.getInteractionSpot() != null) {
            b.setInteractionSpot(npc.getInteractionSpot());
        }
        if (npc.getPos() != null) {
            b.setPos(SceneSystemProto.SceneVec3.newBuilder()
                    .setX(npc.getPos().getX())
                    .setY(npc.getPos().getY())
                    .setZ(npc.getPos().getZ())
                    .build());
        }
        if (npc.getBehavior() == DailyRoutineBehavior.Kind.PATROL
                || npc.getBehavior() == DailyRoutineBehavior.Kind.SLEEP
                || npc.getBehavior() == DailyRoutineBehavior.Kind.WORK
                || npc.getBehavior() == DailyRoutineBehavior.Kind.OPEN_SHOP) {
            b.setTargetPos(SceneSystemProto.SceneVec3.newBuilder()
                    .setX(npc.getTargetX())
                    .setY(npc.getTargetY())
                    .setZ(npc.getTargetZ())
                    .build());
        }
        sceneSyncBroadcaster.broadcastNpcBehavior(zone.getZoneId(),
                npc.getPos() != null ? npc.getPos().getX() : 0f,
                npc.getPos() != null ? npc.getPos().getZ() : 0f,
                b.build());
    }
}
