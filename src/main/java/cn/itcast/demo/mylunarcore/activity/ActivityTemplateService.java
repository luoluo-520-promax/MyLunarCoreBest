package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 配置驱动活动模板：转盘抽奖 / 拼图收集 / 积分兑换；独有机制走 {@link ActivityScriptEngine}。
 */
@Service
public class ActivityTemplateService {

    public record OpResult(boolean success, int retcode, Map<String, Object> payload) {
        public static OpResult fail(int code) {
            return new OpResult(false, code, Map.of());
        }
    }

    private final JdbcTemplate jdbc;
    private final ActivityScriptEngine scriptEngine;
    private final ActivityCircuitBreakerService circuitBreaker;
    private final ObjectProvider<ItemRepository> itemRepositoryProvider;
    private final ConcurrentHashMap<String, Integer> points = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.BitSet> puzzles = new ConcurrentHashMap<>();

    public ActivityTemplateService(JdbcTemplate jdbc,
                                   ActivityScriptEngine scriptEngine,
                                   ActivityCircuitBreakerService circuitBreaker) {
        this(jdbc, scriptEngine, circuitBreaker, null);
    }

    public ActivityTemplateService(JdbcTemplate jdbc,
                                   ActivityScriptEngine scriptEngine,
                                   ActivityCircuitBreakerService circuitBreaker,
                                   ObjectProvider<ItemRepository> itemRepositoryProvider) {
        this.jdbc = jdbc;
        this.scriptEngine = scriptEngine;
        this.circuitBreaker = circuitBreaker;
        this.itemRepositoryProvider = itemRepositoryProvider;
    }

    /** 转盘：按权重抽取奖励档位。 */
    public OpResult spinWheel(int playerId, int activityId, List<Integer> weights, List<String> rewards) {
        if (circuitBreaker.shouldBlockTimedPlay()) {
            return OpResult.fail(503);
        }
        if (playerId <= 0 || activityId <= 0 || weights == null || rewards == null
                || weights.isEmpty() || weights.size() != rewards.size()) {
            return OpResult.fail(1);
        }
        int total = weights.stream().mapToInt(w -> Math.max(0, w)).sum();
        if (total <= 0) {
            return OpResult.fail(2);
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int acc = 0;
        int idx = 0;
        for (int i = 0; i < weights.size(); i++) {
            acc += Math.max(0, weights.get(i));
            if (roll < acc) {
                idx = i;
                break;
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("index", idx);
        payload.put("reward", rewards.get(idx));
        persistEvent(playerId, activityId, "wheel", rewards.get(idx));
        return new OpResult(true, 0, payload);
    }

    /** 拼图：点亮碎片，集齐发奖。 */
    public OpResult collectPuzzlePiece(int playerId, int activityId, int pieceIndex, int totalPieces) {
        if (circuitBreaker.shouldBlockTimedPlay()) {
            return OpResult.fail(503);
        }
        if (pieceIndex < 0 || pieceIndex >= totalPieces || totalPieces <= 0 || totalPieces > 64) {
            return OpResult.fail(1);
        }
        String key = playerId + ":" + activityId;
        java.util.BitSet bits = puzzles.computeIfAbsent(key, k -> new java.util.BitSet(totalPieces));
        bits.set(pieceIndex);
        boolean complete = bits.cardinality() >= totalPieces;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("owned", bits.cardinality());
        payload.put("total", totalPieces);
        payload.put("complete", complete);
        if (complete) {
            payload.put("rewardHint", circuitBreaker.shouldDegradeToMail()
                    ? "basic_mail_reward" : "puzzle_complete_reward");
            persistEvent(playerId, activityId, "puzzle_complete", "all");
        }
        return new OpResult(true, 0, payload);
    }

    /** 积分兑换。 */
    public OpResult exchangePoints(int playerId, int activityId, int cost, String productId) {
        if (circuitBreaker.shouldBlockTimedPlay()) {
            return OpResult.fail(503);
        }
        if (cost <= 0 || productId == null || productId.isBlank()) {
            return OpResult.fail(1);
        }
        String key = playerId + ":" + activityId;
        int cur = points.getOrDefault(key, 0);
        if (cur < cost) {
            return OpResult.fail(2);
        }
        points.put(key, cur - cost);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productId", productId);
        payload.put("remainPoints", cur - cost);
        payload.put("rewardMode", circuitBreaker.shouldDegradeToMail() ? "basic_mail" : "normal");
        persistEvent(playerId, activityId, "exchange", productId);
        return new OpResult(true, 0, payload);
    }

    public void addPoints(int playerId, int activityId, int delta) {
        if (delta == 0) {
            return;
        }
        String key = playerId + ":" + activityId;
        points.merge(key, delta, Integer::sum);
    }

    public int getPoints(int playerId, int activityId) {
        return points.getOrDefault(playerId + ":" + activityId, 0);
    }

    /**
     * 执行活动独有脚本（如签到第7天多倍、限时挑战特殊积分）。
     * scriptId 对应 data/scripts/activity/{scriptId}.groovy。
     */
    public OpResult invokeScript(String scriptId, int playerId, int activityId, Map<String, Object> extra) {
        if (circuitBreaker.shouldBlockTimedPlay()) {
            return OpResult.fail(503);
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("playerId", playerId);
        ctx.put("activityId", activityId);
        ctx.put("points", getPoints(playerId, activityId));
        ctx.put("nowEpoch", Instant.now().getEpochSecond());
        if (extra != null) {
            ctx.putAll(extra);
        }
        ActivityScriptEngine.ScriptResult result = scriptEngine.invoke(scriptId, ctx);
        if (!result.success()) {
            return new OpResult(false, result.retcode(), Map.of("message", result.message()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result.value() instanceof Map<?, ?> m) {
            m.forEach((k, v) -> payload.put(String.valueOf(k), v));
        } else {
            payload.put("value", result.value());
        }
        if (payload.get("addPoints") instanceof Number n) {
            addPoints(playerId, activityId, n.intValue());
            payload.put("points", getPoints(playerId, activityId));
        }
        if (circuitBreaker.shouldDegradeToMail()) {
            payload.put("rewardMode", "basic_mail");
        }
        persistEvent(playerId, activityId, "script:" + scriptId, String.valueOf(payload.getOrDefault("detail", "ok")));
        return new OpResult(true, 0, payload);
    }

    public List<Map<String, Object>> supportedTemplates() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(Map.of("type", "wheel_lottery", "displayName", "转盘抽奖"));
        list.add(Map.of("type", "puzzle_collect", "displayName", "拼图收集"));
        list.add(Map.of("type", "points_exchange", "displayName", "积分兑换"));
        list.add(Map.of("type", "script", "displayName", "脚本扩展机制"));
        list.add(Map.of("type", "RETURN_PLAYER", "displayName", "回流玩家"));
        list.add(Map.of("type", "COMPENSATE", "displayName", "补签补偿"));
        return list;
    }

    /**
     * 补签：消耗道具补记 missDay，写入 activity_template_event（action=compensate）。
     *
     * @param costItemId 消耗道具模板 id；≤0 表示免费补签
     */
    public OpResult makeupSignIn(int playerId, int activityId, String missDay, int costItemId) {
        if (circuitBreaker.shouldBlockTimedPlay()) {
            return OpResult.fail(503);
        }
        if (playerId <= 0 || activityId <= 0 || missDay == null || missDay.isBlank()) {
            return OpResult.fail(1);
        }
        String day = missDay.trim();
        if (day.length() > 16) {
            return OpResult.fail(1);
        }
        if (costItemId > 0) {
            ItemRepository items = itemRepositoryProvider == null
                    ? null : itemRepositoryProvider.getIfAvailable();
            if (items == null) {
                return OpResult.fail(4);
            }
            long have = items.sumCountByItemId(playerId, costItemId);
            if (have < 1) {
                return OpResult.fail(2);
            }
            ItemRepository.SubtractResult sub = items.forceSubtractByItemId(playerId, costItemId, 1);
            if (sub.subtracted() < 1) {
                return OpResult.fail(2);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missDay", day);
        payload.put("compensated", true);
        payload.put("costItemId", costItemId);
        payload.put("rewardMode", circuitBreaker.shouldDegradeToMail() ? "basic_mail" : "normal");
        persistEvent(playerId, activityId, "compensate", day);
        return new OpResult(true, 0, payload);
    }

    private void persistEvent(int playerId, int activityId, String action, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO activity_template_event
                    (player_id, activity_id, action, detail, created_at) VALUES (?, ?, ?, ?, ?)
                    """, playerId, activityId, action, detail, Instant.now().toString());
        } catch (Exception ignored) {
        }
    }
}
