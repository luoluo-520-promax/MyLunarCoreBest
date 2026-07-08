package cn.itcast.demo.mylunarcore.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("BattleRepository 战斗主表仓储测试")
class BattleRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(BattleRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private BattleRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new BattleRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("insertBattle 应返回自增战斗 ID")
    void insertBattleShouldReturnGeneratedId() {
        RepoTestFixtures.stubInsertGeneratedKey(jdbcTemplate, 9001L);
        Timestamp startTime = RepoTestFixtures.ts("2026-06-06 10:00:00");

        long battleId = repository.insertBattle(77, 1, 100, startTime);

        log.info("战斗插入校验: playerId=77, lineupId=1, stageId=100, battleId={}", battleId);
        assertEquals(9001L, battleId);
    }

    @Test
    @DisplayName("updateBattleResult 应更新结束状态与统计")
    void updateBattleResultShouldUpdateRow() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        Timestamp endTime = RepoTestFixtures.ts("2026-06-06 10:05:00");
        String statistics = "{\"damage\":1500}";

        repository.updateBattleResult(9001L, 1, statistics, endTime);

        log.info("战斗结果更新: battleId=9001, endStatus=1, statistics={}, endTime={}",
                statistics, endTime);
    }
}
