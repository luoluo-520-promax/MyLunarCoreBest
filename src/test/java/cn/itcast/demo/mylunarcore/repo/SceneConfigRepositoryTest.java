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

    @Test
    @DisplayName("无配置时应返回 null")
    void findGroupsShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        SceneConfigRepository.SceneRow row = repository.findGroups(9, 9);

        log.info("缺失场景校验: planeId=9, floorId=9, rowNull={}", row == null);
        assertNull(row);
    }
}
