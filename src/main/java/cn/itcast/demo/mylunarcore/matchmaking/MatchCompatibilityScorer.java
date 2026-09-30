package cn.itcast.demo.mylunarcore.matchmaking;

/**
 * 匹配互补评分：等级/战力接近且互补时得分更高（方案 D 预留，可替换为 embedding）。
 */
public final class MatchCompatibilityScorer {

    private MatchCompatibilityScorer() {
    }

    /**
     * 分数越高越适合组队；差值过大时分数下降。
     */
    public static double score(MatchQueue.QueueEntry a, MatchQueue.QueueEntry b) {
        if (a == null || b == null || a.playerId() == b.playerId()) {
            return Double.NEGATIVE_INFINITY;
        }
        int levelDiff = Math.abs(a.level() - b.level());
        int powerDiff = Math.abs(a.power() - b.power());
        // 等级差 0~10、战力差相对惩罚；等待越久轻微加分（避免饿死）
        double waitBonus = Math.min(30.0, (System.currentTimeMillis() - Math.min(a.enqueueTime(), b.enqueueTime())) / 1000.0);
        return 100.0 - levelDiff * 4.0 - Math.min(50.0, powerDiff / 100.0) + waitBonus * 0.1;
    }
}
