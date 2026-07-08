// 场景 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.scene;

// 场景系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
// 怪物配置仓储
import cn.itcast.demo.mylunarcore.repo.MonsterConfigRepository;
// NPC 配置仓储
import cn.itcast.demo.mylunarcore.repo.NpcConfigRepository;
// 场景 plane/floor 配置仓储
import cn.itcast.demo.mylunarcore.repo.SceneConfigRepository;
// 召唤单位配置仓储
import cn.itcast.demo.mylunarcore.repo.SummonUnitConfigRepository;
// 场景运行时上下文
import cn.itcast.demo.mylunarcore.scene.SceneContext;
// 场景道具实体状态
import cn.itcast.demo.mylunarcore.scene.SceneContext.PropState;
// 场景怪物实体状态
import cn.itcast.demo.mylunarcore.scene.SceneContext.MonsterState;
// 场景 NPC 实体状态
import cn.itcast.demo.mylunarcore.scene.SceneContext.NpcState;
// 运行时管理器（分配/缓存 SceneContext）
import cn.itcast.demo.mylunarcore.scene.SceneManager;
// Jackson JSON 节点
import com.fasterxml.jackson.databind.JsonNode;
// Jackson JSON 读写器
import com.fasterxml.jackson.databind.ObjectMapper;
// Netty 客户端连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Channel 自定义属性键
import io.netty.util.AttributeKey;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring @Service 业务 Bean
import org.springframework.stereotype.Service;

// 可变数组列表
import java.util.ArrayList;
// 不可变空集合工厂
import java.util.Collections;
// 列表接口
import java.util.List;
// 原子整型（实体 ID 自增种子）
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景协议业务：进入场景、查询当前信息、NPC 交互等，装配 {@link SceneContext} 并读各类 ConfigRepository。
 */
@Service // Spring Bean：场景玩法 Netty 消息分发入口
public class SceneNettyService {

    // 本类日志记录器（场景业务分类）
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, SceneNettyService.class); // 绑定场景业务分类 SLF4J 日志

    // Channel 上绑定玩家 uid 的属性键
    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid"); // 定义 Channel 属性键名 playerUid

    // Jackson JSON 读写器（解析 scene_config.groups JSON）
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 运行时索引：playerUid ↔ SceneContext
    private final SceneManager sceneManager; // 场景运行时索引 playerUid→SceneContext
    // 场景 plane/floor 与 groups 配置加载
    private final SceneConfigRepository sceneConfigRepository; // 场景 plane/floor 配置仓储
    // 怪物模板配置加载
    private final MonsterConfigRepository monsterConfigRepository; // 怪物配置仓储
    // NPC 模板配置加载
    private final NpcConfigRepository npcConfigRepository; // NPC 配置仓储
    @SuppressWarnings("unused") // 召唤单位配置仓储暂未使用，保留供后续扩展
    private final SummonUnitConfigRepository summonUnitConfigRepository; // 召唤单位配置仓储

    // 场景实体 ID 自增种子（起始 1000000，避免与玩家 uid 冲突）
    private final AtomicInteger entityIdSeed = new AtomicInteger(1000000);

    /**
     * 构造器注入场景相关依赖。
     */
    public SceneNettyService(SceneManager sceneManager,
                              SceneConfigRepository sceneConfigRepository,
                              MonsterConfigRepository monsterConfigRepository,
                              NpcConfigRepository npcConfigRepository,
                              SummonUnitConfigRepository summonUnitConfigRepository) {
        this.sceneManager = sceneManager; // 保存运行时管理器引用
        this.sceneConfigRepository = sceneConfigRepository; // 保存场景配置仓储引用
        this.monsterConfigRepository = monsterConfigRepository; // 保存怪物配置仓储引用
        this.npcConfigRepository = npcConfigRepository; // 保存 NPC 配置仓储引用
        this.summonUnitConfigRepository = summonUnitConfigRepository; // 保存召唤单位配置仓储引用
    }

    /**
     * 进入场景：加载 plane/floor 配置、解析 groups 实体并注册 SceneContext。
     */
    public SceneSystemProto.EnterSceneScRsp handleEnterScene(SceneSystemProto.EnterSceneCsReq req, Channel channel) { // 处理进入场景请求
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.EnterSceneScRsp.newBuilder() // 组装进入场景失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .build(); // 完成响应构建
        }

        int planeId = (int) req.getPlaneId(); // 读取位面 ID
        int floorId = (int) req.getFloorId(); // 读取楼层 ID
        int entryId = (int) req.getEntryId(); // 读取入口点 ID

        SceneContext.ScenePos playerPos = new SceneContext.ScenePos(req.getPosX(), req.getPosY(), req.getPosZ()); // 玩家初始坐标
        SceneContext ctx = new SceneContext(uid, planeId, floorId, entryId, playerPos); // 创建场景运行时上下文

        SceneConfigRepository.SceneRow row = sceneConfigRepository.findGroups(planeId, floorId); // 按 plane/floor 查场景配置
        if (row == null) { // 场景配置行不存在
            return SceneSystemProto.EnterSceneScRsp.newBuilder() // 组装进入场景失败响应
                    .setRetcode(2) // retcode=2：planeId/floorId 对应场景配置不存在
                    .build(); // 完成响应构建
        }

        int nextEntityId = entityIdSeed.getAndAdd(1); // 分配本场景首个实体 ID 并推进种子
        parseGroupsIntoScene(ctx, row.getGroupsJson(), nextEntityId); // 解析 groups JSON 填充怪物/NPC/道具
        ctx.setInitialized(true); // 标记场景已初始化

        sceneManager.put(uid, ctx); // 注册玩家场景上下文
        return ctx.buildEnterSceneRsp(0); // 由 SceneContext 组装进入场景成功响应
    }

    /**
     * 查询玩家当前所在场景的完整快照（怪物、NPC、道具等）。
     */
    public SceneSystemProto.GetCurSceneInfoScRsp handleGetCurSceneInfo(SceneSystemProto.GetCurSceneInfoCsReq req, Channel channel) { // 处理查询当前场景信息
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.GetCurSceneInfoScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .build(); // 完成响应构建
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid); // 按玩家 uid 查找场景上下文
        if (ctx == null || !ctx.isInitialized()) { // 玩家尚未进入场景或上下文缺失
            return SceneSystemProto.GetCurSceneInfoScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(2) // retcode=2：尚未进入场景或场景未初始化
                    .build(); // 完成响应构建
        }
        return ctx.buildCurSceneRsp(0); // 由 SceneContext 组装当前场景快照响应
    }

    /**
     * 与场景内 NPC 交互，返回对应对话 ID（演示实现：对话内容与选项为空）。
     */
    public SceneSystemProto.InteractNpcScRsp handleInteractNpc(SceneSystemProto.InteractNpcCsReq req, Channel channel) { // 处理 NPC 交互
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid); // 按玩家 uid 查找场景上下文
        if (ctx == null) { // 玩家尚未进入场景或上下文缺失
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(2) // retcode=2：玩家不在任何场景中
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        int entityId = (int) req.getEntityId(); // 请求交互的 NPC 实体 ID
        NpcState npc = ctx.getNpc(entityId); // 在场景上下文中查找 NPC
        if (npc == null) { // 场景内找不到该 NPC 实体
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(3) // retcode=3：目标 NPC 实体不存在
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        NpcConfigRepository.NpcRow npcRow = npcConfigRepository.findById(npc.getNpcId()); // 按模板 ID 查 NPC 配置
        int dialogueId = npcRow == null ? 0 : npcRow.getDialogueId(); // 取对话 ID，无配置则为 0

        return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互成功响应
                .setRetcode(0) // retcode=0：成功
                .setEntityId(req.getEntityId()) // 回写 NPC 实体 ID
                .setDialogueId(dialogueId) // 写入对应对话 ID
                .setDialogueContent("") // 演示实现：对话内容留空
                .addAllOptions(Collections.<SceneSystemProto.NpcDialogOption>emptyList()) // 演示实现：对话选项留空
                .build(); // 完成响应构建
    }

    /**
     * 拾取场景内道具，更新道具状态为已开启（演示实现：奖励列表为空）。
     */
    public SceneSystemProto.PickupPropScRsp handlePickupProp(SceneSystemProto.PickupPropCsReq req, Channel channel) { // 处理拾取场景道具
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.PickupPropScRsp.newBuilder() // 组装拾取失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setEntityId(req.getEntityId()) // 回写请求的道具实体 ID
                    .setPropState(0) // 道具状态未变更
                    .build(); // 完成响应构建
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid); // 按玩家 uid 查找场景上下文
        if (ctx == null) { // 玩家尚未进入场景或上下文缺失
            return SceneSystemProto.PickupPropScRsp.newBuilder() // 组装拾取失败响应
                    .setRetcode(2) // retcode=2：玩家不在任何场景中
                    .setEntityId(req.getEntityId()) // 回写请求的道具实体 ID
                    .setPropState(0) // 道具状态未变更
                    .build(); // 完成响应构建
        }

        int entityId = (int) req.getEntityId(); // 请求拾取的道具实体 ID
        PropState prop = ctx.getProp(entityId); // 在场景上下文中查找道具
        if (prop == null) { // 场景内找不到该道具实体
            return SceneSystemProto.PickupPropScRsp.newBuilder() // 组装拾取失败响应
                    .setRetcode(3) // retcode=3：目标道具实体不存在
                    .setEntityId(req.getEntityId()) // 回写请求的道具实体 ID
                    .setPropState(0) // 道具状态未变更
                    .build(); // 完成响应构建
        }

        // state: 0=closed（未开启）, 1=open（已拾取/已开启）
        prop.setState(1); // 将道具状态更新为已开启

        // 当前 schema 未提供奖励表，演示实现返回空奖励列表
        return SceneSystemProto.PickupPropScRsp.newBuilder() // 组装拾取成功响应
                .setRetcode(0) // retcode=0：成功
                .setEntityId(req.getEntityId()) // 回写道具实体 ID
                .addAllRewardItems(Collections.<SceneSystemProto.SceneRewardItem>emptyList()) // 演示不发具体道具奖励
                .setPropState(1) // 回写更新后的道具状态（已开启）
                .build(); // 完成响应构建
    }

    /**
     * 触发场景事件（演示实现：仅确认触发，不更新实体状态）。
     */
    public SceneSystemProto.TriggerSceneEventScRsp handleTriggerSceneEvent(SceneSystemProto.TriggerSceneEventCsReq req, Channel channel) { // 处理触发场景事件
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.TriggerSceneEventScRsp.newBuilder() // 组装触发失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setEntityId(req.getEntityId()) // 回写请求的事件实体 ID
                    .build(); // 完成响应构建
        }

        // 事件系统依赖额外配置表（当前 schema 未包含），演示实现仅确认触发并返回空更新列表
        return SceneSystemProto.TriggerSceneEventScRsp.newBuilder() // 组装触发成功响应
                .setRetcode(0) // retcode=0：成功
                .setEntityId(req.getEntityId()) // 回写事件实体 ID
                .build(); // 完成响应构建
    }

    /**
     * 使用治疗泉（演示实现：不回血、仅标记复活点已设置）。
     */
    public SceneSystemProto.UseHealingSpringScRsp handleUseHealingSpring(SceneSystemProto.UseHealingSpringCsReq req, Channel channel) { // 处理使用治疗泉
        // 治疗泉效果依赖额外配置表（当前 schema 未包含），演示实现固定返回 0 回血
        return SceneSystemProto.UseHealingSpringScRsp.newBuilder() // 组装使用治疗泉成功响应
                .setRetcode(0) // retcode=0：成功
                .setEntityId(req.getEntityId()) // 回写治疗泉实体 ID
                .setHealEffect(
                        SceneSystemProto.HealEffect.newBuilder()
                        .setHpRecovered(0)
                        .build()
                ) // 演示：恢复 HP 为 0
                .setRespawnPointSet(true) // 标记复活点已设置
                .build(); // 完成响应构建
    }

    /**
     * 解析 scene_config.groups JSON，将怪物、NPC、道具实体写入 SceneContext。
     */
    private void parseGroupsIntoScene(SceneContext ctx, String groupsJson, int nextEntityId) { // 解析 groups JSON 填充场景实体
        if (groupsJson == null || groupsJson.trim().isEmpty()) { // groupsJson 为空则无需解析
            return;
        }

        try { // 捕获 JSON 解析异常，避免整场景加载失败
            JsonNode root = MAPPER.readTree(groupsJson); // 将 groups JSON 解析为 Jackson 节点树
            int eid = nextEntityId; // 当前待分配的实体 ID（逐条递增）

            // 解析怪物列表
            JsonNode monstersNode = getFirstNonNull(root, "monsters", "monster"); // 兼容 monsters/monster 两种键名
            if (monstersNode != null && monstersNode.isArray()) {
                for (JsonNode m : monstersNode) { // 遍历每条怪物配置
                    MonsterState s = buildMonsterState(ctx, eid++, m); // 构建怪物状态并分配 entityId
                    if (s != null) {
                        ctx.addMonster(s); // 注册到场景上下文
                    }
                }
            }

            // 解析 NPC 列表
            JsonNode npcsNode = getFirstNonNull(root, "npcs", "npc"); // 兼容 npcs/npc 两种键名
            if (npcsNode != null && npcsNode.isArray()) {
                for (JsonNode n : npcsNode) { // 遍历每条 NPC 配置
                    NpcState s = buildNpcState(ctx, eid++, n); // 构建 NPC 状态并分配 entityId
                    if (s != null) {
                        ctx.addNpc(s); // 注册到场景上下文
                    }
                }
            }

            // 解析道具列表
            JsonNode propsNode = getFirstNonNull(root, "props", "prop"); // 兼容 props/prop 两种键名
            if (propsNode != null && propsNode.isArray()) {
                for (JsonNode p : propsNode) { // 遍历每条道具配置
                    PropState s = buildPropState(ctx, eid++, p); // 构建道具状态并分配 entityId
                    if (s != null) {
                        ctx.addProp(s); // 注册到场景上下文
                    }
                }
            }
        } catch (Exception e) { // JSON 解析或配置加载失败
            log.warn("parse scene_config.groups failed, groupsJson={}", groupsJson, e); // 记录解析异常便于排查
        }
    }

    /**
     * 由 groups JSON 单条节点与怪物配置表构建 {@link MonsterState}。
     */
    private MonsterState buildMonsterState(SceneContext ctx, int entityId, JsonNode node) { // 构建单只怪物运行时状态
        int monsterId = parseIntFlexible(node, "monster_id", "monsterId", "id"); // 优先读显式 monster_id 字段
        if (monsterId == 0) {
            if (node.isNumber()) { // 节点本身为数字时直接作为 monsterId
                monsterId = node.asInt();
            } else {
                return null; // 无法解析 monsterId，跳过该条
            }
        }

        MonsterConfigRepository.MonsterRow cfg = monsterConfigRepository.findById(monsterId); // 查怪物模板配置
        if (cfg == null) {
            return null; // 配置不存在，跳过该条
        }

        int level = parseIntFlexible(node, "level", "custom_level", "customLevel"); // 读取自定义等级
        if (level <= 0) {
            level = cfg.getLevel(); // 未指定则使用模板默认等级
        }
        int baseLevel = cfg.getLevel() <= 0 ? 1 : cfg.getLevel(); // 模板基准等级（防除零）

        int maxHp = (int) Math.max(1, ((long) cfg.getHp()) * level / baseLevel); // 按等级比例缩放最大 HP
        int hp = maxHp; // 初始 HP 等于最大 HP

        List<Integer> buffs = parseBuffs(node, cfg.getBuffsJson()); // 解析 buff 列表（组内优先，否则读配置）
        SceneContext.ScenePos pos = parsePos(node, ctx.getPlayerPos()); // 解析坐标，缺省回退玩家位置

        return new MonsterState(entityId, monsterId, level, hp, maxHp, pos, buffs); // 组装怪物运行时状态
    }

    /**
     * 由 groups JSON 单条节点与 NPC 配置表构建 {@link NpcState}。
     */
    private NpcState buildNpcState(SceneContext ctx, int entityId, JsonNode node) { // 构建单个 NPC 运行时状态
        int npcId = parseIntFlexible(node, "npc_id", "npcId", "id"); // 优先读显式 npc_id 字段
        if (npcId == 0) {
            if (node.isNumber()) { // 节点本身为数字时直接作为 npcId
                npcId = node.asInt();
            } else {
                return null; // 无法解析 npcId，跳过该条
            }
        }

        NpcConfigRepository.NpcRow cfg = npcConfigRepository.findById(npcId); // 查 NPC 模板配置
        if (cfg == null) {
            return null; // 配置不存在，跳过该条
        }

        SceneContext.ScenePos pos = parsePos(node, ctx.getPlayerPos()); // 解析坐标，缺省回退玩家位置
        return new NpcState(entityId, npcId, cfg.getRogueEventId(), pos); // 组装 NPC 运行时状态
    }

    /**
     * 由 groups JSON 单条节点构建 {@link PropState}（不依赖额外配置表）。
     */
    private PropState buildPropState(SceneContext ctx, int entityId, JsonNode node) { // 构建单个道具运行时状态
        int propId = parseIntFlexible(node, "prop_id", "propId", "id"); // 优先读显式 prop_id 字段
        if (propId == 0) {
            if (node.isNumber()) { // 节点本身为数字时直接作为 propId
                propId = node.asInt();
            } else {
                return null; // 无法解析 propId，跳过该条
            }
        }

        int state = parseIntFlexible(node, "state", "prop_state"); // 读取道具开关状态
        SceneContext.ScenePos pos = parsePos(node, ctx.getPlayerPos()); // 解析坐标，缺省回退玩家位置
        if (state < 0) {
            state = 0; // 非法状态归一化为 0（未开启）
        }

        return new PropState(entityId, propId, state, pos); // 组装道具运行时状态
    }

    /**
     * 解析 buff ID 列表：优先读 group 节点内 buffs，否则回退怪物配置 JSON。
     */
    private List<Integer> parseBuffs(JsonNode groupNode, String cfgBuffsJson) { // 解析 buff ID 列表
        // 优先级：group 节点内 buffs → 配置表 buffsJson
        JsonNode buffsNode = getFirstNonNull(groupNode, "buffs", "buff_ids", "buffIds"); // 兼容多种键名
        if (buffsNode != null && buffsNode.isArray()) {
            List<Integer> out = new ArrayList<>(); // 收集 buff ID
            for (JsonNode b : buffsNode) {
                if (b.isNumber()) {
                    out.add(b.asInt()); // 数字节点直接取整
                }
            }
            return out;
        }

        if (cfgBuffsJson == null || cfgBuffsJson.trim().isEmpty()) { // 配置表 buffsJson 为空
            return Collections.emptyList(); // 无 buff
        }

        try { // 尝试解析配置表 buffsJson
            JsonNode arr = MAPPER.readTree(cfgBuffsJson); // 将 buffsJson 解析为数组
            if (arr != null && arr.isArray()) {
                List<Integer> out = new ArrayList<>(); // 收集 buff ID
                for (JsonNode b : arr) {
                    if (b.isNumber()) {
                        out.add(b.asInt()); // 数字节点直接取整
                    }
                }
                return out;
            }
        } catch (Exception ignore) {
            // 解析失败则忽略，返回空列表
        }
        return Collections.emptyList(); // 默认无 buff
    }

    /**
     * 从 JSON 节点解析三维坐标；支持嵌套 pos 对象或 pos_x/y/z 平铺键，缺省回退 fallback。
     */
    private SceneContext.ScenePos parsePos(JsonNode node, SceneContext.ScenePos fallback) { // 解析实体坐标
        JsonNode posNode = getFirstNonNull(node, "pos", "position"); // 优先读嵌套 pos/position 对象
        if (posNode != null && posNode.isObject()) {
            float x = parseFloat(posNode.get("x"), fallback.getX()); // 读 x 坐标
            float y = parseFloat(posNode.get("y"), fallback.getY()); // 读 y 坐标
            float z = parseFloat(posNode.get("z"), fallback.getZ()); // 读 z 坐标
            return new SceneContext.ScenePos(x, y, z); // 组装坐标
        }
        // 平铺键 pos_x / pos_y / pos_z
        float x = parseFloat(node.get("pos_x"), fallback.getX()); // 读平铺 x
        float y = parseFloat(node.get("pos_y"), fallback.getY()); // 读平铺 y
        float z = parseFloat(node.get("pos_z"), fallback.getZ()); // 读平铺 z

        // 平铺键均缺失时直接回退 fallback（避免与玩家位置产生无意义偏移）
        if (node.get("pos_x") == null && node.get("pos_y") == null && node.get("pos_z") == null) {
            return new SceneContext.ScenePos(fallback.getX(), fallback.getY(), fallback.getZ()); // 使用回退坐标
        }
        return new SceneContext.ScenePos(x, y, z); // 组装解析后的坐标
    }

    /**
     * 按候选键名顺序返回 JSON 节点中第一个非 null 的值。
     */
    private JsonNode getFirstNonNull(JsonNode node, String... keys) { // 多键名兼容读取 JSON 子节点
        if (node == null) {
            return null; // 父节点为空
        }
        for (String k : keys) {
            JsonNode v = node.get(k); // 按键名取值
            if (v != null && !v.isNull()) {
                return v; // 找到第一个有效值
            }
        }
        return null; // 所有候选键均无有效值
    }

    /**
     * 按候选键名顺序解析整型字段，支持数字节点与文本数字。
     */
    private int parseIntFlexible(JsonNode node, String... keys) { // 多键名兼容解析整型
        if (node == null) {
            return 0; // 节点为空返回 0
        }
        for (String k : keys) {
            JsonNode v = node.get(k); // 按键名取值
            if (v != null && !v.isNull()) {
                if (v.isNumber()) {
                    return v.asInt(); // 数字节点直接取整
                }
                if (v.isTextual()) {
                    try { // 尝试将文本解析为整数
                        return Integer.parseInt(v.asText());
                    } catch (Exception ignore) {
                        return 0; // 文本非法则返回 0
                    }
                }
            }
        }
        return 0; // 所有候选键均无有效整型值
    }

    /**
     * 解析 JSON 浮点值，节点缺失或非法时回退 fallback。
     */
    private float parseFloat(JsonNode node, float fallback) { // 解析单精度浮点，非法时回退
        if (node == null || node.isNull()) {
            return fallback; // 节点缺失或为 null
        }
        if (node.isNumber()) {
            return (float) node.asDouble(); // 数字节点转 float
        }
        if (node.isTextual()) {
            try { // 尝试将文本解析为浮点
                return Float.parseFloat(node.asText());
            } catch (Exception ignore) {
                return fallback; // 文本非法则回退
            }
        }
        return fallback; // 非数字/文本类型则回退
    }
}
