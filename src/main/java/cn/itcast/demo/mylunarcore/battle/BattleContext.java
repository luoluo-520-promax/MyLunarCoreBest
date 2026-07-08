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
// 列表接口
import java.util.List;
// 键值映射接口
import java.util.Map;

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

    private boolean ended; // 战斗是否已经结束；结束后通常不再继续推进回合或波次。

    private int turn = 1; // 当前战斗回合数，从 1 开始计数，表示战斗已经推进到了第几轮。
    private int currentWave = 1; // 当前正在处理的波次编号，从 1 开始，对应 waves 中的第几个波次。
    private final int waveCount; // 战斗总波次数，用来判断是否还有后续怪物波次需要切换。

    private final Map<Integer, EntityState> entities = new HashMap<>(); // 运行时实体表，保存玩家和怪物的当前生命、死亡状态等信息，战斗结算都依赖这份数据。
    private final List<WaveRuntime> waves; // 战斗的全部波次运行时数据，保存每一波怪物的配置结果，供切波和判定使用。

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

        // 把玩家先加入实体表，这样后续技能、伤害和结算逻辑都可以直接通过 playerId 找到玩家状态。
        entities.put(playerId, new EntityState(playerId, 1000, false));
        // 如果这场战斗有怪物波次，就立即加载第 1 波，保证战斗创建后马上具备可结算的怪物数据。
        if (waveCount > 0) {
            switchToWave(1);
        }
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

    /** 根据实体 ID 查询当前状态；如果实体不存在，返回 null。 */
    public EntityState getEntity(int entityId) {
        return entities.get(entityId); // 从实体表中读取玩家或怪物的实时状态。
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
        for (MonsterRuntime monster : wave.getMonsters()) { // 将本波次的每个怪物注册到实体表中。
            EntityState entity = new EntityState(monster.getConfigMonsterId(), monster.getMaxHp(), false); // 以配置怪物 ID 作为实体 ID，初始化满血且未死亡状态。
            entities.put(entity.getId(), entity); // 放入实体表，供后续伤害和死亡判定使用。
        }
    }

    /** 判断指定波次中的所有怪物是否已经死亡。 */
    public boolean isAllMonstersDeadInWave(int waveIndex) {
        WaveRuntime wave = getWaveByWaveIndex(waveIndex); // 获取指定波次。
        if (wave == null) { // 如果波次不存在，按“已清空”处理，避免阻塞流程。
            return true;
        }
        for (MonsterRuntime monster : wave.getMonsters()) { // 逐个检查本波次怪物的当前状态。
            EntityState entity = entities.get(monster.getConfigMonsterId()); // 从实体表中拿到该怪物的实时状态。
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

    /** 返回当前实体表，供战斗逻辑读取玩家和怪物的最新状态。 */
    public Map<Integer, EntityState> getEntities() {
        return entities; // 提供实体状态视图，战斗中的伤害、治疗、死亡判定都依赖它。
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
            return false;
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

    /** 返回本场战斗使用的阵容 ID。 */
    public int getLineupId() {
        return lineupId;
    }

    /** 返回这场战斗中保存的所有波次运行时数据。 */
    public List<WaveRuntime> getWaves() {
        return waves;
    }
}
