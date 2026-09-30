package cn.itcast.demo.mylunarcore.matchmaking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MatchQueue 匹配队列测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MatchQueueTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MatchQueue 匹配队列测试")
class MatchQueueTest {

    private static final Logger log = LoggerFactory.getLogger(MatchQueueTest.class);

    private static final int MODE = MatchmakingTestFixtures.MODE;

    private MatchQueue queue;

    @BeforeEach
    void setUp() {
        queue = new MatchQueue();
        log.info("匹配队列初始化: mode={}, teamSizeHint=2", MODE);
    }

    /**
     * 验证点：enqueue 应按 FIFO 入队并返回队列长度。
     * <p>测试方法 {@code enqueueShouldAppendAndReturnSize}：
     * <ul>
     *   <li>{@code assertEquals(1, sizeAfterA);}</li>
     *   <li>{@code assertEquals(2, sizeAfterB);}</li>
     * </ul>
     */
    @Test
    @DisplayName("enqueue 应按 FIFO 入队并返回队列长度")
    void enqueueShouldAppendAndReturnSize() {
        int sizeAfterA = queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000));
        int sizeAfterB = queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_B, MODE, 20, 1500));

        log.info("入队校验: playerA={}, sizeAfterA={}, playerB={}, sizeAfterB={}, mode={}",
                MatchmakingTestFixtures.PLAYER_A, sizeAfterA,
                MatchmakingTestFixtures.PLAYER_B, sizeAfterB, MODE);
        assertEquals(1, sizeAfterA);
        assertEquals(2, sizeAfterB);
    }

    /**
     * 验证点：同一玩家重复入队应替换旧记录。
     * <p>测试方法 {@code enqueueShouldReplaceSamePlayer}：
     * <ul>
     *   <li>{@code assertEquals(1, sizeAfterRejoin);}</li>
     *   <li>{@code assertEquals(1, matched.size());}</li>
     *   <li>{@code assertEquals(30, matched.get(0).level());}</li>
     *   <li>{@code assertEquals(2000, matched.get(0).power());}</li>
     * </ul>
     */
    @Test
    @DisplayName("同一玩家重复入队应替换旧记录")
    void enqueueShouldReplaceSamePlayer() {
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000));
        int sizeAfterRejoin = queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 30, 2000));

        List<MatchQueue.QueueEntry> matched = queue.pollMatch(MODE, 1);

        log.info("重复入队校验: playerId={}, sizeAfterRejoin={}, polledLevel={}, polledPower={}, matchedSize={}",
                MatchmakingTestFixtures.PLAYER_A, sizeAfterRejoin,
                matched.isEmpty() ? -1 : matched.get(0).level(),
                matched.isEmpty() ? -1 : matched.get(0).power(),
                matched.size());
        assertEquals(1, sizeAfterRejoin);
        assertEquals(1, matched.size());
        assertEquals(30, matched.get(0).level());
        assertEquals(2000, matched.get(0).power());
    }

    /**
     * 验证点：cancel 应从队列移除指定玩家。
     * <p>测试方法 {@code cancelShouldRemovePlayer}：
     * <ul>
     *   <li>{@code assertTrue(cancelled);}</li>
     *   <li>{@code assertFalse(cancelMissing);}</li>
     *   <li>{@code assertEquals(1, remaining.size());}</li>
     *   <li>{@code assertEquals(MatchmakingTestFixtures.PLAYER_B, remaining.get(0).playerId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("cancel 应从队列移除指定玩家")
    void cancelShouldRemovePlayer() {
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000));
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_B, MODE, 20, 1500));

        boolean cancelled = queue.cancel(MatchmakingTestFixtures.PLAYER_A, MODE);
        boolean cancelMissing = queue.cancel(MatchmakingTestFixtures.PLAYER_C, MODE);
        List<MatchQueue.QueueEntry> remaining = queue.pollMatch(MODE, 1);

        log.info("取消排队校验: cancelPlayerA={}, cancelMissingPlayerC={}, remainingPlayerId={}, remainingSize={}",
                cancelled, cancelMissing,
                remaining.isEmpty() ? -1 : remaining.get(0).playerId(),
                remaining.size());
        assertTrue(cancelled);
        assertFalse(cancelMissing);
        assertEquals(1, remaining.size());
        assertEquals(MatchmakingTestFixtures.PLAYER_B, remaining.get(0).playerId());
    }

    /**
     * 验证点：pollMatch 人数不足应返回空且不消费。
     * <p>测试方法 {@code pollMatchShouldReturnEmptyWhenNotEnough}：
     * <ul>
     *   <li>{@code assertTrue(matched.isEmpty());}</li>
     *   <li>{@code assertEquals(1, stillThere.size());}</li>
     *   <li>{@code assertEquals(MatchmakingTestFixtures.PLAYER_A, stillThere.get(0).playerId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pollMatch 人数不足应返回空且不消费")
    void pollMatchShouldReturnEmptyWhenNotEnough() {
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000));

        List<MatchQueue.QueueEntry> matched = queue.pollMatch(MODE, 2);
        List<MatchQueue.QueueEntry> stillThere = queue.pollMatch(MODE, 1);

        log.info("人数不足校验: teamSize=2, matchedSize={}, stillThereSize={}, stillPlayerId={}",
                matched.size(), stillThere.size(),
                stillThere.isEmpty() ? -1 : stillThere.get(0).playerId());
        assertTrue(matched.isEmpty());
        assertEquals(1, stillThere.size());
        assertEquals(MatchmakingTestFixtures.PLAYER_A, stillThere.get(0).playerId());
    }

    /**
     * 验证点：pollMatch 人数足够应按 FIFO 取出一队。
     * <p>测试方法 {@code pollMatchShouldTakeTeamInFifoOrder}：
     * <ul>
     *   <li>{@code assertEquals(2, matched.size());}</li>
     *   <li>{@code assertEquals(MatchmakingTestFixtures.PLAYER_A, matched.get(0).playerId());}</li>
     *   <li>{@code assertEquals(MatchmakingTestFixtures.PLAYER_B, matched.get(1).playerId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pollMatch 人数足够应按 FIFO 取出一队")
    void pollMatchShouldTakeTeamInFifoOrder() {
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000));
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_B, MODE, 20, 1500));
        queue.enqueue(MatchmakingTestFixtures.entry(
                MatchmakingTestFixtures.PLAYER_C, MODE, 25, 1800));

        List<MatchQueue.QueueEntry> matched = queue.pollMatch(MODE, 2);

        log.info("成局取出校验: teamSize=2, matchedSize={}, firstPlayerId={}, secondPlayerId={}, leftoverHint=1",
                matched.size(),
                matched.get(0).playerId(),
                matched.get(1).playerId());
        assertEquals(2, matched.size());
        assertEquals(MatchmakingTestFixtures.PLAYER_A, matched.get(0).playerId());
        assertEquals(MatchmakingTestFixtures.PLAYER_B, matched.get(1).playerId());
    }
}
