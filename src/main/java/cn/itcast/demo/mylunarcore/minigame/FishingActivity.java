package cn.itcast.demo.mylunarcore.minigame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 钓鱼：随机概率 + QTE 判定。
 */
public class FishingActivity implements MiniGameInstance {

    private final String gameId;
    private final double baseCatchRate;
    private final Map<Integer, AtomicInteger> catches = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> attempts = new ConcurrentHashMap<>();

    public FishingActivity(String gameId, double baseCatchRate) {
        this.gameId = gameId == null ? "fishing" : gameId;
        this.baseCatchRate = Math.min(1.0, Math.max(0.05, baseCatchRate));
    }

    @Override
    public String miniGameId() {
        return gameId;
    }

    @Override
    public void onStart(int playerId, Map<String, Object> context) {
        catches.put(playerId, new AtomicInteger());
        attempts.put(playerId, new AtomicInteger());
    }

    @Override
    public boolean onTick(int playerId, Map<String, Object> tickPayload) {
        attempts.computeIfAbsent(playerId, k -> new AtomicInteger()).incrementAndGet();
        boolean qteOk = tickPayload == null
                || Boolean.TRUE.equals(tickPayload.get("qteSuccess"))
                || "ok".equalsIgnoreCase(String.valueOf(tickPayload.getOrDefault("qte", "ok")));
        double rate = qteOk ? baseCatchRate : baseCatchRate * 0.35;
        if (ThreadLocalRandom.current().nextDouble() < rate) {
            catches.computeIfAbsent(playerId, k -> new AtomicInteger()).incrementAndGet();
        }
        int maxAttempts = tickPayload == null ? 10
                : ((Number) tickPayload.getOrDefault("maxAttempts", 10)).intValue();
        if (attempts.getOrDefault(playerId, new AtomicInteger()).get() >= maxAttempts) {
            onEnd(playerId, getReward(playerId));
            return true;
        }
        return false;
    }

    @Override
    public void onEnd(int playerId, Map<String, Object> result) {
        // noop
    }

    @Override
    public Map<String, Object> getReward(int playerId) {
        int c = catches.getOrDefault(playerId, new AtomicInteger()).get();
        return Map.of("score", c * 100, "catches", c, "rewardHint", "fish_token");
    }
}
