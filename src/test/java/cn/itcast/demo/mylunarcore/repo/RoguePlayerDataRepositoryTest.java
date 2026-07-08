package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.RoguePlayerDataEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("RoguePlayerDataRepository 模拟宇宙玩家数据仓储测试")
class RoguePlayerDataRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(RoguePlayerDataRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private RoguePlayerDataRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RoguePlayerDataRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("loadOrCreate 应确保行存在并返回扩展数据")
    void loadOrCreateShouldEnsureRowAndReturnEntity() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("player_id", 77)
                        .stringCol("talents", "{\"1\":2}")
                        .stringCol("unlocked_miracles", "[10]")
                        .nullableIntCol("selected_path", 3)
                        .intCol("completed_runs", 5)
                        .intCol("highest_floor", 12)
                        .longCol("total_score", 8800L)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        RoguePlayerDataEntity entity = repository.loadOrCreate(77);

        assertEquals(77, entity.getPlayerId());
        log.info("Rogue 扩展数据查询: playerId=77, selectedPath={}, completedRuns={}, highestFloor={}, totalScore={}",
                entity.getSelectedPath(), entity.getCompletedRuns(), entity.getHighestFloor(),
                entity.getTotalScore());
        assertEquals(3, entity.getSelectedPath());
        assertEquals(5, entity.getCompletedRuns());
        assertEquals(12, entity.getHighestFloor());
        assertEquals(8800L, entity.getTotalScore());
    }

    @Test
    @DisplayName("updateSelectedPath 应更新命途")
    void updateSelectedPathShouldUpdatePath() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.updateSelectedPath(77, 4);

        log.info("命途更新校验: playerId=77, pathId=4, affectedRows={}", affected);
        assertEquals(1, affected);
    }

    @Test
    @DisplayName("applyRunEndStats 应累加通关统计")
    void applyRunEndStatsShouldUpdateStats() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.applyRunEndStats(77, 15, 1200L, true);

        log.info("局末统计更新: playerId=77, finalFloor=15, addScore=1200, completed=true, affectedRows={}",
                affected);
        assertEquals(1, affected);
    }
}
