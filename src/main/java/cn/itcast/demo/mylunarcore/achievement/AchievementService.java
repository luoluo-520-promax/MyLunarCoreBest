package cn.itcast.demo.mylunarcore.achievement;

import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.profile.TitleService;
import cn.itcast.demo.mylunarcore.protocol.AchievementSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成就系统服务。
 *
 * <p>职责：
 * <ul>
 *   <li>维护内置成就定义表 {@link Definition}（如抽卡次数、通关层数、图鉴收集数量等里程碑）；</li>
 *   <li>提供进度累加接口 {@link #addProgress}，供抽卡、战斗、图鉴等其他模块在关键节点调用；</li>
 *   <li>查询玩家全部成就的进度与领取状态 {@link #listForPlayer}；</li>
 *   <li>领取达成奖励 {@link #claim}（必须满足"进度 ≥ 目标值且未领取"）。</li>
 * </ul>
 *
 * <p>存储：持久化在 player_achievement 表（player_id, achievement_id, progress, claimed）。
 * 表未创建或数据库异常时，所有操作静默跳过（try-catch 忽略），保证主流程不被成就系统拖垮。
 */
@Service
public class AchievementService {

    /**
     * 成就定义。
     *
     * @param id         成就唯一标识（如 gacha_10、battle_win_20）
     * @param title      成就标题（展示用）
     * @param target     达成所需的目标数值（进度达到该值方可领奖）
     * @param rewardDesc 奖励描述文本
     */
    public record Definition(String id, String title, int target, String rewardDesc) {}

    /** 内置成就清单：抽卡、模拟宇宙、战斗、图鉴等维度的里程碑。 */
    private static final List<Definition> DEFS = List.of(
            new Definition("gacha_10", "抽卡十连启程", 10, "星琼*50"),
            new Definition("gacha_100", "百抽达人", 100, "星琼*300"),
            new Definition("rogue_floor_5", "模拟宇宙第5层", 5, "信用点*10000"),
            new Definition("battle_win_20", "战斗胜利20场", 20, "开拓力*60"),
            new Definition("handbook_avatar", "角色图鉴收集", 4, "星琼*40"),
            new Definition("handbook_light_cone", "光锥图鉴收集", 3, "星琼*40"),
            new Definition("handbook_enemy", "敌人图鉴收集", 3, "信用点*20000"),
            new Definition("handbook_any", "图鉴初学者", 5, "头像框·星穹列车")
    );

    /** 数据库访问模板。 */
    private final JdbcTemplate jdbc;
    private final ObjectProvider<AnalyticsEventPublisher> analyticsProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ObjectProvider<TitleService> titleProvider;

    public AchievementService(JdbcTemplate jdbc) {
        this(jdbc, null, null, null);
    }

    public AchievementService(JdbcTemplate jdbc,
                              ObjectProvider<AnalyticsEventPublisher> analyticsProvider,
                              ObjectProvider<GameSessionManager> sessionProvider) {
        this(jdbc, analyticsProvider, sessionProvider, null);
    }

    public AchievementService(JdbcTemplate jdbc,
                              ObjectProvider<AnalyticsEventPublisher> analyticsProvider,
                              ObjectProvider<GameSessionManager> sessionProvider,
                              ObjectProvider<TitleService> titleProvider) {
        this.jdbc = jdbc;
        this.analyticsProvider = analyticsProvider;
        this.sessionProvider = sessionProvider;
        this.titleProvider = titleProvider;
    }

    /** 返回全部成就定义（供客户端渲染成就列表 / 详情页）。 */
    public List<Definition> definitions() {
        return DEFS;
    }

    /**
     * 为某玩家的某成就累加进度。
     *
     * <p>实现为"先 UPDATE 再 INSERT"的 upsert：若记录已存在则原子性地累加 progress，
     * 不存在则插入一条初始进度记录（初始进度取 max(0, delta)，避免首次就为负值）。
     * 参数非法（playerId<=0、achievementId 为空、delta==0）时直接返回不落库。
     *
     * @param playerId      玩家 ID
     * @param achievementId 成就 ID
     * @param delta         本次累加的进度增量（可正可负）
     */
    public void addProgress(int playerId, String achievementId, int delta) {
        if (playerId <= 0 || achievementId == null || delta == 0) {
            return;
        }
        try {
            int updated = jdbc.update("""
                    UPDATE player_achievement SET progress = progress + ?, updated_at = ?
                    WHERE player_id = ? AND achievement_id = ?
                    """, delta, Instant.now().toString(), playerId, achievementId);
            // 更新影响 0 行说明该记录不存在，改走插入分支
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO player_achievement (player_id, achievement_id, progress, claimed, updated_at)
                        VALUES (?, ?, ?, 0, ?)
                        """, playerId, achievementId, Math.max(0, delta), Instant.now().toString());
            }
            maybePushUnlock(playerId, achievementId);
        } catch (Exception ignored) {
            // 表未就绪时跳过
        }
    }

    private void maybePushUnlock(int playerId, String achievementId) {
        Definition def = DEFS.stream().filter(d -> d.id().equals(achievementId)).findFirst().orElse(null);
        if (def == null) {
            return;
        }
        int progress = 0;
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT progress FROM player_achievement WHERE player_id = ? AND achievement_id = ?",
                    Integer.class, playerId, achievementId);
            progress = n == null ? 0 : n;
        } catch (Exception e) {
            return;
        }
        if (progress < def.target()) {
            return;
        }
        AnalyticsEventPublisher analytics = analyticsProvider == null ? null : analyticsProvider.getIfAvailable();
        if (analytics != null) {
            analytics.track("achievement_unlock", playerId, Map.of(
                    "achievementId", def.id(), "title", def.title(), "target", def.target()));
        }
        GameSessionManager sessions = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        AchievementSystemProto.AchievementUnlockPushScNotify notify =
                AchievementSystemProto.AchievementUnlockPushScNotify.newBuilder()
                        .setAchievementId(def.id())
                        .setTitle(def.title())
                        .setToastText("成就解锁：" + def.title())
                        .setRewardDesc(def.rewardDesc())
                        .setCanClaim(true)
                        .setTarget(def.target())
                        .build();
        session.send(new GamePacket(CmdIds.ACHIEVEMENT_UNLOCK_PUSH_SC_NOTIFY, notify.toByteArray()));
    }

    /**
     * 查询玩家全部成就的展示数据（进度 + 领取状态）。
     *
     * <p>先从数据库读取该玩家已有记录（progress、claimed），再与内置定义表 DEFS 全量对齐，
     * 确保未开始推进的成就也出现在列表中（进度 0 / 未领取）。
     *
     * @param playerId 玩家 ID
     * @return 成就列表，元素为 id/title/target/rewardDesc/progress/claimed 的映射
     */
    public List<Map<String, Object>> listForPlayer(int playerId) {
        // 按成就 ID 缓存进度与领取状态
        Map<String, Integer> progress = new LinkedHashMap<>();
        Map<String, Boolean> claimed = new LinkedHashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT achievement_id, progress, claimed FROM player_achievement WHERE player_id = ?",
                    playerId);
            for (Map<String, Object> row : rows) {
                String id = String.valueOf(row.get("achievement_id"));
                progress.put(id, ((Number) row.get("progress")).intValue());
                claimed.put(id, ((Number) row.get("claimed")).intValue() == 1);
            }
        } catch (Exception ignored) {
            // 表不可用时按全未推进处理
        }
        // 与定义表对齐，逐条拼装输出
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition def : DEFS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", def.id());
            item.put("title", def.title());
            item.put("target", def.target());
            item.put("rewardDesc", def.rewardDesc());
            item.put("progress", progress.getOrDefault(def.id(), 0));
            item.put("claimed", claimed.getOrDefault(def.id(), false));
            out.add(item);
        }
        return out;
    }

    /**
     * 领取指定成就奖励。
     *
     * <p>通过 UPDATE 的原子条件（claimed = 0 AND progress >= target）保证：
     * 只有"未领取且进度达标"的成就才能领取成功，重复领取返回 false。
     *
     * @param playerId      玩家 ID
     * @param achievementId 成就 ID
     * @return 是否领取成功
     */
    public boolean claim(int playerId, String achievementId) {
        // 校验成就 ID 是否在定义表内
        Definition def = DEFS.stream().filter(d -> d.id().equals(achievementId)).findFirst().orElse(null);
        if (def == null) {
            return false;
        }
        try {
            int n = jdbc.update("""
                    UPDATE player_achievement SET claimed = 1, updated_at = ?
                    WHERE player_id = ? AND achievement_id = ? AND claimed = 0 AND progress >= ?
                    """, Instant.now().toString(), playerId, achievementId, def.target());
            if (n > 0) {
                TitleService titles = titleProvider == null ? null : titleProvider.getIfAvailable();
                if (titles != null) {
                    titles.unlockFromAchievement(playerId, achievementId);
                }
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
