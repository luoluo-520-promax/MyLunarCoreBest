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

/**
 * MonsterConfigRepository 怪物配置仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MonsterConfigRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MonsterConfigRepository 怪物配置仓储测试")
class MonsterConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(MonsterConfigRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private MonsterConfigRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new MonsterConfigRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findById 应返回怪物配置行。
     * <p>测试方法 {@code findByIdShouldReturnMonsterRow}：
     * <ul>
     *   <li>{@code assertEquals(101, row.getId());}</li>
     *   <li>{@code assertEquals(5, row.getLevel());}</li>
     *   <li>{@code assertEquals(300, row.getHp());}</li>
     *   <li>{@code assertEquals("[1,2]", row.getBuffsJson());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findById 应返回怪物配置行")
    void findByIdShouldReturnMonsterRow() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 101).stringCol("name", "史莱姆")
                .intCol("level", 5).intCol("hp", 300)
                .intCol("attack", 20).intCol("defense", 10)
                .intCol("speed", 8).intCol("model_id", 10001)
                .stringCol("buffs", "[1,2]")));

        MonsterConfigRepository.MonsterRow row = repository.findById(101);

        assertEquals(101, row.getId());
        log.info("怪物配置查询: monsterId=101, level={}, hp={}, buffsJson={}",
                row.getLevel(), row.getHp(), row.getBuffsJson());
        assertEquals(5, row.getLevel());
        assertEquals(300, row.getHp());
        assertEquals("[1,2]", row.getBuffsJson());
    }

    /**
     * 验证点：不存在或异常时应返回 null。
     * <p>测试方法 {@code findByIdShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(row);}</li>
     * </ul>
     */
    @Test
    @DisplayName("不存在或异常时应返回 null")
    void findByIdShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        MonsterConfigRepository.MonsterRow row = repository.findById(9999);

        log.info("缺失配置校验: monsterId=9999, rowNull={}", row == null);
        assertNull(row);
    }
}
