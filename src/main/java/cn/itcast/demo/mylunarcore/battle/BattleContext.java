// 战斗运行时状态包
package cn.itcast.demo.mylunarcore.battle;

// 技能行为仓储
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
// 波次配置 DTO
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
// JSON 节点树
import com.fasterxml.jackson.databind.JsonNode;
// Jackson ObjectMapper：解析技能参数 JSON
import com.fasterxml.jackson.databind.ObjectMapper;
// 可变数组列表
import java.util.ArrayList;
// 不可修改空列表常量
import java.util.Collections;
// 哈希映射
import java.util.HashMap;
// 有序参战集合
import java.util.LinkedHashSet;
// 列表接口
import java.util.List;
// 键值映射接口
import java.util.Map;
// 参战玩家集合视图
import java.util.Set;

/**
 * 战斗上下文。
 * <p>这是一次战斗在内存中的完整运行时快照，包含玩家、波次、实体状态、回合数和开始时间等信息。</p>
 * <p>这里的数据不是长期持久化的，而是随着战斗开始创建、在战斗过程中不断更新，战斗结束后会被移除。</p>
 * <p>换句话说，它的作用就是让战斗逻辑在处理攻击、结算、换波次等操作时，始终能读取到同一份战斗状态。</p>
 */
public class BattleContext {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 用于把技能参数 JSON 字符串解析成 JsonNode，避免每次解析都重复创建对象。

    private final Object lock = new Object(); // 同一战局内的共享锁，所有会改变战斗状态的操作都应尽量在这把锁保护下执行，避免并发结算互相覆盖。

    private final long battleId; // 这场战斗的唯一标识，用于在 BattleManager 中查找和移除对应上下文。
    private final int playerId; // 发起这场战斗的玩家角色 ID，便于结算、回查或日志定位。
    private final int lineupId; // 本场战斗使用的阵容配置 ID，表示玩家进入战斗时采用的是哪一套上阵方案。
    private final int battleStageId; // 当前战斗所属关卡 ID，用来标识战斗对应的剧情/副本/关卡进度。
    private final long startTimeSeconds; // 战斗开始时间戳（秒），可用于统计战斗耗时或判断是否超时。
    /** 多人共战参与者（含发起者）；Party 成员开战时扇入。 */
    private final Set<Integer> participantPlayerIds = new LinkedHashSet<>();

    private boolean ended; // 战斗是否已经结束；结束后通常不再继续推进回合或波次。

    private int turn = 1; // 当前战斗回合数，从 1 开始计数，表示战斗已经推进到了第几轮。
    private int currentWave = 1; // 当前正在处理的波次编号，从 1 开始，对应 waves 中的第几个波次。
    private final int waveCount; // 战斗总波次数，用来判断是否还有后续怪物波次需要切换。

    private final Map<Integer, EntityState> entities = new HashMap<>(); // 运行时实体表，保存玩家和怪物的当前生命、死亡状态等信息，战斗结算都依赖这份数据。
    /** 运行时实体 ID 分配（与配置怪 ID 分离，避免同波次同模板互相覆盖）。 */
    private int nextRuntimeEntityId = 1_000_000;
    private final List<WaveRuntime> waves; // 战斗的全部波次运行时数据，保存每一波怪物的配置结果，供切波和判定使用。

    /** 绑定的场景怪物 entityId；0 表示非场景遭遇战（直接 stage 开战）。 */
    private int sceneEntityUid;
    /** 场景怪物配置模板 ID，用于掉落查表。 */
    private int sceneMonsterId;
    /** 开战时玩家 uid，用于战后回场景。 */
    private long playerUid;
    private int returnPlaneId;
    private int returnFloorId;
    private int returnEntryId;
    private float returnPosX;
    private float returnPosY;
    private float returnPosZ;

    /** 自动战斗：掉线或玩家开启后由服务端代操作。 */
    private volatile boolean autoBattle;
    /** auto | disconnect | player */
    private volatile String autoReason = "";
    /** 1=PRIORITY_SKILL 2=PRIORITY_BASIC 3=SAVE_ENERGY */
    private volatile int autoStrategy = 1;
    /** 0默认 1集火精英 2优先破盾 */
    private volatile int targetFocus = 0;
    /** 玩家手动大招覆盖：>0 时下一拍 AI 优先执行该技能。 */
    private volatile int pendingManualSkillId;
    private volatile int pendingManualCasterId;
    private final java.util.List<Integer> pendingManualTargets = new java.util.ArrayList<>();
    /** 战斗倍速：仅允许 1/2/3，服务端压缩等待时间。 */
    private volatile int speedMultiplier = 1;
    /** 下一拍自动行动的最早时间（毫秒）。 */
    private volatile long nextActionAtMs;
    /** 队伍战技点（0~5 简化模型）。 */
    private volatile int teamSkillPoints = 3;
    /** 终结技充能百分比（0~100）。 */
    private volatile int ultEnergyPercent = 0;
    /** 微观干预后是否恢复 Auto 策略。 */
    private volatile boolean revertAutoAfterMicro = false;
    private volatile int savedAutoStrategy = 1;
    private volatile int savedTargetFocus = 0;
    /** 服务端权威顿帧结束墙钟（毫秒）；0=无进行中的 Hit-stop。 */
    private volatile long expectedHitStopEndMs;

    private BattleContext(long battleId,
                            int playerId,
                            int lineupId,
                            int battleStageId,
                            long startTimeSeconds,
                            List<WaveRuntime> waves) {
        this.battleId = battleId; // 记录当前战斗实例的唯一 ID，后续查找、结束、清理都靠它定位。
        this.playerId = playerId; // 记录发起战斗的玩家 ID，便于在战斗过程中识别主角。
        this.lineupId = lineupId; // 记录本次战斗使用的阵容 ID，方便回溯玩家进入战斗时的配置。
        this.battleStageId = battleStageId; // 记录关卡 ID，说明这场战斗属于哪个关卡或副本。
        this.startTimeSeconds = startTimeSeconds; // 记录战斗开始时间，后续可用于时长统计和超时控制。
        this.waves = waves == null ? Collections.<WaveRuntime>emptyList() : waves; // 保存所有波次的运行时结果；如果没有波次则使用空列表，避免空指针。
        this.waveCount = this.waves.size(); // 统计总波次数，供后续切波和结束判定使用。
        this.ended = false; // 刚创建战斗时默认处于进行中状态。
        this.participantPlayerIds.add(playerId);

        // 把玩家先加入实体表，这样后续技能、伤害和结算逻辑都可以直接通过 playerId 找到玩家状态。
        entities.put(playerId, new EntityState(playerId, 1000, false));
        // 如果这场战斗有怪物波次，就立即加载第 1 波，保证战斗创建后马上具备可结算的怪物数据。
        if (waveCount > 0) {
            switchToWave(1);
        }
    }

    /**
     * 绑定场景回落点与遭遇实体，供战后回世界与删怪。
     */
    public void bindWorldAnchor(long playerUid,
                                int sceneEntityUid,
                                int sceneMonsterId,
                                int planeId,
                                int floorId,
                                int entryId,
                                float posX,
                                float posY,
                                float posZ) {
        this.playerUid = playerUid;
        this.sceneEntityUid = sceneEntityUid;
        this.sceneMonsterId = sceneMonsterId;
        this.returnPlaneId = planeId;
        this.returnFloorId = floorId;
        this.returnEntryId = entryId;
        this.returnPosX = posX;
        this.returnPosY = posY;
        this.returnPosZ = posZ;
    }

    public boolean hasWorldAnchor() {
        return sceneEntityUid > 0 || returnPlaneId > 0;
    }

    /** 全部波次清剿完成（最后一波怪物全灭）。 */
    public boolean isAllWavesCleared() {
        if (waveCount <= 0) {
            return false;
        }
        return currentWave >= waveCount && isAllMonstersDeadInWave(currentWave);
    }

    /** 玩家实体已死亡。 */
    public boolean isPlayerDefeated() {
        EntityState player = entities.get(playerId);
        return player != null && player.isDead();
    }

    /**
     * 服务端权威胜负：1=胜，2=负，0=尚无法判定（不可采信客户端胜利）。
     */
    public int resolveAuthoritativeEndStatus() {
        if (isAllWavesCleared()) {
            return 1;
        }
        if (isPlayerDefeated()) {
            return 2;
        }
        return 0;
    }

    /**
     * 根据波次配置创建一个完整的战斗上下文。
     * <p>这个方法的作用是把“静态配置”转换成“可运行状态”：先把每个波次配置变成 `WaveRuntime`，
     * 再把战斗的基础信息一起封装到 `BattleContext` 中，供后续攻击、切波和结算流程使用。</p>
     */
    public static BattleContext createNew(long battleId,
                                           int playerId,
                                           int lineupId,
                                           int battleStageId,
                                           long startTimeSeconds,
                                           List<BattleMonsterWaveRepository.WaveConfig> waveConfigs) {
        List<WaveRuntime> waveRuntimes = new ArrayList<>(); // 用来接收每一波怪物的运行时结果。
        if (waveConfigs != null) { // 如果配置存在，就逐条转换成运行时对象。
            for (BattleMonsterWaveRepository.WaveConfig cfg : waveConfigs) {
                waveRuntimes.add(BattleMonsterWaveSimpleFactory.createWaveFromConfig(cfg));
            }
        }
        return new BattleContext(battleId, playerId, lineupId, battleStageId, startTimeSeconds, waveRuntimes); // 返回构建好的战斗上下文。
    }

    /**
     * 从断线/跨节点快照重建战局骨架（波次用占位，实体 HP/韧性按快照覆盖）。
     */
    public static BattleContext fromSnapshot(BattleSnapshot snap) {
        if (snap == null) {
            return null;
        }
        int waves = Math.max(1, snap.waveCount());
        List<WaveRuntime> placeholders = new ArrayList<>(waves);
        for (int i = 1; i <= waves; i++) {
            placeholders.add(new WaveRuntime(i, Collections.emptyList()));
        }
        BattleContext ctx = new BattleContext(
                snap.battleId(),
                snap.playerId(),
                snap.lineupId(),
                snap.battleStageId(),
                snap.startTimeSeconds(),
                placeholders);
        ctx.entities.clear();
        if (snap.entities() != null) {
            for (BattleSnapshot.EntitySnap e : snap.entities().values()) {
                EntityState state = new EntityState(e.id(), e.hp(), e.dead());
                state.setToughness(e.toughness(), Math.max(e.toughness(), 1));
                if (e.broken()) {
                    state.setToughness(0, Math.max(e.toughness(), 1));
                }
                state.setSkinId(e.skinId());
                ctx.entities.put(e.id(), state);
            }
        }
        if (snap.participantPlayerIds() != null) {
            for (Integer pid : snap.participantPlayerIds()) {
                if (pid != null && pid > 0) {
                    ctx.participantPlayerIds.add(pid);
                }
            }
        }
        ctx.turn = Math.max(1, snap.turn());
        ctx.currentWave = Math.max(1, Math.min(waves, snap.currentWave()));
        ctx.ended = snap.ended();
        return ctx;
    }

    /** 根据实体 ID 查询当前状态；如果实体不存在，返回 null。 */
    public EntityState getEntity(int entityId) {
        return entities.get(entityId); // 从实体表中读取玩家或怪物的实时状态。
    }

    /** 读取实体增量属性表；实体不存在时返回 null。 */
    public CombatAttributeSheet attributeSheetOf(int entityId) {
        EntityState e = entities.get(entityId);
        return e == null ? null : e.getAttributeSheet();
    }

    /** 回合数加 1，用于表示战斗已经推进到下一步。 */
    public void incrementTurn() {
        this.turn++; // 让当前回合数前进一轮，通常在一轮行动结算结束后调用。
    }

    /**
     * 切换到指定波次，并把该波次对应的怪物加入到实体列表中。
     * <p>这里不仅更新当前波次编号，还会把该波次的怪物实例注册到 `entities`，这样战斗逻辑才能继续读取它们的血量和死亡状态。</p>
     */
    public void switchToWave(int waveIndex) {
        this.currentWave = waveIndex; // 先标记当前处于第几波，这样外部读取上下文时能知道战斗进度。
        WaveRuntime wave = getWaveByWaveIndex(waveIndex); // 找到对应波次的运行时数据。
        if (wave == null) { // 如果波次不存在，说明配置有问题或已经越界，直接返回。
            return;
        }
        for (MonsterRuntime monster : wave.getMonsters()) {
            int runtimeId = allocateRuntimeEntityId();
            monster.bindRuntimeEntityId(runtimeId);
            EntityState entity = new EntityState(runtimeId, monster.getMaxHp(), false);
            int toughness = Math.max(30, monster.getLevel() * 15);
            entity.setToughness(toughness, toughness);
            entities.put(runtimeId, entity);
        }
    }

    private int allocateRuntimeEntityId() {
        return nextRuntimeEntityId++;
    }

    /** 在锁内注册实体（禁止外部直接 mutate {@link #getEntities()}）。 */
    public void putEntity(EntityState entity) {
        if (entity != null) {
            entities.put(entity.getId(), entity);
        }
    }

    /** 判断指定波次中的所有怪物是否已经死亡。 */
    public boolean isAllMonstersDeadInWave(int waveIndex) {
        WaveRuntime wave = getWaveByWaveIndex(waveIndex); // 获取指定波次。
        if (wave == null) { // 如果波次不存在，按“已清空”处理，避免阻塞流程。
            return true;
        }
        for (MonsterRuntime monster : wave.getMonsters()) { // 逐个检查本波次怪物的当前状态。
            EntityState entity = entities.get(monster.getRuntimeEntityId()); // 从实体表中拿到该怪物的实时状态。
            if (entity != null && !entity.isDead()) { // 只要有任意一个怪物还活着，就说明这一波还没打完。
                return false;
            }
        }
        return true; // 遍历完都没找到存活怪物，说明这一波已经全部死亡。
    }

    /**
     * 根据波次序号获取对应波次。
     * <p>waveIndex 从 1 开始，而 `waves` 列表下标从 0 开始，所以这里要减 1 才能正确取到对应元素。</p>
     */
    private WaveRuntime getWaveByWaveIndex(int waveIndex) {
        if (waveIndex <= 0 || waveIndex > waves.size()) { // 如果索引非法，直接返回 null，避免数组越界。
            return null;
        }
        return waves.get(waveIndex - 1); // 把 1-based 编号转换为 0-based 下标。
    }

    /**
     * 把技能在数据库中的行为配置，解析成运行时可直接使用的动作列表。
     * <p>技能通常不是一个单独动作，而是一串按顺序执行的行为。这里会去 `maze_skill_action` 表中读取某个技能对应的所有行为记录，
     * 并把每条记录转换成 `MazeSkillActionRuntime`，这样战斗结算时就不用再直接操作数据库。</p>
     * <p>如果某条行为的 `paramsJson` 不是合法 JSON，这里不会抛出异常打断整场战斗，而是把参数当作空值继续处理，保证战斗流程尽量可运行。</p>
     */
    public List<MazeSkillActionRuntime> getResolvedActionsForSkill(int skillId,
                                                                  MazeSkillActionRepository actionRepository) {
        if (skillId <= 0) { // 如果技能 ID 非法，就不需要继续查表。
            return Collections.emptyList();
        }
        List<MazeSkillActionRepository.MazeSkillActionRow> rows = actionRepository.findBySkillId(skillId); // 查询该技能对应的所有行为配置。
        if (rows == null || rows.isEmpty()) { // 没有配置就返回空列表，表示这个技能没有可执行行为。
            return Collections.emptyList();
        }
        List<MazeSkillActionRuntime> runtime = new ArrayList<>(); // 存放转换后的运行时动作。
        for (MazeSkillActionRepository.MazeSkillActionRow r : rows) { // 逐条把数据库记录转成运行时对象。
            JsonNode paramsNode = null; // 保存解析后的参数 JSON。
            if (r.getParamsJson() != null && !r.getParamsJson().trim().isEmpty()) { // 如果参数字符串不为空，就尝试解析。
                try {
                    paramsNode = MAPPER.readTree(r.getParamsJson());
                } catch (Exception ignore) {
                    paramsNode = null; // 解析失败时直接当作没有参数，避免中断战斗流程。
                }
            }
            runtime.add(new MazeSkillActionRuntime(r.getActionType(), paramsNode)); // 把动作类型和参数一起封装起来。
        }
        return runtime; // 返回该技能完整的运行时动作链。
    }

    /**
     * 返回实体表不可变视图；变更请走 {@link #putEntity} 并持有 {@link #getLock()}。
     */
    public Map<Integer, EntityState> getEntities() {
        return Collections.unmodifiableMap(entities);
    }

    /** 实体表快照副本（可安全跨线程只读遍历）。 */
    public Map<Integer, EntityState> snapshotEntities() {
        return Map.copyOf(entities);
    }

    /** 单条已解析的技能行为：包含行为类型以及已经解析好的 JSON 参数。 */
    public static class MazeSkillActionRuntime {
        private final int actionType; // 行为类型，用来决定这条配置到底是伤害、加 buff、击杀还是其他战斗动作。
        private final JsonNode paramsNode; // 行为参数的 JSON 树，保存这条动作所需的详细配置。

        public MazeSkillActionRuntime(int actionType, JsonNode paramsNode) {
            this.actionType = actionType;
            this.paramsNode = paramsNode;
        }

        public int getActionType() {
            return actionType;
        }

        public JsonNode getParamsNode() {
            return paramsNode;
        }

        /**
         * 尝试从参数中读取血量变化值。
         * <p>战斗配置里有的写法会把伤害写成正数，有的写法会把治疗写成负数，所以这里直接返回一个整数，
         * 由上层逻辑决定这个值最终是加血还是扣血。</p>
         * <p>同时兼容多个字段名，是为了适配不同来源的技能配置格式。</p>
         */
        public int tryParseHpDelta() {
            if (paramsNode == null || paramsNode.isNull()) {
                return 0;
            }
            return tryParseInt(paramsNode, "hp_change", "hpChange", "hp", "delta", "value");
        }

        /**
         * 解析要附加到目标上的 Buff ID。
         * <p>这个方法同时支持“单个 buff”与“多个 buff”两种配置方式：
         * 如果配置里直接给了一个 buffId，就返回一个长度为 1 的列表；如果给的是 buffIds 数组，就把数组逐个读出来。</p>
         */
        public List<Integer> tryParseBuffIds() {
            if (paramsNode == null || paramsNode.isNull()) {
                return Collections.emptyList();
            }
            List<Integer> out = new ArrayList<>();

            JsonNode single = first(paramsNode, "buff_id", "buffId", "buff");
            if (single != null && single.isNumber()) {
                out.add(single.asInt());
                return out;
            }

            JsonNode arr = first(paramsNode, "buff_ids", "buffIds");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    if (n.isNumber()) {
                        out.add(n.asInt());
                    }
                }
                return out;
            }

            return Collections.emptyList();
        }

        /**
         * 判断参数是否表示“直接击杀目标”。
         * <p>有些配置会写成布尔值 `true/false`，有些会写成数字 `1/0`，还有些会写成字符串，
         * 这里统一做兼容解析，避免因为配置格式不一致导致行为失效。</p>
         */
        public boolean tryParseKillTrue() {
            if (paramsNode == null || paramsNode.isNull()) {
                return false;
            }
            JsonNode v = first(paramsNode, "kill", "dead", "is_dead", "set_dead");
            if (v != null) {
                if (v.isBoolean()) {
                    return v.booleanValue();
                }
                if (v.isNumber()) {
                    return v.asInt() != 0;
                }
                if (v.isTextual()) {
                    return "true".equalsIgnoreCase(v.asText()) || "1".equals(v.asText());
                }
            }
            // 兼容种子数据 {"ratio":1.0} 作为击杀意图
            JsonNode ratio = first(paramsNode, "ratio", "kill_ratio");
            return ratio != null && ratio.isNumber() && ratio.asDouble() > 0;
        }

        /** 解析召唤物配置 ID。 */
        public int tryParseSummonId() {
            if (paramsNode == null || paramsNode.isNull()) {
                return 0;
            }
            return tryParseInt(paramsNode, "summonId", "summon_id", "summon", "id");
        }

        /** 解析韧性削减量；无配置时返回 0。 */
        public int tryParseToughnessDelta() {
            if (paramsNode == null || paramsNode.isNull()) {
                return 0;
            }
            return tryParseInt(paramsNode, "toughness", "toughness_change", "break", "breakDamage");
        }

        /**
         * 从多个候选字段名中取出第一个有效值。
         * <p>这个方法的意义是让 JSON 参数保持向后兼容：同一个配置项即使换了字段名，运行时也能尽量读出来。</p>
         */
        private JsonNode first(JsonNode node, String... keys) {
            for (String k : keys) {
                JsonNode v = node.get(k);
                if (v != null && !v.isNull()) {
                    return v;
                }
            }
            return null;
        }

        /**
         * 把 JSON 值转换成整数。
         * <p>支持数字和数字字符串两种形式；如果解析失败，就返回 0，让调用方可以按“未配置/无效果”处理。</p>
         */
        private int tryParseInt(JsonNode node, String... keys) {
            JsonNode v = first(node, keys);
            if (v == null) {
                return 0;
            }
            if (v.isNumber()) {
                return v.asInt();
            }
            if (v.isTextual()) {
                try {
                    return Integer.parseInt(v.asText());
                } catch (Exception e) {
                    return 0;
                }
            }
            return 0;
        }
    }

    /** 返回这把锁对象，用于保证同一战局内的结算过程线程安全。 */
    public Object getLock() {
        return lock;
    }

    /** 返回当前回合数，回合数越大表示战斗推进得越久。 */
    public int getTurn() {
        return turn;
    }

    /** 返回当前波次编号，用来表示当前正在处理第几批怪物。 */
    public int getCurrentWave() {
        return currentWave;
    }

    /** 返回总波次数，用来判断这场战斗一共有多少批怪物要处理。 */
    public int getWaveCount() {
        return waveCount;
    }

    /** 返回战斗开始时间戳（秒）。 */
    public long getStartTimeSeconds() {
        return startTimeSeconds;
    }

    /** 返回当前战斗所属的关卡 ID。 */
    public int getBattleStageId() {
        return battleStageId;
    }

    /** 返回这场战斗的唯一 ID。 */
    public long getBattleId() {
        return battleId;
    }

    /** 返回战斗是否已经结束。 */
    public boolean isEnded() {
        return ended;
    }

    /** 标记战斗是否结束，true 表示后续不应再继续进行战斗结算。 */
    public void setEnded(boolean ended) {
        this.ended = ended;
    }

    /** 返回发起这场战斗的玩家角色 ID。 */
    public int getPlayerId() {
        return playerId;
    }

    /** 是否为共战参与者（含发起者）。 */
    public boolean isParticipant(int pid) {
        return participantPlayerIds.contains(pid);
    }

    /** 加入共战参与者；已存在则忽略。 */
    public void addParticipant(int pid) {
        if (pid > 0) {
            participantPlayerIds.add(pid);
        }
    }

    /** 不可变参战玩家列表快照。 */
    public List<Integer> getParticipantPlayerIds() {
        return List.copyOf(participantPlayerIds);
    }

    /** 返回本场战斗使用的阵容 ID。 */
    public int getLineupId() {
        return lineupId;
    }

    /** 返回这场战斗中保存的所有波次运行时数据。 */
    public List<WaveRuntime> getWaves() {
        return waves;
    }

    public int getSceneEntityUid() {
        return sceneEntityUid;
    }

    public int getSceneMonsterId() {
        return sceneMonsterId;
    }

    public long getPlayerUid() {
        return playerUid;
    }

    public int getReturnPlaneId() {
        return returnPlaneId;
    }

    public int getReturnFloorId() {
        return returnFloorId;
    }

    public int getReturnEntryId() {
        return returnEntryId;
    }

    public float getReturnPosX() {
        return returnPosX;
    }

    public float getReturnPosY() {
        return returnPosY;
    }

    public float getReturnPosZ() {
        return returnPosZ;
    }

    /**
     * 当前波次仍存活的怪物实体 ID 列表（按配置怪物 ID）。
     */
    public boolean isAutoBattle() {
        return autoBattle;
    }

    public void setAutoBattle(boolean autoBattle, String reason) {
        this.autoBattle = autoBattle;
        this.autoReason = reason == null ? "" : reason;
        if (autoBattle && nextActionAtMs <= 0L) {
            nextActionAtMs = System.currentTimeMillis();
        }
    }

    public String getAutoReason() {
        return autoReason == null ? "" : autoReason;
    }

    public int getAutoStrategy() {
        return autoStrategy;
    }

    public int getTargetFocus() {
        return targetFocus;
    }

    /** 0默认 / 1集火精英 / 2优先破盾 */
    public boolean setTargetFocus(int focus) {
        if (focus < 0 || focus > 2) {
            return false;
        }
        this.targetFocus = focus;
        return true;
    }

    /** 合法策略 1/2/3；0 视为默认优先战技。 */
    public boolean setAutoStrategy(int strategy) {
        if (strategy == 0) {
            this.autoStrategy = 1;
            return true;
        }
        if (strategy != 1 && strategy != 2 && strategy != 3) {
            return false;
        }
        this.autoStrategy = strategy;
        return true;
    }

    /**
     * 抢占式大招槽：写入待执行大招并立即打断当前 Auto 倒计时（nextActionAtMs=now）。
     */
    public void queueManualUlt(int skillId, int casterId, java.util.List<Integer> targetIds) {
        this.pendingManualSkillId = skillId <= 0 ? 3 : skillId;
        this.pendingManualCasterId = casterId;
        this.pendingManualTargets.clear();
        if (targetIds != null) {
            this.pendingManualTargets.addAll(targetIds);
        }
        // 抢占：暂停 1500ms 倒计时，立刻响应
        this.nextActionAtMs = System.currentTimeMillis();
    }

    /**
     * AI 微观干预：锁定下一动技能，可选执行后自动恢复 Auto 策略。
     */
    public void queueMicroIntervention(int skillId, int casterId, java.util.List<Integer> targetIds,
                                       boolean revertAfter) {
        if (revertAfter) {
            this.savedAutoStrategy = autoStrategy;
            this.savedTargetFocus = targetFocus;
            this.revertAutoAfterMicro = true;
        }
        queueManualUlt(skillId, casterId, targetIds);
    }

    public boolean consumeRevertAutoAfterMicro() {
        boolean r = revertAutoAfterMicro;
        revertAutoAfterMicro = false;
        return r;
    }

    public int getSavedAutoStrategy() {
        return savedAutoStrategy;
    }

    public int getSavedTargetFocus() {
        return savedTargetFocus;
    }

    public int getTeamSkillPoints() {
        return teamSkillPoints;
    }

    public void setTeamSkillPoints(int points) {
        this.teamSkillPoints = Math.max(0, Math.min(5, points));
    }

    public void adjustTeamSkillPoints(int delta) {
        setTeamSkillPoints(teamSkillPoints + delta);
    }

    public int getUltEnergyPercent() {
        return ultEnergyPercent;
    }

    public void setUltEnergyPercent(int percent) {
        this.ultEnergyPercent = Math.max(0, Math.min(100, percent));
    }

    public boolean hasPendingManualUlt() {
        return pendingManualSkillId > 0;
    }

    public int consumePendingManualSkillId() {
        int id = pendingManualSkillId;
        pendingManualSkillId = 0;
        return id;
    }

    public int getPendingManualCasterId() {
        return pendingManualCasterId;
    }

    public java.util.List<Integer> snapshotPendingManualTargets() {
        return java.util.List.copyOf(pendingManualTargets);
    }

    public int getSpeedMultiplier() {
        return speedMultiplier;
    }

    /** 设置倍速；非法值返回 false 且不改写。 */
    public boolean setSpeedMultiplier(int multiplier) {
        if (multiplier != 1 && multiplier != 2 && multiplier != 3) {
            return false;
        }
        this.speedMultiplier = multiplier;
        return true;
    }

    public long getNextActionAtMs() {
        return nextActionAtMs;
    }

    public void setNextActionAtMs(long nextActionAtMs) {
        this.nextActionAtMs = nextActionAtMs;
    }

    public long getExpectedHitStopEndMs() {
        return expectedHitStopEndMs;
    }

    public void setExpectedHitStopEndMs(long expectedHitStopEndMs) {
        this.expectedHitStopEndMs = expectedHitStopEndMs;
    }

    /**
     * 按倍速压缩等待：客户端加速播放，服务端决策间隔同步缩短。
     */
    public long compressedWaitMs(long baseMs) {
        int mul = speedMultiplier <= 0 ? 1 : speedMultiplier;
        return Math.max(50L, baseMs / mul);
    }

    /** 调度下一拍自动行动。 */
    public void scheduleNextAutoAction(long baseWaitMs) {
        this.nextActionAtMs = System.currentTimeMillis() + compressedWaitMs(baseWaitMs);
    }

    public List<Integer> listAliveMonsterIdsInCurrentWave() {
        WaveRuntime wave = getWaveByWaveIndex(currentWave);
        if (wave == null) {
            return List.of();
        }
        List<Integer> ids = new ArrayList<>();
        for (MonsterRuntime monster : wave.getMonsters()) {
            EntityState entity = entities.get(monster.getRuntimeEntityId());
            if (entity != null && !entity.isDead()) {
                ids.add(entity.getId());
            }
        }
        return ids;
    }

    /**
     * 按 targetFocus 重排目标：1=优先高血量精英，2=优先未破盾。
     */
    public List<Integer> listAliveMonsterIdsByFocus(int focus) {
        List<Integer> ids = listAliveMonsterIdsInCurrentWave();
        if (ids.isEmpty() || focus == 0) {
            return ids;
        }
        List<Integer> ranked = new ArrayList<>(ids);
        if (focus == 1) {
            ranked.sort((a, b) -> {
                EntityState ea = entities.get(a);
                EntityState eb = entities.get(b);
                int ha = ea == null ? 0 : ea.getHp();
                int hb = eb == null ? 0 : eb.getHp();
                return Integer.compare(hb, ha);
            });
        } else if (focus == 2) {
            ranked.sort((a, b) -> {
                EntityState ea = entities.get(a);
                EntityState eb = entities.get(b);
                boolean ba = ea != null && !ea.isBroken() && ea.getMaxToughness() > 0;
                boolean bb = eb != null && !eb.isBroken() && eb.getMaxToughness() > 0;
                if (ba == bb) {
                    return 0;
                }
                return ba ? -1 : 1;
            });
        }
        return ranked;
    }
}
