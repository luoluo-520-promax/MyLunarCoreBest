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

/**
 * BattleMonsterWaveRepository 战斗波次仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code BattleMonsterWaveRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
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

    /**
     * 验证点：loadWavesByStageId 应按 wave_order 返回波次列表。
     * <p>测试方法 {@code loadWavesByStageIdShouldReturnOrderedWaves}：
     * <ul>
     *   <li>{@code assertEquals(2, waves.size());}</li>
     *   <li>{@code assertEquals(1, waves.get(0).getWaveOrder());}</li>
     *   <li>{@code assertEquals("[101,102]", waves.get(0).getMonstersJson());}</li>
     *   <li>{@code assertEquals(2, waves.get(1).getWaveOrder());}</li>
     * </ul>
     */
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
