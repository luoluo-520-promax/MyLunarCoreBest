package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.AccountEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("PlayerDataRepository 玩家数据仓储测试")
class PlayerDataRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerDataRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private PlayerDataRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new PlayerDataRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    @Test
    @DisplayName("findAccountByUsername 应返回账号实体")
    void findAccountByUsernameShouldReturnAccount() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .stringCol("id", "1001").stringCol("username", "traveler")
                        .stringCol("password", "hash").stringCol("email", "a@test.com")
                        .stringCol("phone", "13800000000").intCol("status", 1))
        ));

        AccountEntity account = repository.findAccountByUsername("traveler");

        assertEquals("traveler", account.getUsername());
        log.info("账号查询: username=traveler, accountId={}, status={}", account.getId(), account.getStatus());
        assertEquals("1001", account.getId());
        assertEquals(1, account.getStatus());
    }

    @Test
    @DisplayName("loadPlayerByUid 应返回玩家核心字段")
    void loadPlayerByUidShouldReturnPlayer() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("uid", 77L).longCol("account_id", 1001L)
                        .stringCol("nickname", "开拓者").intCol("level", 60)
                        .longCol("exp", 120000L).intCol("world_level", 6)
                        .intCol("stamina", 180).stringCol("currency", "{\"gold\":1000}")
                        .intCol("scene_id", 1001)
                        .bigDecimalCol("pos_x", new BigDecimal("12.5"))
                        .bigDecimalCol("pos_y", new BigDecimal("0.0"))
                        .bigDecimalCol("pos_z", new BigDecimal("8.3"))
                        .timestampCol("last_login", RepoTestFixtures.ts("2026-06-06 09:00:00"))
                        .timestampCol("last_logout", RepoTestFixtures.ts("2026-06-05 23:00:00")))
        ));

        PlayerEntity player = repository.loadPlayerByUid(77);

        assertEquals(77L, player.getUid());
        log.info("玩家核心查询: uid=77, nickname={}, level={}, stamina={}, sceneId={}",
                player.getNickname(), player.getLevel(), player.getStamina(), player.getSceneId());
        assertEquals("开拓者", player.getNickname());
        assertEquals(60, player.getLevel());
        assertEquals(1001, player.getSceneId());
    }

    @Test
    @DisplayName("loadCoreData 应加载玩家并初始化空子表集合")
    void loadCoreDataShouldInitializeEmptyCollections() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("uid", 77L).longCol("account_id", 1001L)
                        .stringCol("nickname", "开拓者").intCol("level", 1)
                        .longCol("exp", 0L).intCol("world_level", 0)
                        .intCol("stamina", 0).stringCol("currency", null)
                        .intCol("scene_id", 0)
                        .bigDecimalCol("pos_x", BigDecimal.ZERO)
                        .bigDecimalCol("pos_y", BigDecimal.ZERO)
                        .bigDecimalCol("pos_z", BigDecimal.ZERO)
                        .timestampCol("last_login", null)
                        .timestampCol("last_logout", null))
        ));

        PlayerData data = repository.loadCoreData(77);

        assertNotNull(data.getPlayer());
        log.info("核心数据装载: uid=77, avatarCount={}, itemCount={}, lineupCount={}",
                data.getAvatars().size(), data.getItems().size(), data.getLineups().size());
        assertTrue(data.getAvatars().isEmpty());
        assertTrue(data.getItems().isEmpty());
        assertTrue(data.getLineups().isEmpty());
    }

    @Test
    @DisplayName("persistPlayerSnapshot 无效实体应返回 0")
    void persistPlayerSnapshotShouldRejectInvalidEntity() {
        int affectedNull = repository.persistPlayerSnapshot(null);
        PlayerEntity invalid = new PlayerEntity();
        invalid.setUid(0);
        int affectedZeroUid = repository.persistPlayerSnapshot(invalid);

        log.info("快照持久化拒绝: affectedNull={}, affectedZeroUid={}", affectedNull, affectedZeroUid);
        assertEquals(0, affectedNull);
        assertEquals(0, affectedZeroUid);
    }

    @Test
    @DisplayName("createDefaultPlayerForAccount 非法 account.id 应抛异常")
    void createDefaultPlayerShouldRejectInvalidAccountId() {
        AccountEntity account = new AccountEntity();
        account.setId("not-a-number");

        log.info("创角非法账号校验: accountId={}", account.getId());
        assertThrows(IllegalArgumentException.class,
                () -> repository.createDefaultPlayerForAccount(account));
    }

    @Test
    @DisplayName("findAccountByUsername 不存在时应返回 null")
    void findAccountByUsernameShouldReturnNullWhenMissing() {
        RepoTestFixtures.stubQueryRows(jdbcTemplate, Collections.emptyList());

        AccountEntity account = repository.findAccountByUsername("ghost");

        log.info("缺失账号校验: username=ghost, accountNull={}", account == null);
        assertNull(account);
    }
}
