package cn.itcast.demo.mylunarcore.affinity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NPC/同行者好感度服务。
 *
 * <p>功能定位：
 * <ul>
 *   <li>记录玩家与每个 NPC 的好感经验 exp，按经验档位 {@link #LEVEL_THRESHOLDS} 折算等级 level；</li>
 *   <li>等级每提升 1 级解锁一个任务/剧情标志位 {@code affinity_<npcId>_lvN}，
 *       并可在提升瞬间返回奖励提示 rewardHint（供客户端弹出解锁反馈）；</li>
 *   <li>经验来源分两类：对话选项（{@link #addFromDialogue}，默认 +10）与赠礼
 *       （{@link #addFromGift}，默认 +20），均记录触发原因 reason 便于后续审计；</li>
 *   <li>数据先写内存缓存 {@link #memExp}（key = playerId:npcId）加速读取，
 *       再落库到 <code>player_affinity</code> 表；DB 异常时降级为纯内存。</li>
 * </ul>
 */
@Service
public class AffinityService {

    /**
     * 好感度状态。
     *
     * @param npcId           NPC ID
     * @param exp             当前好感经验
     * @param level           由经验换算出的等级（0 为最低）
     * @param unlockedFlags   该等级已解锁的全部标志位（1..level 逐级累积）
     */
    public record AffinityState(String npcId, int exp, int level, List<String> unlockedFlags) {}

    /**
     * 好感度变更结果。
     *
     * @param success    是否处理成功
     * @param retcode    错误码：0=成功，1=参数非法（玩家 ID/NPC ID 无效）
     * @param state      变更后的好感度状态
     * @param rewardHint 若本次触发了升级则返回升级奖励标志文本，否则为空串
     */
    public record ChangeResult(boolean success, int retcode, AffinityState state, String rewardHint) {}

    /** 等级经验档位（下标即等级）：0、100、300、600、1000、1600，满级为档位末位经验。 */
    private static final int[] LEVEL_THRESHOLDS = {0, 100, 300, 600, 1000, 1600};

    /** 数据库访问模板，负责读写 player_affinity 表。 */
    private final JdbcTemplate jdbc;
    /** 内存经验缓存：key = playerId:npcId → 好感经验，避免重复查库。 */
    private final ConcurrentHashMap<String, Integer> memExp = new ConcurrentHashMap<>();

    /** 依赖注入：依赖 JdbcTemplate 做持久化。 */
    public AffinityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询玩家对某 NPC 的好感度状态（含当前等级与已解锁标志位）。
     *
     * @param playerId 玩家 ID
     * @param npcId    NPC ID
     * @return 好感度状态快照；从未互动过时返回 exp=0、level=0、空标志列表
     */
    public AffinityState get(int playerId, String npcId) {
        int exp = loadExp(playerId, npcId);
        int level = levelOf(exp);
        return new AffinityState(npcId, exp, level, flagsForLevel(npcId, level));
    }

    /**
     * 对话选项触发的经验增加。
     *
     * <p>参数校验失败返回失败结果（retcode=1）；delta 未传正数时默认 +10。
     * 调用方（对话系统）应传入本次选择的选项 ID 作为触发来源。
     *
     * @param playerId 玩家 ID
     * @param npcId    NPC ID
     * @param choiceId 本次选择的对话选项 ID（仅记录来源）
     * @param delta    增加的经验值（≤0 时使用默认 10）
     * @return 变更结果，含升级奖励提示
     */
    public ChangeResult addFromDialogue(int playerId, String npcId, String choiceId, int delta) {
        if (playerId <= 0 || npcId == null || npcId.isBlank()) {
            return new ChangeResult(false, 1, null, "");
        }
        int add = delta > 0 ? delta : 10;
        return apply(playerId, npcId, add, "dialogue:" + (choiceId == null ? "" : choiceId));
    }

    /**
     * 赠礼触发的经验增加。
     *
     * <p>参数校验失败返回失败结果（retcode=1）；delta 未传正数时默认 +20。
     *
     * @param playerId   玩家 ID
     * @param npcId      NPC ID
     * @param giftItemId 赠送的道具 ID（仅记录来源）
     * @param delta      增加的经验值（≤0 时使用默认 20）
     * @return 变更结果，含升级奖励提示
     */
    public ChangeResult addFromGift(int playerId, String npcId, int giftItemId, int delta) {
        if (playerId <= 0 || npcId == null || npcId.isBlank()) {
            return new ChangeResult(false, 1, null, "");
        }
        int add = delta > 0 ? delta : 20;
        return apply(playerId, npcId, add, "gift:" + giftItemId);
    }

    /**
     * 列出玩家全部有经验记录的 NPC 好感度状态。
     *
     * <p>遍历内存缓存中属于该玩家（key 前缀 playerId:）的条目，逐个回读最新状态
     * （会触发 DB 查询补全），供客户端渲染好感度面板。
     *
     * @param playerId 玩家 ID
     * @return 该玩家的好感度状态列表（按缓存遍历顺序，非排序保证）
     */
    public List<Map<String, Object>> list(int playerId) {
        List<Map<String, Object>> out = new ArrayList<>();
        String prefix = playerId + ":";
        for (Map.Entry<String, Integer> e : memExp.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            String npcId = e.getKey().substring(prefix.length());
            AffinityState s = get(playerId, npcId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("npcId", s.npcId());
            m.put("exp", s.exp());
            m.put("level", s.level());
            m.put("unlockedFlags", s.unlockedFlags());
            out.add(m);
        }
        return out;
    }

    /**
     * 好感经验变更的核心处理。
     *
     * <p>计算变更前后经验，用新经验刷新内存缓存并落库；若跨过了经验档位（新等级 > 旧等级），
     * 则返回升级奖励标志 {@code affinity_<npcId>_<newLv>} 供客户端提示解锁。
     *
     * @param playerId 玩家 ID
     * @param npcId    NPC ID
     * @param delta    本次增加的经验值（必须为正）
     * @param reason   变更来源说明（dialogue:xxx / gift:xxx）
     * @return 变更结果
     */
    private ChangeResult apply(int playerId, String npcId, int delta, String reason) {
        String key = playerId + ":" + npcId;
        int before = loadExp(playerId, npcId);
        // 经验上限封顶为满级所需经验，防止溢出
        int after = Math.min(LEVEL_THRESHOLDS[LEVEL_THRESHOLDS.length - 1], before + delta);
        memExp.put(key, after);
        persist(playerId, npcId, after);
        int oldLv = levelOf(before);
        int newLv = levelOf(after);
        String reward = "";
        if (newLv > oldLv) {
            // 升级奖励标志：affinity_<npcId>_<newLv>
            reward = "affinity_level_" + npcId + "_" + newLv;
        }
        return new ChangeResult(true, 0, new AffinityState(npcId, after, newLv, flagsForLevel(npcId, newLv)), reward);
    }

    /**
     * 读取玩家与某 NPC 的好感经验。
     *
     * <p>优先返回内存缓存；未命中时查库 player_affinity 表并回填缓存；
     * 无记录或 DB 异常时返回 0（表示从未互动过）。
     *
     * @param playerId 玩家 ID
     * @param npcId    NPC ID
     * @return 好感经验值
     */
    private int loadExp(int playerId, String npcId) {
        String key = playerId + ":" + npcId;
        Integer mem = memExp.get(key);
        if (mem != null) {
            return mem;
        }
        try {
            Integer exp = jdbc.queryForObject(
                    "SELECT exp FROM player_affinity WHERE player_id=? AND npc_id=?",
                    Integer.class, playerId, npcId);
            if (exp != null) {
                memExp.put(key, exp);
                return exp;
            }
        } catch (Exception ignored) {
            // 表未就绪时走内存兜底
        }
        return 0;
    }

    /**
     * 持久化好感经验到数据库。
     *
     * <p>采用"先 UPDATE 后 INSERT"策略：UPDATE 无影响行则 INSERT 建行，
     * 持久化失败不影响内存缓存中的最新值。
     *
     * @param playerId 玩家 ID
     * @param npcId    NPC ID
     * @param exp      最新经验值
     */
    private void persist(int playerId, String npcId, int exp) {
        try {
            int u = jdbc.update("""
                    UPDATE player_affinity SET exp=?, updated_at=? WHERE player_id=? AND npc_id=?
                    """, exp, Instant.now().toString(), playerId, npcId);
            if (u == 0) {
                jdbc.update("""
                        INSERT INTO player_affinity (player_id, npc_id, exp, updated_at) VALUES (?, ?, ?, ?)
                        """, playerId, npcId, exp, Instant.now().toString());
            }
        } catch (Exception ignored) {
            // 落库失败忽略：内存缓存仍持有最新值
        }
    }

    /**
     * 按经验值计算当前等级。
     *
     * <p>遍历档位数组，取"最后一个 ≤ exp 的档位下标"作为等级；
     * 经验低于 100 时等级为 0。
     *
     * @param exp 好感经验
     * @return 当前等级（0..LEVEL_THRESHOLDS.length-1）
     */
    private static int levelOf(int exp) {
        int lv = 0;
        for (int i = 0; i < LEVEL_THRESHOLDS.length; i++) {
            if (exp >= LEVEL_THRESHOLDS[i]) {
                lv = i;
            }
        }
        return lv;
    }

    /**
     * 生成某 NPC 在指定等级下应解锁的全部标志位。
     *
     * <p>逐级累积：等级越高，返回列表越长（包含所有低等级标志）。
     *
     * @param npcId NPC ID
     * @param level 当前等级
     * @return 解锁标志列表，如 ["affinity_npc1_lv1", "affinity_npc1_lv2", ...]
     */
    private static List<String> flagsForLevel(String npcId, int level) {
        List<String> flags = new ArrayList<>();
        for (int i = 1; i <= level; i++) {
            flags.add("affinity_" + npcId + "_lv" + i);
        }
        return flags;
    }
}
