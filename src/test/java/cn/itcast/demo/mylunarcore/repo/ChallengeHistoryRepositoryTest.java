package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("ChallengeHistoryRepository 挑战历史仓储测试")
class ChallengeHistoryRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeHistoryRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private ChallengeHistoryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ChallengeHistoryRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("findByPlayerAndChallenge 应返回历史最佳记录")
    void findByPlayerAndChallengeShouldReturnEntity() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("id", 1L).intCol("player_id", 77)
                        .intCol("challenge_id", 1001).intCol("group_id", 10)
                        .intCol("stars", 7).intCol("score", 9500)
                        .intCol("taken_reward", 3)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        Optional<ChallengeHistoryEntity> opt = repository.findByPlayerAndChallenge(77, 1001);

        assertTrue(opt.isPresent());
        ChallengeHistoryEntity entity = opt.get();
        log.info("挑战历史查询: playerId=77, challengeId=1001, stars={}, score={}, takenReward={}",
                entity.getStars(), entity.getScore(), entity.getTakenReward());
        assertEquals(7, entity.getStars());
        assertEquals(9500, entity.getScore());
        assertEquals(3, entity.getTakenReward());
    }

    @Test
    @DisplayName("findByPlayerAndChallenge 无记录时应返回 empty")
    void findByPlayerAndChallengeShouldReturnEmptyWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        Optional<ChallengeHistoryEntity> opt = repository.findByPlayerAndChallenge(77, 9999);

        log.info("缺失历史校验: playerId=77, challengeId=9999, present={}", opt.isPresent());
        assertTrue(opt.isEmpty());
    }

    @Test
    @DisplayName("countHistory 应统计满足筛选条件的历史条数")
    void countHistoryShouldReturnCount() {
        RepoTestFixtures.stubQueryForObjectScalar(jdbcTemplate, 3);

        int count = repository.countHistory(77, 10, 1001);

        log.info("历史统计: playerId=77, groupId=10, challengeId=1001, count={}", count);
        assertEquals(3, count);
    }

    @Test
    @DisplayName("listHistory 应分页返回历史列表")
    void listHistoryShouldReturnPagedList() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("id", 2L).intCol("player_id", 77)
                        .intCol("challenge_id", 1002).intCol("group_id", 10)
                        .intCol("stars", 3).intCol("score", 8000)
                        .intCol("taken_reward", 1)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-02 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        List<ChallengeHistoryEntity> list = repository.listHistory(77, 10, null, 0, 10);

        assertEquals(1, list.size());
        log.info("历史分页查询: playerId=77, groupId=10, offset=0, limit=10, rowCount={}, challengeId={}",
                list.size(), list.get(0).getChallengeId());
        assertEquals(1002, list.get(0).getChallengeId());
    }

    @Test
    @DisplayName("upsertBestResult 应执行合并更新")
    void upsertBestResultShouldUpsert() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        repository.upsertBestResult(77, 1001, 10, 7, 9600);

        log.info("最佳成绩合并: playerId=77, challengeId=1001, groupId=10, starsMask=7, score=9600");
    }
}
