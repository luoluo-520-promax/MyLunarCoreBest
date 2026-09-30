package cn.itcast.demo.mylunarcore.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * MazeSkillActionRepository 迷宫技能行为仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MazeSkillActionRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MazeSkillActionRepository 迷宫技能行为仓储测试")
class MazeSkillActionRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(MazeSkillActionRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private MazeSkillActionRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new MazeSkillActionRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findBySkillId 应按 action_order 返回有序行为链。
     * <p>测试方法 {@code findBySkillIdShouldReturnOrderedActions}：
     * <ul>
     *   <li>{@code assertEquals(2, actions.size());}</li>
     *   <li>{@code assertEquals(1, actions.get(0).getActionOrder());}</li>
     *   <li>{@code assertEquals("{\"hp_change\":-150}", actions.get(0).getParamsJson());}</li>
     *   <li>{@code assertEquals(2, actions.get(1).getActionOrder());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findBySkillId 应按 action_order 返回有序行为链")
    void findBySkillIdShouldReturnOrderedActions() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("id", 1).intCol("skill_id", 1001)
                        .intCol("action_type", 1).intCol("action_category", 0)
                        .intCol("action_order", 1).stringCol("params", "{\"hp_change\":-150}")),
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("id", 2).intCol("skill_id", 1001)
                        .intCol("action_type", 2).intCol("action_category", 0)
                        .intCol("action_order", 2).stringCol("params", "{\"buff_id\":5}"))
        ));

        List<MazeSkillActionRepository.MazeSkillActionRow> actions = repository.findBySkillId(1001);

        assertEquals(2, actions.size());
        log.info("技能行为链查询: skillId=1001, actionCount={}, firstOrder={}, firstParams={}, secondOrder={}",
                actions.size(), actions.get(0).getActionOrder(), actions.get(0).getParamsJson(),
                actions.get(1).getActionOrder());
        assertEquals(1, actions.get(0).getActionOrder());
        assertEquals("{\"hp_change\":-150}", actions.get(0).getParamsJson());
        assertEquals(2, actions.get(1).getActionOrder());
    }

    /**
     * 验证点：查库异常时应返回空列表。
     * <p>测试方法 {@code findBySkillIdShouldReturnEmptyOnException}：
     * <ul>
     *   <li>{@code assertTrue(actions.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查库异常时应返回空列表")
    void findBySkillIdShouldReturnEmptyOnException() {
        RepoTestFixtures.stubQueryThrows(jdbcTemplate, new RuntimeException("db down"));

        List<MazeSkillActionRepository.MazeSkillActionRow> actions = repository.findBySkillId(2000);

        log.info("异常容错校验: skillId=2000, actionCount={}", actions.size());
        assertTrue(actions.isEmpty());
    }
}
