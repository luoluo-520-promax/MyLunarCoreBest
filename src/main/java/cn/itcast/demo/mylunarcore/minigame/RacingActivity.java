package cn.itcast.demo.mylunarcore.minigame;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跑酷竞速：检查点到达时间校验与排名。
 */
public class RacingActivity implements MiniGameInstance {

    private final String gameId;
    private final List<Map<String, Object>> checkpoints;
    private final Map<Integer, Long> startAt = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> progress = new ConcurrentHashMap<>();
    private final Map<Integer, Long> finishMs = new ConcurrentHashMap<>();

    public RacingActivity(String gameId, List<Map<String, Object>> checkpoints) {
        this.gameId = gameId == null ? "racing" : gameId;
        this.checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
    }

    @Override
    public String miniGameId() {
        return gameId;
    }

    @Override
    public void onStart(int playerId, Map<String, Object> context) {
        startAt.put(playerId, System.currentTimeMillis());
        progress.put(playerId, 0);
        finishMs.remove(playerId);
    }

    @Override
    public boolean onTick(int playerId, Map<String, Object> tickPayload) {
        int idx = tickPayload == null ? -1 : ((Number) tickPayload.getOrDefault("checkpointIndex", -1)).intValue();
        int cur = progress.getOrDefault(playerId, 0);
        if (idx == cur) {
            progress.put(playerId, cur + 1);
        }
        if (progress.getOrDefault(playerId, 0) >= checkpoints.size()) {
            long elapsed = System.currentTimeMillis() - startAt.getOrDefault(playerId, System.currentTimeMillis());
            finishMs.put(playerId, elapsed);
            onEnd(playerId, Map.of("elapsedMs", elapsed));
            return true;
        }
        return false;
    }

    @Override
    public void onEnd(int playerId, Map<String, Object> result) {
        // ranking handled by MiniGameFramework
    }

    @Override
    public Map<String, Object> getReward(int playerId) {
        Long elapsed = finishMs.get(playerId);
        if (elapsed == null) {
            return Map.of();
        }
        int score = (int) Math.max(0, 100_000 - elapsed);
        return Map.of("score", score, "elapsedMs", elapsed, "rewardHint", "racing_token");
    }

    public List<Map<String, Object>> checkpoints() {
        return checkpoints;
    }
}
