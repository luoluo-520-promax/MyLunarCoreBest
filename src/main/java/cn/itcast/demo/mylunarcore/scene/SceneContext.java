// 场景运行时上下文所在包
package cn.itcast.demo.mylunarcore.scene;

// 主循环可 tick 接口
import cn.itcast.demo.mylunarcore.common.Tickable;
// 场景系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
// Lombok Getter 生成
import lombok.Getter;
// Lombok Setter 生成
import lombok.Setter;

// 可变数组列表
import java.util.ArrayList;
// 不可变空集合工厂
import java.util.Collections;
// 可变哈希映射
import java.util.HashMap;
// 列表接口
import java.util.List;
// 映射接口
import java.util.Map;

/**
 * 单玩家当前场景运行时：怪物/NPC/道具状态与玩家坐标，实现 {@link Tickable} 供主循环驱动。
 */
@Getter // Lombok：为字段生成 getter
public class SceneContext implements Tickable { // 场景上下文，参与游戏主循环 tick

    private final long playerUid; // 玩家登录 uid（Channel 绑定）

    private final int planeId; // 位面 ID
    private final int floorId; // 楼层 ID
    private final int entryId; // 入口点 ID
    private final ScenePos playerPos; // 玩家当前三维坐标

    private final Map<Integer, MonsterState> monsters = new HashMap<>(); // entityId → 怪物运行时状态
    private final Map<Integer, NpcState> npcs = new HashMap<>(); // entityId → NPC 运行时状态
    private final Map<Integer, PropState> props = new HashMap<>(); // entityId → 道具运行时状态

    @Setter // Lombok：为 initialized 生成 setter
    private boolean initialized; // 场景实体是否已完成初始化加载

    /**
     * 创建未初始化的场景上下文。
     */
    public SceneContext(long playerUid,
                         int planeId,
                         int floorId,
                         int entryId,
                         ScenePos playerPos) { // 构造场景上下文
        this.playerUid = playerUid; // 保存玩家 uid
        this.planeId = planeId; // 保存位面 ID
        this.floorId = floorId; // 保存楼层 ID
        this.entryId = entryId; // 保存入口 ID
        this.playerPos = playerPos; // 保存玩家坐标
        this.initialized = false; // 初始未加载实体
    }

    /**
     * 游戏主循环每帧回调：可在此处理怪物 Buff 过期等（当前为占位）。
     */
    @Override
    public void onTick(long nowMillis, long deltaMillis) { // 主循环 tick 回调
        for (MonsterState m : monsters.values()) { // 遍历场景内所有怪物
            if (!m.getBuffs().isEmpty() && deltaMillis > 0) { // 有 Buff 且时间推进
                // Buff 过期 / AI tick 钩子可在此扩展，当前为占位
            }
        }
    }

    /** 按 entityId 获取怪物状态；不存在返回 null。 */
    public MonsterState getMonster(int entityId) { // 查询怪物
        return monsters.get(entityId); // 不存在则 null
    }

    /** 按 entityId 获取 NPC 状态；不存在返回 null。 */
    public NpcState getNpc(int entityId) { // 查询 NPC
        return npcs.get(entityId); // 不存在则 null
    }

    /** 按 entityId 获取道具状态；不存在返回 null。 */
    public PropState getProp(int entityId) { // 查询道具
        return props.get(entityId); // 不存在则 null
    }

    /** 注册怪物到场景索引。 */
    public void addMonster(MonsterState s) { // 添加怪物
        monsters.put(s.entityId, s); // 以 entityId 为键存入
    }

    /** 注册 NPC 到场景索引。 */
    public void addNpc(NpcState s) { // 添加 NPC
        npcs.put(s.entityId, s); // 以 entityId 为键存入
    }

    /** 注册道具到场景索引。 */
    public void addProp(PropState s) { // 添加道具
        props.put(s.entityId, s); // 以 entityId 为键存入
    }

    /**
     * 组装「进入场景」下行响应：场景信息 + 实体列表。
     */
    public SceneSystemProto.EnterSceneScRsp buildEnterSceneRsp(int retcode) { // 构建进入场景响应
        SceneSystemProto.SceneLoadInfo sceneInfo = SceneSystemProto.SceneLoadInfo.newBuilder() // 场景加载信息
                .setPlaneId(planeId) // 写入位面 ID
                .setFloorId(floorId) // 写入楼层 ID
                .setEntryId(entryId) // 写入入口 ID
                .setPosX(playerPos.x) // 写入玩家 X 坐标
                .setPosY(playerPos.y) // 写入玩家 Y 坐标
                .setPosZ(playerPos.z) // 写入玩家 Z 坐标
                .build(); // 完成 SceneLoadInfo 构建

        SceneSystemProto.EntityList entityList = SceneSystemProto.EntityList.newBuilder() // 场景实体列表
                .addAllMonsters(buildMonsterEntities()) // 写入怪物实体
                .addAllNpcs(buildNpcEntities()) // 写入 NPC 实体
                .addAllProps(buildPropEntities()) // 写入道具实体
                .build(); // 完成 EntityList 构建

        return SceneSystemProto.EnterSceneScRsp.newBuilder() // 组装进入场景响应
                .setRetcode(retcode) // 写入错误码（0=成功）
                .setSceneInfo(sceneInfo) // 写入场景加载信息
                .setEntityList(entityList) // 写入实体列表
                .build(); // 完成响应构建
    }

    /**
     * 组装「查询当前场景」下行响应，结构与进入场景类似。
     */
    public SceneSystemProto.GetCurSceneInfoScRsp buildCurSceneRsp(int retcode) { // 构建当前场景查询响应
        SceneSystemProto.SceneLoadInfo sceneInfo = SceneSystemProto.SceneLoadInfo.newBuilder() // 场景加载信息
                .setPlaneId(planeId) // 写入位面 ID
                .setFloorId(floorId) // 写入楼层 ID
                .setEntryId(entryId) // 写入入口 ID
                .setPosX(playerPos.x) // 写入玩家 X
                .setPosY(playerPos.y) // 写入玩家 Y
                .setPosZ(playerPos.z) // 写入玩家 Z
                .build(); // 完成 SceneLoadInfo 构建

        SceneSystemProto.EntityList entityList = SceneSystemProto.EntityList.newBuilder() // 场景实体列表
                .addAllMonsters(buildMonsterEntities()) // 写入怪物
                .addAllNpcs(buildNpcEntities()) // 写入 NPC
                .addAllProps(buildPropEntities()) // 写入道具
                .build(); // 完成 EntityList 构建

        return SceneSystemProto.GetCurSceneInfoScRsp.newBuilder() // 组装查询响应
                .setRetcode(retcode) // 写入错误码
                .setSceneInfo(sceneInfo) // 写入场景信息
                .setEntityList(entityList) // 写入实体列表
                .build(); // 完成响应构建
    }

    /** 将内存怪物状态转为协议 MonsterEntity 列表。 */
    private List<SceneSystemProto.MonsterEntity> buildMonsterEntities() { // 构建怪物协议列表
        if (monsters.isEmpty()) { // 无怪物
            return Collections.emptyList(); // 返回空列表
        }
        List<SceneSystemProto.MonsterEntity> list = new ArrayList<>(); // 协议怪物列表
        for (MonsterState s : monsters.values()) { // 遍历所有怪物
            list.add(SceneSystemProto.MonsterEntity.newBuilder() // 组装单只怪物
                    .setEntityId(s.entityId) // 写入场景实体 ID
                    .setMonsterId(s.monsterId) // 写入配置怪物 ID
                    .setLevel(s.level) // 写入等级
                    .setHp(s.hp) // 写入当前 HP
                    .setMaxHp(s.maxHp) // 写入最大 HP
                    .setPos(toProtoPos(s.pos)) // 写入坐标
                    .addAllBuffs(s.buffs) // 写入 Buff 列表
                    .build()); // 完成单条构建
        }
        return list; // 返回完整列表
    }

    /** 将内存 NPC 状态转为协议 NpcEntity 列表。 */
    private List<SceneSystemProto.NpcEntity> buildNpcEntities() { // 构建 NPC 协议列表
        if (npcs.isEmpty()) { // 无 NPC
            return Collections.emptyList(); // 返回空列表
        }
        List<SceneSystemProto.NpcEntity> list = new ArrayList<>(); // 协议 NPC 列表
        for (NpcState s : npcs.values()) { // 遍历所有 NPC
            list.add(SceneSystemProto.NpcEntity.newBuilder() // 组装单个 NPC
                    .setEntityId(s.entityId) // 写入场景实体 ID
                    .setNpcId(s.npcId) // 写入配置 NPC ID
                    .setRogueEventId(s.rogueEventId) // 写入关联 Rogue 事件 ID
                    .setPos(toProtoPos(s.pos)) // 写入坐标
                    .build()); // 完成单条构建
        }
        return list; // 返回完整列表
    }

    /** 将内存道具状态转为协议 PropEntity 列表。 */
    private List<SceneSystemProto.PropEntity> buildPropEntities() { // 构建道具协议列表
        if (props.isEmpty()) { // 无道具
            return Collections.emptyList(); // 返回空列表
        }
        List<SceneSystemProto.PropEntity> list = new ArrayList<>(); // 协议道具列表
        for (PropState s : props.values()) { // 遍历所有道具
            list.add(SceneSystemProto.PropEntity.newBuilder() // 组装单个道具
                    .setEntityId(s.entityId) // 写入场景实体 ID
                    .setPropId(s.propId) // 写入配置道具 ID
                    .setState(s.state) // 写入道具状态（0=关闭 1=开启）
                    .setPos(toProtoPos(s.pos)) // 写入坐标
                    .build()); // 完成单条构建
        }
        return list; // 返回完整列表
    }

    /** 将内部 ScenePos 转为协议 SceneVec3。 */
    private static SceneSystemProto.SceneVec3 toProtoPos(ScenePos p) { // 坐标转 Protobuf
        return SceneSystemProto.SceneVec3.newBuilder() // 组装三维向量
                .setX(p.x) // 写入 X
                .setY(p.y) // 写入 Y
                .setZ(p.z) // 写入 Z
                .build(); // 完成构建
    }

    /** 场景内三维坐标（浮点，与协议 SceneVec3 对应）。 */
    @Getter // Lombok：生成 getter
    @Setter // Lombok：生成 setter
    public static class ScenePos { // 场景坐标值对象
        private float x; // X 轴坐标
        private float y; // Y 轴坐标
        private float z; // Z 轴坐标

        public ScenePos(float x, float y, float z) { // 构造三维坐标
            this.x = x; // 保存 X
            this.y = y; // 保存 Y
            this.z = z; // 保存 Z
        }
    }

    /** 场景内怪物运行时状态。 */
    @Getter // Lombok：生成 getter
    @Setter // Lombok：生成 setter（hp/maxHp 可变更）
    public static class MonsterState { // 怪物运行时快照
        private final int entityId; // 场景实体 ID（运行时分配）
        private final int monsterId; // 怪物配置模板 ID
        private final int level; // 怪物等级
        private int hp; // 当前生命值
        private int maxHp; // 最大生命值
        private final ScenePos pos; // 场景坐标
        private final List<Integer> buffs; // 当前 Buff ID 列表

        public MonsterState(int entityId,
                              int monsterId,
                              int level,
                              int hp,
                              int maxHp,
                              ScenePos pos,
                              List<Integer> buffs) { // 构造怪物状态
            this.entityId = entityId; // 保存实体 ID
            this.monsterId = monsterId; // 保存配置 ID
            this.level = level; // 保存等级
            this.hp = hp; // 保存当前 HP
            this.maxHp = maxHp; // 保存最大 HP
            this.pos = pos; // 保存坐标
            this.buffs = buffs == null ? Collections.<Integer>emptyList() : buffs; // null 时降级为空列表
        }
    }

    /** 场景内 NPC 运行时状态。 */
    @Getter // Lombok：生成 getter
    @Setter // Lombok：生成 setter
    public static class NpcState { // NPC 运行时快照
        private final int entityId; // 场景实体 ID
        private final int npcId; // NPC 配置 ID
        private final int rogueEventId; // 关联 Rogue 事件 ID
        private final ScenePos pos; // 场景坐标

        public NpcState(int entityId, int npcId, int rogueEventId, ScenePos pos) { // 构造 NPC 状态
            this.entityId = entityId; // 保存实体 ID
            this.npcId = npcId; // 保存配置 ID
            this.rogueEventId = rogueEventId; // 保存 Rogue 事件 ID
            this.pos = pos; // 保存坐标
        }
    }

    /** 场景内可交互道具运行时状态。 */
    @Getter // Lombok：生成 getter
    @Setter // Lombok：生成 setter（state 可变更）
    public static class PropState { // 道具运行时快照
        private final int entityId; // 场景实体 ID
        private final int propId; // 道具配置 ID
        private int state; // 道具状态（0=关闭 1=已拾取/开启）
        private final ScenePos pos; // 场景坐标

        public PropState(int entityId, int propId, int state, ScenePos pos) { // 构造道具状态
            this.entityId = entityId; // 保存实体 ID
            this.propId = propId; // 保存配置 ID
            this.state = state; // 保存初始状态
            this.pos = pos; // 保存坐标
        }
    }
}
