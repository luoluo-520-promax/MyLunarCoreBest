package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.repo.MonsterConfigRepository;
import cn.itcast.demo.mylunarcore.repo.NpcConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Zone 世界实体权威：首次进场播种、投影到 SceneContext、击杀/刷新同步。
 */
@Service
public class ZoneWorldService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, ZoneWorldService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ZoneManager zoneManager;
    private final SceneManager sceneManager;
    private final SceneSyncBroadcaster sceneSyncBroadcaster;
    private final MonsterConfigRepository monsterConfigRepository;
    private final NpcConfigRepository npcConfigRepository;
    private final EncounterConfigRepository encounterConfigRepository;
    private final ObjectProvider<EntityBehaviorService> entityBehaviorService;

    public ZoneWorldService(ZoneManager zoneManager,
                            SceneManager sceneManager,
                            SceneSyncBroadcaster sceneSyncBroadcaster,
                            MonsterConfigRepository monsterConfigRepository,
                            NpcConfigRepository npcConfigRepository,
                            EncounterConfigRepository encounterConfigRepository,
                            ObjectProvider<EntityBehaviorService> entityBehaviorService) {
        this.zoneManager = zoneManager;
        this.sceneManager = sceneManager;
        this.sceneSyncBroadcaster = sceneSyncBroadcaster;
        this.monsterConfigRepository = monsterConfigRepository;
        this.npcConfigRepository = npcConfigRepository;
        this.encounterConfigRepository = encounterConfigRepository;
        this.entityBehaviorService = entityBehaviorService;
    }

    /**
     * 确保 Zone 已播种，并将存活世界实体投影到玩家 SceneContext。
     */
    public void seedAndProject(SceneContext scene, String groupsJson) {
        ZoneContext zone = zoneManager.getOrCreate(scene.getPlaneId(), scene.getFloorId());
        synchronized (zone) {
            if (!zone.isWorldSeeded()) {
                parseGroupsIntoZone(zone, groupsJson, scene.getPlayerPos());
                zone.markWorldSeeded();
                log.info("zone world seeded, zoneId={}, monsters={}, props={}, npcs={}",
                        zone.getZoneId(), zone.getMonsters().size(), zone.getProps().size(), zone.getNpcs().size());
            }
        }
        projectAliveToScene(zone, scene);
    }

    public void projectAliveToScene(ZoneContext zone, SceneContext scene) {
        scene.clearWorldEntities();
        for (ZoneContext.ZoneMonster m : zone.getMonsters().values()) {
            if (m.isAlive()) {
                scene.addMonster(m.toSceneState());
            }
        }
        for (ZoneContext.ZoneProp p : zone.getProps().values()) {
            scene.addProp(p.toSceneState());
        }
        for (ZoneContext.ZoneNpc n : zone.getNpcs().values()) {
            if (n.isVisible()) {
                scene.addNpc(n.toSceneState());
            }
        }
    }

    /**
     * 战后击杀：Zone 权威删除/刷新，并同步所有同 Zone 玩家视图。
     */
    public ZoneContext.ZoneMonster despawnMonster(int zoneId, int entityId, long nowMillis) {
        ZoneContext zone = zoneManager.get(zoneId);
        if (zone == null) {
            return null;
        }
        EncounterConfig.EncounterEntry entry = null;
        ZoneContext.ZoneMonster existing = zone.getMonster(entityId);
        if (existing != null) {
            entry = encounterConfigRepository.current().find(existing.getMonsterId());
        }
        int refreshSeconds = entry != null ? Math.max(0, entry.refreshSeconds()) : 0;
        ZoneContext.ZoneMonster killed;
        synchronized (zone) {
            killed = zone.killMonster(entityId, refreshSeconds, nowMillis);
        }
        if (killed == null) {
            return null;
        }
        for (Long uid : zone.getPlayerUids()) {
            SceneContext scene = sceneManager.getByPlayerUid(uid);
            if (scene != null) {
                scene.removeMonster(entityId);
            }
        }
        sceneSyncBroadcaster.broadcastMonsterRemoved(zoneId, entityId,
                killed.getPos().getX(), killed.getPos().getZ());
        log.info("zone monster despawned, zoneId={}, entityId={}, monsterId={}, refreshSeconds={}",
                zoneId, entityId, killed.getMonsterId(), refreshSeconds);
        return killed;
    }

    /**
     * 刷新到期怪物，并向 Zone 内玩家推送 add。
     */
    public void tickRefresh(ZoneContext zone, long nowMillis) {
        List<ZoneContext.ZoneMonster> revived;
        synchronized (zone) {
            revived = zone.refreshDueMonsters(nowMillis);
        }
        for (ZoneContext.ZoneMonster m : revived) {
            for (Long uid : zone.getPlayerUids()) {
                SceneContext scene = sceneManager.getByPlayerUid(uid);
                if (scene != null) {
                    scene.addMonster(m.toSceneState());
                }
            }
            sceneSyncBroadcaster.broadcastMonsterAdded(zone.getZoneId(), m);
        }
    }

    public ZoneContext.ZoneProp updatePropState(int zoneId, int entityId, int state) {
        ZoneContext zone = zoneManager.get(zoneId);
        if (zone == null) {
            return null;
        }
        ZoneContext.ZoneProp prop = zone.getProp(entityId);
        if (prop == null) {
            return null;
        }
        prop.setState(state);
        for (Long uid : zone.getPlayerUids()) {
            SceneContext scene = sceneManager.getByPlayerUid(uid);
            if (scene != null) {
                SceneContext.PropState local = scene.getProp(entityId);
                if (local != null) {
                    local.setState(state);
                }
            }
        }
        return prop;
    }

    private void parseGroupsIntoZone(ZoneContext zone, String groupsJson, SceneContext.ScenePos fallbackPos) {
        if (groupsJson == null || groupsJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(groupsJson);
            EncounterConfig encounter = encounterConfigRepository.current();

            JsonNode monstersNode = first(root, "monsters", "monster");
            if (monstersNode != null && monstersNode.isArray()) {
                for (JsonNode m : monstersNode) {
                    ZoneContext.ZoneMonster zm = buildMonster(zone, m, fallbackPos, encounter);
                    if (zm != null) {
                        zone.putMonster(zm);
                    }
                }
            }
            JsonNode npcsNode = first(root, "npcs", "npc");
            if (npcsNode != null && npcsNode.isArray()) {
                EntityBehaviorService behavior = entityBehaviorService.getIfAvailable();
                for (JsonNode n : npcsNode) {
                    ZoneContext.ZoneNpc zn = buildNpc(zone, n, fallbackPos);
                    if (zn != null) {
                        zone.putNpc(zn);
                        if (behavior != null) {
                            behavior.bindNpc(zone, zn);
                        }
                    }
                }
            }
            JsonNode propsNode = first(root, "props", "prop");
            if (propsNode != null && propsNode.isArray()) {
                for (JsonNode p : propsNode) {
                    ZoneContext.ZoneProp zp = buildProp(zone, p, fallbackPos);
                    if (zp != null) {
                        zone.putProp(zp);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("parse groups into zone failed, zoneId={}", zone.getZoneId(), e);
        }
    }

    private ZoneContext.ZoneMonster buildMonster(ZoneContext zone, JsonNode node,
                                                 SceneContext.ScenePos fallback,
                                                 EncounterConfig encounter) {
        int monsterId = parseInt(node, "monster_id", "monsterId", "id");
        if (monsterId == 0 && node.isNumber()) {
            monsterId = node.asInt();
        }
        if (monsterId <= 0) {
            return null;
        }
        MonsterConfigRepository.MonsterRow cfg = monsterConfigRepository.findById(monsterId);
        if (cfg == null) {
            return null;
        }
        int level = parseInt(node, "level", "custom_level", "customLevel");
        if (level <= 0) {
            level = cfg.getLevel();
        }
        int baseLevel = cfg.getLevel() <= 0 ? 1 : cfg.getLevel();
        int maxHp = (int) Math.max(1, ((long) cfg.getHp()) * level / baseLevel);
        SceneContext.ScenePos pos = parsePos(node, fallback);
        List<Integer> buffs = parseBuffs(node, cfg.getBuffsJson());
        ZoneContext.ZoneMonster zm = new ZoneContext.ZoneMonster(
                zone.nextEntityId(), monsterId, level, maxHp, maxHp, pos, buffs);
        EncounterConfig.EncounterEntry entry = encounter.find(monsterId);
        float defaultAggro = encounter.defaultAggroRadius() > 0 ? (float) encounter.defaultAggroRadius() : 5f;
        if (entry != null) {
            zm.setAggressive(entry.aggressive() == null || entry.aggressive());
            float radius = entry.aggroRadius() != null && entry.aggroRadius() > 0
                    ? entry.aggroRadius().floatValue() : defaultAggro;
            zm.setAggroRadius(radius);
        } else {
            zm.setAggressive(false);
            zm.setAggroRadius(defaultAggro);
        }
        return zm;
    }

    private ZoneContext.ZoneNpc buildNpc(ZoneContext zone, JsonNode node, SceneContext.ScenePos fallback) {
        int npcId = parseInt(node, "npc_id", "npcId", "id");
        if (npcId == 0 && node.isNumber()) {
            npcId = node.asInt();
        }
        if (npcId <= 0) {
            return null;
        }
        NpcConfigRepository.NpcRow cfg = npcConfigRepository.findById(npcId);
        if (cfg == null) {
            return null;
        }
        return new ZoneContext.ZoneNpc(zone.nextEntityId(), npcId, cfg.getRogueEventId(), parsePos(node, fallback));
    }

    private ZoneContext.ZoneProp buildProp(ZoneContext zone, JsonNode node, SceneContext.ScenePos fallback) {
        int propId = parseInt(node, "prop_id", "propId", "id");
        if (propId == 0 && node.isNumber()) {
            propId = node.asInt();
        }
        if (propId <= 0) {
            return null;
        }
        int state = parseInt(node, "state", "prop_state");
        if (state < 0) {
            state = 0;
        }
        return new ZoneContext.ZoneProp(zone.nextEntityId(), propId, state, parsePos(node, fallback));
    }

    private static List<Integer> parseBuffs(JsonNode groupNode, String cfgBuffsJson) {
        JsonNode buffsNode = first(groupNode, "buffs", "buff_ids", "buffIds");
        if (buffsNode != null && buffsNode.isArray()) {
            List<Integer> out = new ArrayList<>();
            for (JsonNode b : buffsNode) {
                if (b.isNumber()) {
                    out.add(b.asInt());
                }
            }
            return out;
        }
        if (cfgBuffsJson == null || cfgBuffsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode arr = MAPPER.readTree(cfgBuffsJson);
            if (arr != null && arr.isArray()) {
                List<Integer> out = new ArrayList<>();
                for (JsonNode b : arr) {
                    if (b.isNumber()) {
                        out.add(b.asInt());
                    }
                }
                return out;
            }
        } catch (Exception ignore) {
            // keep empty
        }
        return Collections.emptyList();
    }

    private static SceneContext.ScenePos parsePos(JsonNode node, SceneContext.ScenePos fallback) {
        JsonNode posNode = first(node, "pos", "position");
        if (posNode != null && posNode.isObject()) {
            return new SceneContext.ScenePos(
                    asFloat(posNode.get("x"), fallback.getX()),
                    asFloat(posNode.get("y"), fallback.getY()),
                    asFloat(posNode.get("z"), fallback.getZ()));
        }
        if (node.get("pos_x") == null && node.get("pos_y") == null && node.get("pos_z") == null) {
            return new SceneContext.ScenePos(fallback.getX(), fallback.getY(), fallback.getZ());
        }
        return new SceneContext.ScenePos(
                asFloat(node.get("pos_x"), fallback.getX()),
                asFloat(node.get("pos_y"), fallback.getY()),
                asFloat(node.get("pos_z"), fallback.getZ()));
    }

    private static JsonNode first(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) {
                return v;
            }
        }
        return null;
    }

    private static int parseInt(JsonNode node, String... keys) {
        JsonNode v = first(node, keys);
        return v != null && v.isNumber() ? v.asInt() : 0;
    }

    private static float asFloat(JsonNode n, float fallback) {
        return n != null && n.isNumber() ? (float) n.asDouble() : fallback;
    }
}
