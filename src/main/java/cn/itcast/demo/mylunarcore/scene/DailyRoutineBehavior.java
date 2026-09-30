package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;

/**
 * 单帧日程行为：WaypointMove / IdleAnimation / InteractionSpot / 夜间休眠或消失。
 */
public final class DailyRoutineBehavior {

    public enum Kind {
        IDLE,
        PATROL,
        SLEEP,
        WORK,
        DESPAWN,
        OPEN_SHOP;

        public static Kind fromConfig(String raw) {
            if (raw == null || raw.isBlank()) {
                return IDLE;
            }
            String key = raw.trim().toUpperCase().replace('-', '_');
            return switch (key) {
                case "PATROL", "WAYPOINT_MOVE" -> PATROL;
                case "SLEEP" -> SLEEP;
                case "WORK", "INTERACT", "INTERACTION_SPOT" -> WORK;
                case "DESPAWN", "DESPAWN_OR_SLEEP", "NIGHT_HIDE" -> DESPAWN;
                case "OPEN_SHOP", "SHOP", "VEND" -> OPEN_SHOP;
                case "IDLE", "IDLE_ANIMATION" -> IDLE;
                default -> IDLE;
            };
        }

        /** DESPAWN_OR_SLEEP：有 home 则 SLEEP，否则 DESPAWN。 */
        public static Kind resolveDespawnOrSleep(String raw, boolean hasHome) {
            if (raw == null) {
                return IDLE;
            }
            String key = raw.trim().toUpperCase().replace('-', '_');
            if ("DESPAWN_OR_SLEEP".equals(key) || "NIGHT_HIDE".equals(key)) {
                return hasHome ? SLEEP : DESPAWN;
            }
            return fromConfig(raw);
        }

        public SceneSystemProto.NpcBehaviorType toProto() {
            return switch (this) {
                case IDLE -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_IDLE;
                case PATROL -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_PATROL;
                case SLEEP -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_SLEEP;
                case WORK -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_WORK;
                case DESPAWN -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_DESPAWN;
                case OPEN_SHOP -> SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_OPEN_SHOP;
            };
        }
    }

    public record Applied(Kind kind, String animHint, String interactionSpot,
                          SceneContext.ScenePos targetPos, boolean visible, boolean snapToTarget) {}

    private DailyRoutineBehavior() {
    }

    /**
     * 按当前时段解析应挂载的行为；不推进移动（移动见 {@link #stepWaypointMove}）。
     */
    public static Applied resolve(NpcScheduleConfigRepository.ScheduleCfg schedule,
                                  WorldTimeService.Period period) {
        if (schedule == null) {
            return new Applied(Kind.IDLE, "idle", null, null, true, false);
        }
        NpcScheduleConfigRepository.PeriodCfg matched = matchPeriod(schedule, period);
        String behaviorRaw = matched != null ? matched.behavior() : schedule.defaultBehavior();
        String anim = matched != null && matched.animHint() != null && !matched.animHint().isBlank()
                ? matched.animHint()
                : (schedule.idleAnim() != null ? schedule.idleAnim() : "idle");
        String spotId = matched != null ? matched.interactionSpot() : null;
        String homeId = matched != null ? matched.homeSpot() : null;
        String group = matched != null ? matched.waypointGroup() : null;

        boolean hasHome = homeId != null && !homeId.isBlank()
                && schedule.spots() != null && schedule.spots().containsKey(homeId);
        Kind kind = Kind.resolveDespawnOrSleep(behaviorRaw, hasHome);

        SceneContext.ScenePos target = null;
        boolean snap = false;
        boolean visible = true;

        switch (kind) {
            case PATROL -> {
                target = firstWaypoint(schedule, group);
            }
            case WORK, OPEN_SHOP -> {
                target = spotOf(schedule, spotId);
                snap = target != null;
                if (kind == Kind.OPEN_SHOP && (anim == null || anim.isBlank() || "idle".equals(anim))) {
                    anim = "vend";
                }
            }
            case SLEEP -> {
                target = spotOf(schedule, homeId);
                snap = target != null;
                visible = true;
                if (anim == null || anim.isBlank()) {
                    anim = "sleep";
                }
            }
            case DESPAWN -> {
                target = spotOf(schedule, homeId);
                visible = false;
                if (anim == null || anim.isBlank()) {
                    anim = "despawn";
                }
            }
            case IDLE -> {
                if (anim == null || anim.isBlank()) {
                    anim = schedule.idleAnim() != null ? schedule.idleAnim() : "idle_stand";
                }
            }
        }
        return new Applied(kind, anim, spotId, target, visible, snap);
    }

    /**
     * 沿路点组推进；返回是否发生位置变化。到达当前目标后切换下一路点。
     */
    public static boolean stepWaypointMove(ZoneContext.ZoneNpc npc,
                                           NpcScheduleConfigRepository.ScheduleCfg schedule,
                                           String waypointGroup,
                                           float moveSpeed,
                                           long deltaMs) {
        if (npc == null || schedule == null || waypointGroup == null || waypointGroup.isBlank()) {
            return false;
        }
        var points = schedule.waypointGroups().get(waypointGroup);
        if (points == null || points.isEmpty() || npc.getPos() == null) {
            return false;
        }
        int idx = Math.floorMod(npc.getWaypointIndex(), points.size());
        NpcScheduleConfigRepository.VecCfg wp = points.get(idx);
        float tx = wp.xOr(npc.getPos().getX());
        float ty = wp.yOr(npc.getPos().getY());
        float tz = wp.zOr(npc.getPos().getZ());
        npc.setTargetX(tx);
        npc.setTargetY(ty);
        npc.setTargetZ(tz);

        float dx = tx - npc.getPos().getX();
        float dz = tz - npc.getPos().getZ();
        float distSq = dx * dx + dz * dz;
        float step = Math.max(0.1f, moveSpeed) * (deltaMs / 1000f);
        if (distSq <= step * step || distSq < 0.04f) {
            npc.getPos().setX(tx);
            npc.getPos().setY(ty);
            npc.getPos().setZ(tz);
            npc.setWaypointIndex(Math.floorMod(idx + 1, points.size()));
            var next = points.get(npc.getWaypointIndex());
            npc.setTargetX(next.xOr(tx));
            npc.setTargetY(next.yOr(ty));
            npc.setTargetZ(next.zOr(tz));
            return true;
        }
        float dist = (float) Math.sqrt(distSq);
        float nx = npc.getPos().getX() + dx / dist * step;
        float nz = npc.getPos().getZ() + dz / dist * step;
        float ny = npc.getPos().getY() + (ty - npc.getPos().getY()) * Math.min(1f, step / dist);
        npc.getPos().setX(nx);
        npc.getPos().setY(ny);
        npc.getPos().setZ(nz);
        return true;
    }

    private static NpcScheduleConfigRepository.PeriodCfg matchPeriod(
            NpcScheduleConfigRepository.ScheduleCfg schedule, WorldTimeService.Period period) {
        if (schedule.periods() == null || period == null) {
            return null;
        }
        String sky = period.skybox();
        for (NpcScheduleConfigRepository.PeriodCfg p : schedule.periods()) {
            if (p == null || p.period() == null) {
                continue;
            }
            if (NpcScheduleConfigRepository.normalizePeriodKey(p.period()).equalsIgnoreCase(sky)) {
                return p;
            }
        }
        return null;
    }

    private static SceneContext.ScenePos firstWaypoint(NpcScheduleConfigRepository.ScheduleCfg schedule,
                                                       String group) {
        if (group == null || schedule.waypointGroups() == null) {
            return null;
        }
        var list = schedule.waypointGroups().get(group);
        if (list == null || list.isEmpty()) {
            return null;
        }
        var v = list.get(0);
        return new SceneContext.ScenePos(v.xOr(0), v.yOr(0), v.zOr(0));
    }

    private static SceneContext.ScenePos spotOf(NpcScheduleConfigRepository.ScheduleCfg schedule, String id) {
        if (id == null || id.isBlank() || schedule.spots() == null) {
            return null;
        }
        var v = schedule.spots().get(id);
        if (v == null) {
            return null;
        }
        return new SceneContext.ScenePos(v.xOr(0), v.yOr(0), v.zOr(0));
    }
}
