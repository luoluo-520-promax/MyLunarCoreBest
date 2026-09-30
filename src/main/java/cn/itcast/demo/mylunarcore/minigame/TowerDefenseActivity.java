package cn.itcast.demo.mylunarcore.minigame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 塔防：放置炮台 + 波次击杀积分。
 */
public class TowerDefenseActivity implements MiniGameInstance {

    private final String gameId;
    private final int turretSlots;
    private final int waves;
    private final Map<Integer, AtomicInteger> score = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> wave = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicInteger> turrets = new ConcurrentHashMap<>();

    public TowerDefenseActivity(String gameId, int turretSlots, int waves) {
        this.gameId = gameId == null ? "tower_defense" : gameId;
        this.turretSlots = Math.max(1, turretSlots);
        this.waves = Math.max(1, waves);
    }

    @Override
    public String miniGameId() {
        return gameId;
    }

    @Override
    public void onStart(int playerId, Map<String, Object> context) {
        score.put(playerId, new AtomicInteger());
        wave.put(playerId, new AtomicInteger(1));
        turrets.put(playerId, new AtomicInteger());
    }

    @Override
    public boolean onTick(int playerId, Map<String, Object> tickPayload) {
        if (tickPayload == null) {
            return false;
        }
        String action = String.valueOf(tickPayload.getOrDefault("action", ""));
        if ("place_turret".equalsIgnoreCase(action)) {
            AtomicInteger t = turrets.computeIfAbsent(playerId, k -> new AtomicInteger());
            if (t.get() < turretSlots) {
                t.incrementAndGet();
            }
            return false;
        }
        if ("kill".equalsIgnoreCase(action)) {
            int pts = ((Number) tickPayload.getOrDefault("points", 10)).intValue();
            score.computeIfAbsent(playerId, k -> new AtomicInteger()).addAndGet(pts);
            return false;
        }
        if ("wave_clear".equalsIgnoreCase(action)) {
            int w = wave.computeIfAbsent(playerId, k -> new AtomicInteger(1)).incrementAndGet();
            if (w > waves) {
                onEnd(playerId, Map.of("score", score.getOrDefault(playerId, new AtomicInteger()).get()));
                return true;
            }
        }
        return false;
    }

    @Override
    public void onEnd(int playerId, Map<String, Object> result) {
        // noop
    }

    @Override
    public Map<String, Object> getReward(int playerId) {
        int s = score.getOrDefault(playerId, new AtomicInteger()).get();
        return Map.of("score", s, "rewardHint", "td_blueprint");
    }
}
