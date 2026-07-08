package cn.itcast.demo.mylunarcore.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("BattleMonsterWaveRepository 战斗波次仓储测试")
class BattleMonsterWaveRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(BattleMonsterWaveRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private BattleMonsterWaveRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new BattleMonsterWaveRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("loadWavesByStageId 应按 wave_order 返回波次列表")
    void loadWavesByStageIdShouldReturnOrderedWaves() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("id", 1).intCol("battle_stage_id", 100)
                        .intCol("wave_order", 1).stringCol("monsters", "[101,102]")
                        .intCol("custom_level", 2)),
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("id", 2).intCol("battle_stage_id", 100)
                        .intCol("wave_order", 2).stringCol("monsters", "[201]")
                        .intCol("custom_level", 3))
        ));

        List<BattleMonsterWaveRepository.WaveConfig> waves = repository.loadWavesByStageId(100);

        assertEquals(2, waves.size());
        log.info("波次配置查询: stageId=100, waveCount={}, wave1Order={}, wave1Monsters={}, wave2Order={}",
                waves.size(), waves.get(0).getWaveOrder(), waves.get(0).getMonstersJson(),
                waves.get(1).getWaveOrder());
        assertEquals(1, waves.get(0).getWaveOrder());
        assertEquals("[101,102]", waves.get(0).getMonstersJson());
        assertEquals(2, waves.get(1).getWaveOrder());
    }
}
