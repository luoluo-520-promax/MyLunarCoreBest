package cn.itcast.demo.mylunarcore.matchmaking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方案 D 匹配互补评分。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MatchCompatibilityScorerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("方案 D 匹配互补评分")
class MatchCompatibilityScorerTest {

    /**
     * 验证点：等级接近应比等级悬殊得分更高。
     * <p>测试方法 {@code closerLevelShouldScoreHigher}：
     * <ul>
     *   <li>{@code assertTrue(MatchCompatibilityScorer.score(a, close) > MatchCompatibilityScorer.score(a, far));}</li>
     * </ul>
     */
    @Test
    @DisplayName("等级接近应比等级悬殊得分更高")
    void closerLevelShouldScoreHigher() {
        long now = System.currentTimeMillis();
        MatchQueue.QueueEntry a = new MatchQueue.QueueEntry(1, 1, 20, 1000, now);
        MatchQueue.QueueEntry close = new MatchQueue.QueueEntry(2, 1, 21, 1100, now);
        MatchQueue.QueueEntry far = new MatchQueue.QueueEntry(3, 1, 50, 5000, now);
        assertTrue(MatchCompatibilityScorer.score(a, close) > MatchCompatibilityScorer.score(a, far));
    }

    /**
     * 验证点：互补评分成局应优先挑选接近的两人。
     * <p>测试方法 {@code pollMatchShouldPreferCompatiblePair}：
     * <ul>
     *   <li>{@code assertEquals(2, matched.size());}</li>
     *   <li>{@code assertTrue(matched.stream().anyMatch(e -> e.playerId() == 1));}</li>
     *   <li>{@code assertTrue(matched.stream().anyMatch(e -> e.playerId() == 3));}</li>
     * </ul>
     */
    @Test
    @DisplayName("互补评分成局应优先挑选接近的两人")
    void pollMatchShouldPreferCompatiblePair() {
        MatchQueue queue = new MatchQueue();
        long now = System.currentTimeMillis();
        queue.enqueue(new MatchQueue.QueueEntry(1, 1, 10, 100, now));
        queue.enqueue(new MatchQueue.QueueEntry(2, 1, 50, 9000, now));
        queue.enqueue(new MatchQueue.QueueEntry(3, 1, 11, 120, now));
        List<MatchQueue.QueueEntry> matched = queue.pollMatch(1, 2, true);
        assertEquals(2, matched.size());
        assertTrue(matched.stream().anyMatch(e -> e.playerId() == 1));
        assertTrue(matched.stream().anyMatch(e -> e.playerId() == 3));
    }
}
