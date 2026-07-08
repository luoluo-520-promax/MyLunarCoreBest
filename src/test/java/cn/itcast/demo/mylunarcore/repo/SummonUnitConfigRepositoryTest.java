package cn.itcast.demo.mylunarcore.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

@DisplayName("SummonUnitConfigRepository 召唤物配置仓储测试")
class SummonUnitConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(SummonUnitConfigRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private SummonUnitConfigRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new SummonUnitConfigRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("findById 应返回召唤物配置")
    void findByIdShouldReturnSummonUnitRow() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 6001).intCol("duration", 3)
                .objectCol("hp", 500).intCol("attack", 80)
                .intCol("can_be_targeted", 1)));

        SummonUnitConfigRepository.SummonUnitRow row = repository.findById(6001);

        assertEquals(6001, row.getSummonConfigId());
        log.info("召唤物配置查询: summonConfigId=6001, duration={}, hp={}",
                row.getDuration(), row.getHp());
        assertEquals(3, row.getDuration());
        assertEquals(500, row.getHp());
    }

    @Test
    @DisplayName("HP 为 NULL 时应默认 0")
    void findByIdShouldDefaultHpToZero() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 6002).intCol("duration", 2)
                .objectCol("hp", null).intCol("attack", 50)
                .intCol("can_be_targeted", 0)));

        SummonUnitConfigRepository.SummonUnitRow row = repository.findById(6002);

        log.info("NULL HP 校验: summonConfigId=6002, duration={}, hp={}",
                row.getDuration(), row.getHp());
        assertEquals(0, row.getHp());
    }

    @Test
    @DisplayName("不存在时应返回 null")
    void findByIdShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        SummonUnitConfigRepository.SummonUnitRow row = repository.findById(0);

        log.info("缺失召唤物校验: summonConfigId=0, rowNull={}", row == null);
        assertNull(row);
    }
}
