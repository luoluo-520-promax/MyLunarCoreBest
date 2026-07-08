package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.GameDataEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

@DisplayName("GameDataRepository 游戏数据仓储测试")
class GameDataRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(GameDataRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private GameDataRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new GameDataRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("findByKey 应返回游戏数据实体")
    void findByKeyShouldReturnEntity() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .stringCol("data_key", "hotfix_v1")
                        .stringCol("payload_json", "{\"version\":1}")
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 08:00:00")))
        ));

        GameDataEntity entity = repository.findByKey("hotfix_v1");

        assertEquals("hotfix_v1", entity.getDataKey());
        log.info("游戏数据查询: dataKey={}, payloadJson={}, updatedAt={}",
                entity.getDataKey(), entity.getPayloadJson(), entity.getUpdatedAt());
        assertEquals("{\"version\":1}", entity.getPayloadJson());
    }

    @Test
    @DisplayName("键不存在时应返回 null")
    void findByKeyShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        GameDataEntity entity = repository.findByKey("not_exist");

        log.info("缺失键校验: dataKey=not_exist, entityNull={}", entity == null);
        assertNull(entity);
    }
}
