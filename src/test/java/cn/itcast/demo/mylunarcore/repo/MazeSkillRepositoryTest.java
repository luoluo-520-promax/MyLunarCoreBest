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
 * MazeSkillRepository 迷宫技能仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MazeSkillRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MazeSkillRepository 迷宫技能仓储测试")
class MazeSkillRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(MazeSkillRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private MazeSkillRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new MazeSkillRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findById 应返回技能静态定义。
     * <p>测试方法 {@code findByIdShouldReturnMazeSkill}：
     * <ul>
     *   <li>{@code assertEquals(5001, skill.getId());}</li>
     *   <li>{@code assertEquals("烈焰斩", skill.getName());}</li>
     *   <li>{@code assertEquals(2, skill.getSkillType());}</li>
     *   <li>{@code assertEquals(1, skill.getTriggerBattle());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findById 应返回技能静态定义")
    void findByIdShouldReturnMazeSkill() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 5001).stringCol("name", "烈焰斩")
                .stringCol("description", "造成火焰伤害")
                .intCol("skill_type", 2).intCol("trigger_battle", 1)
                .intCol("adventure_modifier", 10)));

        MazeSkillRepository.MazeSkill skill = repository.findById(5001);

        assertEquals(5001, skill.getId());
        log.info("技能定义查询: skillId=5001, name={}, skillType={}, triggerBattle={}, adventureModifier={}",
                skill.getName(), skill.getSkillType(), skill.getTriggerBattle(), skill.getAdventureModifier());
        assertEquals("烈焰斩", skill.getName());
        assertEquals(2, skill.getSkillType());
        assertEquals(1, skill.getTriggerBattle());
    }

    /**
     * 验证点：不存在或异常时应返回 null。
     * <p>测试方法 {@code findByIdShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(skill);}</li>
     * </ul>
     */
    @Test
    @DisplayName("不存在或异常时应返回 null")
    void findByIdShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        MazeSkillRepository.MazeSkill skill = repository.findById(0);

        log.info("缺失技能校验: skillId=0, skillNull={}", skill == null);
        assertNull(skill);
    }
}
