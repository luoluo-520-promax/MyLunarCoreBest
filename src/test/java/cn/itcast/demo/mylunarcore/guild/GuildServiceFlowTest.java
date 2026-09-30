package cn.itcast.demo.mylunarcore.guild;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公会核心业务流程：H2(MySQL 模式) 真实 JDBC 集成测试。
 */
@DisplayName("公会业务全流程")
class GuildServiceFlowTest {

    private static final int LEADER = 1001;
    private static final int MEMBER = 1002;
    private static final int OTHER = 1003;

    private JdbcTemplate jdbc;
    private Path tempDir;
    private GuildService guildService;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:guild_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE guild (
                    guild_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    notice VARCHAR(512) NOT NULL DEFAULT '',
                    level INT NOT NULL DEFAULT 1,
                    exp INT NOT NULL DEFAULT 0,
                    leader_id INT NOT NULL,
                    member_count INT NOT NULL DEFAULT 1,
                    max_members INT NOT NULL DEFAULT 30,
                    created_at VARCHAR(40) NOT NULL,
                    UNIQUE (name)
                )
                """);
        jdbc.execute("""
                CREATE TABLE guild_member (
                    guild_id BIGINT NOT NULL,
                    player_id INT NOT NULL,
                    role TINYINT NOT NULL DEFAULT 0,
                    contribution INT NOT NULL DEFAULT 0,
                    weekly_contrib INT NOT NULL DEFAULT 0,
                    joined_at VARCHAR(40) NOT NULL,
                    PRIMARY KEY (guild_id, player_id),
                    UNIQUE (player_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE guild_shop_purchase (
                    guild_id BIGINT NOT NULL,
                    player_id INT NOT NULL,
                    product_id INT NOT NULL,
                    week_key VARCHAR(16) NOT NULL,
                    buy_count INT NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, player_id, product_id, week_key)
                )
                """);

        tempDir = Files.createTempDirectory("guild-shop-");
        Files.writeString(tempDir.resolve("GuildShopConfigs.json"), """
                [
                  {"productId":1,"itemId":101,"count":60,"contributionCost":100,"weeklyLimit":2,"name":"星琼小份"},
                  {"productId":2,"itemId":201,"count":1,"contributionCost":500,"weeklyLimit":1,"name":"公会材料"}
                ]
                """);

        guildService = new GuildService(jdbc, new ObjectMapper(), new DbGuildContributionRank(jdbc), tempDir.toString());
        guildService.reloadShopCatalog();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (Stream<Path> walk = Files.walk(tempDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("创建成功且会长入会")
    void createSuccessAndLeaderIsMember() {
        GuildService.OpResult r = guildService.create(LEADER, "开拓者公会", "欢迎");
        assertTrue(r.success());
        assertEquals(0, r.retcode());

        GuildService.GuildInfo g = guildService.loadGuildForPlayer(LEADER);
        assertNotNull(g);
        assertEquals("开拓者公会", g.name());
        assertEquals(LEADER, g.leaderId());
        assertEquals(1, g.memberCount());

        GuildService.MemberInfo me = guildService.loadMember(g.guildId(), LEADER);
        assertNotNull(me);
        assertEquals(2, me.role());
    }

    @Test
    @DisplayName("非法创建参数与重名/已在公会被拒绝")
    void rejectBlankNameSelfOrDuplicateMembership() {
        assertEquals(2, guildService.create(0, "x", "").retcode());
        assertEquals(2, guildService.create(LEADER, "  ", "").retcode());
        assertEquals(2, guildService.create(LEADER, "a".repeat(25), "").retcode());

        assertTrue(guildService.create(LEADER, "独一份", "").success());
        assertEquals(4, guildService.create(LEADER, "另一个", "").retcode());
        assertEquals(5, guildService.create(MEMBER, "独一份", "").retcode());
    }

    @Test
    @DisplayName("成员加入后退出更新人数")
    void memberJoinThenLeaveUpdatesCount() {
        assertTrue(guildService.create(LEADER, "星核社", "").success());
        long guildId = guildService.loadGuildForPlayer(LEADER).guildId();

        assertTrue(guildService.join(MEMBER, guildId).success());
        assertEquals(2, guildService.loadGuild(guildId).memberCount());
        assertEquals(2, guildService.listMembers(guildId).size());

        assertTrue(guildService.leave(MEMBER).success());
        assertEquals(1, guildService.loadGuild(guildId).memberCount());
        assertNull(guildService.findGuildIdByPlayer(MEMBER));
    }

    @Test
    @DisplayName("有成员时会长不可直接退出")
    void leaderCannotLeaveWhileOthersRemain() {
        assertTrue(guildService.create(LEADER, "领袖社", "").success());
        long guildId = guildService.loadGuildForPlayer(LEADER).guildId();
        assertTrue(guildService.join(MEMBER, guildId).success());

        assertEquals(7, guildService.leave(LEADER).retcode());
        assertNotNull(guildService.loadGuild(guildId));
    }

    @Test
    @DisplayName("独任会长退出解散公会")
    void soloLeaderLeaveDissolvesGuild() {
        assertTrue(guildService.create(LEADER, "临时社", "").success());
        assertTrue(guildService.leave(LEADER).success());
        assertNull(guildService.findGuildIdByPlayer(LEADER));
        assertTrue(jdbc.queryForList("SELECT * FROM guild").isEmpty());
    }

    @Test
    @DisplayName("加入不存在公会或已在其他公会被拒绝")
    void joinRejectsMissingOrAlreadyInGuild() {
        assertEquals(3, guildService.join(MEMBER, 99999L).retcode());
        assertTrue(guildService.create(LEADER, "A社", "").success());
        long a = guildService.loadGuildForPlayer(LEADER).guildId();
        assertTrue(guildService.create(OTHER, "B社", "").success());
        assertEquals(4, guildService.join(OTHER, a).retcode());
    }

    @Test
    @DisplayName("贡献增加个人贡献与公会经验")
    void contributeIncreasesPersonalAndGuildExp() {
        assertTrue(guildService.create(LEADER, "贡献社", "").success());
        assertTrue(guildService.contribute(LEADER, 150).success());

        GuildService.GuildInfo g = guildService.loadGuildForPlayer(LEADER);
        assertEquals(150, g.exp());
        GuildService.MemberInfo me = guildService.loadMember(g.guildId(), LEADER);
        assertEquals(150, me.contribution());
        assertEquals(150, me.weeklyContrib());
    }

    @Test
    @DisplayName("非法贡献数量或未入会")
    void contributeRejectsInvalidAmountOrNoGuild() {
        assertEquals(2, guildService.contribute(LEADER, 0).retcode());
        assertEquals(2, guildService.contribute(LEADER, 10_001).retcode());
        assertEquals(3, guildService.contribute(LEADER, 10).retcode());
    }

    @Test
    @DisplayName("商店扣贡献并遵守周限购")
    void shopBuyConsumesContributionAndRespectsWeeklyLimit() {
        assertTrue(guildService.create(LEADER, "商店社", "").success());
        assertTrue(guildService.contribute(LEADER, 500).success());

        assertEquals(2, guildService.listShopProducts().size());
        assertTrue(guildService.buyShop(LEADER, 1).success());
        assertEquals(1, guildService.boughtThisWeek(LEADER, 1));
        assertEquals(400, guildService.loadMember(
                guildService.findGuildIdByPlayer(LEADER), LEADER).contribution());

        assertTrue(guildService.buyShop(LEADER, 1).success());
        assertEquals(2, guildService.boughtThisWeek(LEADER, 1));
        assertEquals(6, guildService.buyShop(LEADER, 1).retcode());

        assertEquals(5, guildService.buyShop(LEADER, 2).retcode());
    }

    @Test
    @DisplayName("未知商品与未入会购买被拒绝")
    void shopRejectsUnknownProductAndNonMember() {
        assertEquals(3, guildService.buyShop(LEADER, 99).retcode());
        assertEquals(4, guildService.buyShop(LEADER, 1).retcode());
    }

    @Test
    @DisplayName("端到端：建会→招人→贡献→购物→退会")
    void fullLifecycle() {
        assertTrue(guildService.create(LEADER, "旅程公会", "启程").success());
        long guildId = guildService.loadGuildForPlayer(LEADER).guildId();

        assertTrue(guildService.join(MEMBER, guildId).success());
        assertTrue(guildService.contribute(MEMBER, 120).success());
        assertTrue(guildService.buyShop(MEMBER, 1).success());
        assertEquals(20, guildService.loadMember(guildId, MEMBER).contribution());

        assertTrue(guildService.leave(MEMBER).success());
        assertEquals(1, guildService.loadGuild(guildId).memberCount());

        List<GuildService.MemberInfo> left = guildService.listMembers(guildId);
        assertEquals(1, left.size());
        assertEquals(LEADER, left.get(0).playerId());
    }
}
