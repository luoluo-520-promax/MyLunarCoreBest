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

/**
 * GameDataRepository 游戏数据仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GameDataRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
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

    /**
     * 验证点：findByKey 应返回游戏数据实体。
     * <p>测试方法 {@code findByKeyShouldReturnEntity}：
     * <ul>
     *   <li>{@code assertEquals("hotfix_v1", entity.getDataKey());}</li>
     *   <li>{@code assertEquals("{\"version\":1}", entity.getPayloadJson());}</li>
     * </ul>
     */
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

    /**
     * 验证点：键不存在时应返回 null。
     * <p>测试方法 {@code findByKeyShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(entity);}</li>
     * </ul>
     */
    @Test
    @DisplayName("键不存在时应返回 null")
    void findByKeyShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        GameDataEntity entity = repository.findByKey("not_exist");

        log.info("缺失键校验: dataKey=not_exist, entityNull={}", entity == null);
        assertNull(entity);
    }

    /**
     * 验证点：upsert 应写入或更新 game_data。
     * <p>测试方法 {@code upsertShouldUpdateRow}：
     * <ul>
     *   <li>{@code assertEquals(1, affected);}</li>
     * </ul>
     */
    @Test
    @DisplayName("upsert 应写入或更新 game_data")
    void upsertShouldUpdateRow() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.upsert("activity.5000701", "{\"activityId\":5000701}");

        assertEquals(1, affected);
        log.info("upsert 校验: dataKey=activity.5000701, affectedRows={}", affected);
    }
}
