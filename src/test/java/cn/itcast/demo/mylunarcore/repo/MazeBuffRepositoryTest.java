package cn.itcast.demo.mylunarcore.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@DisplayName("MazeBuffRepository 迷宫 Buff 仓储测试")
class MazeBuffRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(MazeBuffRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private MazeBuffRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new MazeBuffRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("findMaxStack 应返回数据库配置的叠层上限")
    void findMaxStackShouldReturnConfiguredValue() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 7).intCol("max_stack", 5)));

        int maxStack = repository.findMaxStack(7);

        log.info("Buff 叠层查询: buffId=7, maxStack={}", maxStack);
        assertEquals(5, maxStack);
    }

    @Test
    @DisplayName("findMaxStack 应使用缓存避免重复查库")
    void findMaxStackShouldUseCache() {
        RepoTestFixtures.stubQueryForObject(jdbcTemplate, RepoTestFixtures.mockResultSet(b -> b
                .intCol("id", 8).intCol("max_stack", 3)));

        int first = repository.findMaxStack(8);
        int second = repository.findMaxStack(8);

        log.info("缓存命中校验: buffId=8, firstMaxStack={}, secondMaxStack={}", first, second);
        assertEquals(3, first);
        assertEquals(3, second);
    }

    @Test
    @DisplayName("非法 buffId 或查库失败时应返回 1")
    void findMaxStackShouldDefaultToOne() {
        int invalid = repository.findMaxStack(0);
        RepoTestFixtures.stubQueryForObjectThrows(jdbcTemplate, new RuntimeException("not found"));
        int missing = repository.findMaxStack(99);

        log.info("默认值校验: buffId=0 maxStack={}, buffId=99 maxStack={}", invalid, missing);
        assertEquals(1, invalid);
        assertEquals(1, missing);
    }
}
