package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.RogueTalentEntity;
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

@DisplayName("RogueTalentRepository 模拟宇宙天赋仓储测试")
class RogueTalentRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(RogueTalentRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private RogueTalentRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new RogueTalentRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("listByPlayerId 应按 talent_id 返回天赋列表")
    void listByPlayerIdShouldReturnTalents() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("player_id", 77).intCol("talent_id", 1)
                        .intCol("level", 2).intCol("activated", 1)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-05 10:00:00"))),
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("player_id", 77).intCol("talent_id", 2)
                        .intCol("level", 1).intCol("activated", 0)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-05 10:00:00")))
        ));

        List<RogueTalentEntity> talents = repository.listByPlayerId(77);

        assertEquals(2, talents.size());
        log.info("天赋列表查询: playerId=77, talentCount={}, talent1Id={}, talent1Level={}, talent1Activated={}",
                talents.size(), talents.get(0).getTalentId(), talents.get(0).getLevel(),
                talents.get(0).isActivated());
        assertEquals(1, talents.get(0).getTalentId());
        assertEquals(2, talents.get(0).getLevel());
    }

    @Test
    @DisplayName("load 不存在时应返回 null")
    void loadShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        RogueTalentEntity talent = repository.load(77, 99);

        log.info("缺失天赋校验: playerId=77, talentId=99, talentNull={}", talent == null);
        assertNull(talent);
    }

    @Test
    @DisplayName("upgradeTalent 应执行 upsert 并读回最新行")
    void upgradeTalentShouldUpsertAndReload() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("player_id", 77).intCol("talent_id", 5)
                        .intCol("level", 3).intCol("activated", 1)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        RogueTalentEntity talent = repository.upgradeTalent(77, 5, 3);

        assertEquals(3, talent.getLevel());
        log.info("天赋升级校验: playerId=77, talentId=5, level={}, activated={}",
                talent.getLevel(), talent.isActivated());
        assertEquals(true, talent.isActivated());
    }
}
