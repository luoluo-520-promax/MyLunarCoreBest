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
 * SceneConfigRepository 场景配置仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code SceneConfigRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("SceneConfigRepository 场景配置仓储测试")
class SceneConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(SceneConfigRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private SceneConfigRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new SceneConfigRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findGroups 应返回场景分组 JSON。
     * <p>测试方法 {@code findGroupsShouldReturnSceneRow}：
     * <ul>
     *   <li>{@code assertEquals(1, row.getPlaneId());}</li>
     *   <li>{@code assertEquals(2, row.getFloorId());}</li>
     *   <li>{@code assertEquals("[{\"npc\":1001}]", row.getGroupsJson());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findGroups 应返回场景分组 JSON")
    void findGroupsShouldReturnSceneRow() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("plane_id", 1).intCol("floor_id", 2)
                .stringCol("groups", "[{\"npc\":1001}]")));

        SceneConfigRepository.SceneRow row = repository.findGroups(1, 2);

        assertEquals(1, row.getPlaneId());
        log.info("场景配置查询: planeId=1, floorId=2, groupsJson={}", row.getGroupsJson());
        assertEquals(2, row.getFloorId());
        assertEquals("[{\"npc\":1001}]", row.getGroupsJson());
    }

    /**
     * 验证点：无配置时应返回 null。
     * <p>测试方法 {@code findGroupsShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(row);}</li>
     * </ul>
     */
    @Test
    @DisplayName("无配置时应返回 null")
    void findGroupsShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        SceneConfigRepository.SceneRow row = repository.findGroups(9, 9);

        log.info("缺失场景校验: planeId=9, floorId=9, rowNull={}", row == null);
        assertNull(row);
    }
}
