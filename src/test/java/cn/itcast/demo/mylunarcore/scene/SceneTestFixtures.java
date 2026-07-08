package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.repo.MonsterConfigRepository;
import cn.itcast.demo.mylunarcore.repo.NpcConfigRepository;
import cn.itcast.demo.mylunarcore.repo.SceneConfigRepository;

import java.util.List;

/**
 * 场景模块测试用公共数据构造工具。
 */
final class SceneTestFixtures {

    static final long PLAYER_UID = 77L;
    static final int PLANE_ID = 1;
    static final int FLOOR_ID = 2;
    static final int ENTRY_ID = 3;
    static final float POS_X = 10.5f;
    static final float POS_Y = 0.0f;
    static final float POS_Z = -5.2f;

    static final int MONSTER_CONFIG_ID = 101;
    static final int NPC_CONFIG_ID = 501;
    static final int PROP_CONFIG_ID = 301;
    static final int NPC_DIALOGUE_ID = 9001;
    static final int NPC_ROGUE_EVENT_ID = 42;

    private SceneTestFixtures() {
    }

    static SceneContext.ScenePos playerPos() {
        return new SceneContext.ScenePos(POS_X, POS_Y, POS_Z);
    }

    static SceneContext createContext(long playerUid) {
        return new SceneContext(playerUid, PLANE_ID, FLOOR_ID, ENTRY_ID, playerPos());
    }

    static SceneContext createInitializedContext(long playerUid) {
        SceneContext ctx = createContext(playerUid);
        ctx.addMonster(monsterState(1000001, MONSTER_CONFIG_ID, 5, 500, 500, 1.0f, 2.0f, 3.0f, List.of(7, 8)));
        ctx.addNpc(npcState(1000002, NPC_CONFIG_ID, NPC_ROGUE_EVENT_ID, 4.0f, 5.0f, 6.0f));
        ctx.addProp(propState(1000003, PROP_CONFIG_ID, 0, 7.0f, 8.0f, 9.0f));
        ctx.setInitialized(true);
        return ctx;
    }

    static SceneContext.MonsterState monsterState(int entityId, int monsterId, int level,
                                                     int hp, int maxHp,
                                                     float x, float y, float z,
                                                     List<Integer> buffs) {
        return new SceneContext.MonsterState(
                entityId, monsterId, level, hp, maxHp,
                new SceneContext.ScenePos(x, y, z), buffs);
    }

    static SceneContext.NpcState npcState(int entityId, int npcId, int rogueEventId,
                                           float x, float y, float z) {
        return new SceneContext.NpcState(
                entityId, npcId, rogueEventId,
                new SceneContext.ScenePos(x, y, z));
    }

    static SceneContext.PropState propState(int entityId, int propId, int state,
                                             float x, float y, float z) {
        return new SceneContext.PropState(
                entityId, propId, state,
                new SceneContext.ScenePos(x, y, z));
    }

    static SceneConfigRepository.SceneRow sceneRow(int planeId, int floorId, String groupsJson) {
        return new SceneConfigRepository.SceneRow(planeId, floorId, groupsJson);
    }

    static String sampleGroupsJson() {
        return """
                {
                  "monsters": [
                    {"monster_id": 101, "level": 5, "pos": {"x": 1.0, "y": 2.0, "z": 3.0}, "buffs": [7, 8]}
                  ],
                  "npcs": [
                    {"npc_id": 501, "pos_x": 4.0, "pos_y": 5.0, "pos_z": 6.0}
                  ],
                  "props": [
                    {"prop_id": 301, "state": 0, "pos": {"x": 7.0, "y": 8.0, "z": 9.0}}
                  ]
                }
                """;
    }

    static MonsterConfigRepository.MonsterRow monsterRow(int id, int level, int hp, String buffsJson) {
        return new MonsterConfigRepository.MonsterRow(id, level, hp, buffsJson);
    }

    static NpcConfigRepository.NpcRow npcRow(int id, int dialogueId, int rogueEventId) {
        return new NpcConfigRepository.NpcRow(id, dialogueId, rogueEventId);
    }
}
