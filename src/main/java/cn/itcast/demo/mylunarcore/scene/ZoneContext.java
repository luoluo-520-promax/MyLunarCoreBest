package cn.itcast.demo.mylunarcore.scene;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 共享 Zone 运行时：玩家 AOI + 世界怪/宝箱权威实体。
 */
@Getter
public class ZoneContext {

    private final int zoneId;
    private final int planeId;
    private final int floorId;

    private final Set<Long> playerUids = ConcurrentHashMap.newKeySet();
    private final Map<Long, SceneContext.ScenePos> playerPositions = new ConcurrentHashMap<>();
    private final AoiGrid aoiGrid;

    /** Zone 级怪权威；entityId 在 Zone 内唯一。 */
    private final Map<Integer, ZoneMonster> monsters = new ConcurrentHashMap<>();
    /** Zone 级道具权威。 */
    private final Map<Integer, ZoneProp> props = new ConcurrentHashMap<>();
    /** NPC 仍可共享，便于同 Zone 一致对话点。 */
    private final Map<Integer, ZoneNpc> npcs = new ConcurrentHashMap<>();

    private final AtomicInteger entityIdSeed = new AtomicInteger(1_000_000);
    private volatile boolean worldSeeded;

    public ZoneContext(int zoneId, int planeId, int floorId) {
        this(zoneId, planeId, floorId, 20f);
    }

    public ZoneContext(int zoneId, int planeId, int floorId, float aoiCellSize) {
        this.zoneId = zoneId;
        this.planeId = planeId;
        this.floorId = floorId;
        this.aoiGrid = new AoiGrid(aoiCellSize);
    }

    public int nextEntityId() {
        return entityIdSeed.getAndIncrement();
    }

    public void markWorldSeeded() {
        this.worldSeeded = true;
    }

    public void addPlayer(long playerUid, SceneContext.ScenePos pos) {
        playerUids.add(playerUid);
        playerPositions.put(playerUid, pos);
        aoiGrid.update(playerUid, pos.getX(), pos.getZ());
    }

    public void removePlayer(long playerUid) {
        playerUids.remove(playerUid);
        playerPositions.remove(playerUid);
        aoiGrid.remove(playerUid);
    }

    public void updatePlayerPos(long playerUid, SceneContext.ScenePos pos) {
        playerPositions.put(playerUid, pos);
        aoiGrid.update(playerUid, pos.getX(), pos.getZ());
    }

    public Set<Long> nearbyPlayers(long playerUid) {
        SceneContext.ScenePos pos = playerPositions.get(playerUid);
        if (pos == null) {
            return Set.of();
        }
        return aoiGrid.nearby(playerUid, pos.getX(), pos.getZ());
    }

    /** 以世界坐标为中心的 AOI 邻近玩家（不含排除 uid，传 0 表示不排除）。 */
    public Set<Long> nearbyPlayersAt(float x, float z, long excludeUid) {
        return aoiGrid.nearby(excludeUid, x, z);
    }

    public void putMonster(ZoneMonster monster) {
        if (monster != null) {
            monsters.put(monster.getEntityId(), monster);
        }
    }

    public void putProp(ZoneProp prop) {
        if (prop != null) {
            props.put(prop.getEntityId(), prop);
        }
    }

    public void putNpc(ZoneNpc npc) {
        if (npc != null) {
            npcs.put(npc.getEntityId(), npc);
        }
    }

    public ZoneMonster getMonster(int entityId) {
        return monsters.get(entityId);
    }

    public ZoneProp getProp(int entityId) {
        return props.get(entityId);
    }

    /**
     * 击杀：标记死亡并安排刷新；返回被击杀快照。
     */
    public ZoneMonster killMonster(int entityId, int refreshSeconds, long nowMillis) {
        ZoneMonster m = monsters.get(entityId);
        if (m == null || !m.isAlive()) {
            return null;
        }
        m.setAlive(false);
        m.setHp(0);
        if (refreshSeconds > 0) {
            m.setRefreshAtMillis(nowMillis + refreshSeconds * 1000L);
        } else {
            m.setRefreshAtMillis(0);
            monsters.remove(entityId);
        }
        return m;
    }

    /**
     * 处理到期刷新，返回重新激活的怪物列表。
     */
    public List<ZoneMonster> refreshDueMonsters(long nowMillis) {
        List<ZoneMonster> revived = new ArrayList<>();
        for (ZoneMonster m : monsters.values()) {
            if (m.isAlive()) {
                continue;
            }
            if (m.getRefreshAtMillis() > 0 && nowMillis >= m.getRefreshAtMillis()) {
                m.setAlive(true);
                m.setHp(m.getMaxHp());
                m.setRefreshAtMillis(0);
                revived.add(m);
            }
        }
        return revived;
    }

    /**
     * Zone 共享怪物权威状态。
     */
    @Getter
    @Setter
    public static class ZoneMonster {
        private final int entityId;
        private final int monsterId;
        private final int level;
        private int hp;
        private int maxHp;
        private final SceneContext.ScenePos pos;
        private final List<Integer> buffs;
        private boolean alive = true;
        private long refreshAtMillis;
        private boolean aggressive = true;
        private float aggroRadius = 5f;

        public ZoneMonster(int entityId, int monsterId, int level, int hp, int maxHp,
                           SceneContext.ScenePos pos, List<Integer> buffs) {
            this.entityId = entityId;
            this.monsterId = monsterId;
            this.level = level;
            this.hp = hp;
            this.maxHp = maxHp;
            this.pos = pos;
            this.buffs = buffs == null ? Collections.emptyList() : List.copyOf(buffs);
        }

        public SceneContext.MonsterState toSceneState() {
            return new SceneContext.MonsterState(entityId, monsterId, level, hp, maxHp, pos, buffs);
        }
    }

    @Getter
    @Setter
    public static class ZoneProp {
        private final int entityId;
        private final int propId;
        private int state;
        private final SceneContext.ScenePos pos;

        public ZoneProp(int entityId, int propId, int state, SceneContext.ScenePos pos) {
            this.entityId = entityId;
            this.propId = propId;
            this.state = state;
            this.pos = pos;
        }

        public SceneContext.PropState toSceneState() {
            return new SceneContext.PropState(entityId, propId, state, pos);
        }
    }

    @Getter
    @Setter
    public static class ZoneNpc {
        private final int entityId;
        private final int npcId;
        private final int rogueEventId;
        private final SceneContext.ScenePos pos;
        /** 播种出生点，作息回家参考。 */
        private SceneContext.ScenePos homePos;
        private DailyRoutineBehavior.Kind behavior = DailyRoutineBehavior.Kind.IDLE;
        private String animHint = "idle";
        private String interactionSpot;
        private String waypointGroup;
        private int waypointIndex;
        private int lastNotifiedWaypointIndex = -1;
        private float targetX;
        private float targetY;
        private float targetZ;
        private boolean visible = true;

        public ZoneNpc(int entityId, int npcId, int rogueEventId, SceneContext.ScenePos pos) {
            this.entityId = entityId;
            this.npcId = npcId;
            this.rogueEventId = rogueEventId;
            this.pos = pos;
            if (pos != null) {
                this.homePos = new SceneContext.ScenePos(pos.getX(), pos.getY(), pos.getZ());
                this.targetX = pos.getX();
                this.targetY = pos.getY();
                this.targetZ = pos.getZ();
            }
        }

        public SceneContext.NpcState toSceneState() {
            return new SceneContext.NpcState(entityId, npcId, rogueEventId, pos);
        }
    }
}
