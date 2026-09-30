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
 * NpcConfigRepository NPC 配置仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code NpcConfigRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("NpcConfigRepository NPC 配置仓储测试")
class NpcConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(NpcConfigRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private NpcConfigRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new NpcConfigRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：findById 应返回 NPC 配置行。
     * <p>测试方法 {@code findByIdShouldReturnNpcRow}：
     * <ul>
     *   <li>{@code assertEquals(3001, row.getId());}</li>
     *   <li>{@code assertEquals(4001, row.getDialogueId());}</li>
     *   <li>{@code assertEquals(5001, row.getRogueEventId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("findById 应返回 NPC 配置行")
    void findByIdShouldReturnNpcRow() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 3001).intCol("dialogue_id", 4001)
                .intCol("rogue_event_id", 5001)));

        NpcConfigRepository.NpcRow row = repository.findById(3001);

        assertEquals(3001, row.getId());
        log.info("NPC 配置查询: npcId=3001, dialogueId={}, rogueEventId={}",
                row.getDialogueId(), row.getRogueEventId());
        assertEquals(4001, row.getDialogueId());
        assertEquals(5001, row.getRogueEventId());
    }

    /**
     * 验证点：不存在时应返回 null。
     * <p>测试方法 {@code findByIdShouldReturnNullWhenMissing}：
     * <ul>
     *   <li>{@code assertNull(row);}</li>
     * </ul>
     */
    @Test
    @DisplayName("不存在时应返回 null")
    void findByIdShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));

        NpcConfigRepository.NpcRow row = repository.findById(0);

        log.info("缺失 NPC 校验: npcId=0, rowNull={}", row == null);
        assertNull(row);
    }
}
