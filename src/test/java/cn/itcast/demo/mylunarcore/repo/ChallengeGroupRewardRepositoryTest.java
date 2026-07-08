package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.ChallengeGroupRewardEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("ChallengeGroupRewardRepository 挑战组奖励仓储测试")
class ChallengeGroupRewardRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeGroupRewardRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private ChallengeGroupRewardRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new ChallengeGroupRewardRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("find 应确保行存在并返回领奖进度")
    void findShouldReturnRewardEntity() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("id", 5L).intCol("player_id", 77)
                        .intCol("group_id", 10).intCol("taken_stars", 6)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        Optional<ChallengeGroupRewardEntity> opt = repository.find(77, 10);

        assertTrue(opt.isPresent());
        ChallengeGroupRewardEntity entity = opt.get();
        log.info("组奖励查询: playerId=77, groupId=10, takenStars={}", entity.getTakenStars());
        assertEquals(6, entity.getTakenStars());
    }

    @Test
    @DisplayName("updateTakenStars 应更新已领星级掩码")
    void updateTakenStarsShouldUpdateMask() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.updateTakenStars(77, 10, 15);

        log.info("星级掩码更新: playerId=77, groupId=10, takenStarsMask=15, affectedRows={}", affected);
        assertEquals(1, affected);
    }

    @Test
    @DisplayName("ensureRow 应幂等插入默认行")
    void ensureRowShouldInsertDefaultRow() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        repository.ensureRow(77, 20);

        log.info("默认行确保: playerId=77, groupId=20");
    }
}
