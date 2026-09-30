package cn.itcast.demo.mylunarcore.challenge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ChallengeManager 挑战运行时索引测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ChallengeManagerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ChallengeManager 挑战运行时索引测试")
class ChallengeManagerTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeManagerTest.class);

    private static final int PLAYER_ID = 77;
    private static final int CHALLENGE_ID = 100;

    private ChallengeManager manager;

    @BeforeEach
    void setUp() {
        manager = new ChallengeManager();
        log.info("挑战管理器初始化: initialUidSeq=10000");
    }

    /**
     * 验证点：nextUid 应单调递增。
     * <p>测试方法 {@code nextUidShouldIncrement}：
     * <ul>
     *   <li>{@code assertEquals(10_001L, uid1);}</li>
     *   <li>{@code assertEquals(10_002L, uid2);}</li>
     *   <li>{@code assertEquals(1L, uid2 - uid1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("nextUid 应单调递增")
    void nextUidShouldIncrement() {
        long uid1 = manager.nextUid();
        long uid2 = manager.nextUid();
        log.info("UID 分配校验: uid1={}, uid2={}, delta={}", uid1, uid2, uid2 - uid1);
        assertEquals(10_001L, uid1);
        assertEquals(10_002L, uid2);
        assertEquals(1L, uid2 - uid1);
    }

    /**
     * 验证点：put/get 应正确缓存与读取运行时。
     * <p>测试方法 {@code putAndGetShouldStoreRuntime}：
     * <ul>
     *   <li>{@code assertNotNull(loaded);}</li>
     *   <li>{@code assertEquals(challengeUid, loaded.getChallengeUid());}</li>
     *   <li>{@code assertEquals(PLAYER_ID, loaded.getPlayerId());}</li>
     *   <li>{@code assertEquals(CHALLENGE_ID, loaded.getChallengeId());}</li>
     *   <li>{@code assertEquals(1, loaded.getStatus());}</li>
     *   <li>{@code assertEquals(2, loaded.getWaveCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("put/get 应正确缓存与读取运行时")
    void putAndGetShouldStoreRuntime() {
        long challengeUid = manager.nextUid();
        ChallengeRuntime runtime = ChallengeTestFixtures.createRuntime(challengeUid, PLAYER_ID, CHALLENGE_ID);
        manager.put(runtime);

        ChallengeRuntime loaded = manager.get(challengeUid);
        assertNotNull(loaded);
        log.info("运行时缓存校验: challengeUid={}, playerId={}, challengeId={}, status={}, waveCount={}",
                loaded.getChallengeUid(), loaded.getPlayerId(), loaded.getChallengeId(),
                loaded.getStatus(), loaded.getWaveCount());
        assertEquals(challengeUid, loaded.getChallengeUid());
        assertEquals(PLAYER_ID, loaded.getPlayerId());
        assertEquals(CHALLENGE_ID, loaded.getChallengeId());
        assertEquals(1, loaded.getStatus());
        assertEquals(2, loaded.getWaveCount());
    }

    /**
     * 验证点：remove 应释放已结束挑战。
     * <p>测试方法 {@code removeShouldDropRuntime}：
     * <ul>
     *   <li>{@code assertNotNull(manager.get(challengeUid));}</li>
     *   <li>{@code assertNull(afterRemove);}</li>
     * </ul>
     */
    @Test
    @DisplayName("remove 应释放已结束挑战")
    void removeShouldDropRuntime() {
        long challengeUid = manager.nextUid();
        manager.put(ChallengeTestFixtures.createRuntime(challengeUid, PLAYER_ID, CHALLENGE_ID));
        assertNotNull(manager.get(challengeUid));

        manager.remove(challengeUid);
        ChallengeRuntime afterRemove = manager.get(challengeUid);
        log.info("运行时移除校验: challengeUid={}, existsBeforeRemove=true, existsAfterRemove={}",
                challengeUid, afterRemove != null);
        assertNull(afterRemove);
    }

    /**
     * 验证点：查询不存在的 challengeUid 应返回 null。
     * <p>测试方法 {@code getMissingUidShouldReturnNull}：
     * <ul>
     *   <li>{@code assertNull(missing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询不存在的 challengeUid 应返回 null")
    void getMissingUidShouldReturnNull() {
        ChallengeRuntime missing = manager.get(99_999L);
        log.info("缺失 UID 查询校验: challengeUid=99999, runtime={}", missing);
        assertNull(missing);
    }
}
