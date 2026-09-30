package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.VersionActivityService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassService;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.guild.GuildTechService;
import cn.itcast.demo.mylunarcore.hall.SupportService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.net.ClientFeatureFlags;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerLoadingStateService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.quest.DailyMissionService;
import cn.itcast.demo.mylunarcore.quest.QuestTriggerEngine;
import cn.itcast.demo.mylunarcore.repo.FriendRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import cn.itcast.demo.mylunarcore.repo.QuestProgressRepository;
import cn.itcast.demo.mylunarcore.scene.SceneEntityIdAllocator;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.story.StoryChapterService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 核心循环全流程：体力 / 日常 / 战令 / 主线章节 / 助战 / 公会科技 /
 * 切图加载态 / 版本活动 / SupportedFeatures / Zone 自适应容量。
 */
@DisplayName("P5 核心循环与新业务流程总测")
class CoreLoopBusinessFlowsTest {

    private static Path tempDir;
    private static JdbcTemplate jdbc;

    @BeforeEach
    void setUpDb() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:coreloop_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE player (
                    uid INT PRIMARY KEY,
                    stamina INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_stamina_meta (
                    player_id INT PRIMARY KEY,
                    last_regen_at_ms BIGINT NOT NULL,
                    daily_buy_count INT NOT NULL DEFAULT 0,
                    buy_day VARCHAR(16) NOT NULL DEFAULT '',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE daily_mission_progress (
                    player_id INT NOT NULL,
                    mission_day VARCHAR(16) NOT NULL,
                    mission_id INT NOT NULL,
                    progress INT NOT NULL DEFAULT 0,
                    claimed TINYINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, mission_day, mission_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE battle_pass_progress (
                    player_id INT NOT NULL,
                    season_id INT NOT NULL,
                    xp INT NOT NULL DEFAULT 0,
                    level INT NOT NULL DEFAULT 0,
                    premium TINYINT NOT NULL DEFAULT 0,
                    claimed_free_json VARCHAR(512) NOT NULL,
                    claimed_premium_json VARCHAR(512) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, season_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE story_chapter_progress (
                    player_id INT PRIMARY KEY,
                    current_chapter_id INT NOT NULL DEFAULT 1,
                    completed_chapters_json VARCHAR(1024) NOT NULL,
                    unlocked_planes_json VARCHAR(1024) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE support_unit (
                    player_id INT PRIMARY KEY,
                    avatar_instance_id BIGINT NOT NULL,
                    avatar_id INT NOT NULL,
                    level INT NOT NULL,
                    promotion INT NOT NULL DEFAULT 0,
                    rank_val INT NOT NULL DEFAULT 0,
                    snapshot_json VARCHAR(1024) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE version_activity_progress (
                    player_id INT NOT NULL,
                    version_activity_id INT NOT NULL,
                    token_balance INT NOT NULL DEFAULT 0,
                    completed_nodes_json VARCHAR(1024) NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, version_activity_id)
                )
                """);

        tempDir = Files.createTempDirectory("core-loop-");
        Files.writeString(tempDir.resolve("StaminaConfigs.json"), """
                {
                  "maxStamina": 100,
                  "regenPerMinute": 10,
                  "regenIntervalMs": 1000,
                  "dungeonCost": 40,
                  "materialFarmCost": 20,
                  "dailyBuyLimit": 2,
                  "buyCostCurrencyId": 101,
                  "buyCostAmounts": [10, 20],
                  "buyGrantAmount": 30
                }
                """);
        Files.writeString(tempDir.resolve("DailyMissionConfigs.json"), """
                {
                  "missions": [
                    {
                      "missionId": 1,
                      "title": "每日战斗",
                      "description": "完成1场战斗",
                      "triggerType": 1,
                      "targetId": 0,
                      "required": 1,
                      "rewards": [{"currencyId": 1, "amount": 50}],
                      "battlePassXp": 500
                    }
                  ]
                }
                """);
        Files.writeString(tempDir.resolve("BattlePassConfigs.json"), """
                {
                  "seasonId": 1,
                  "seasonKey": "test",
                  "maxLevel": 5,
                  "xpPerLevel": 100,
                  "freeRewards": [
                    {"level": 1, "currencyId": 1, "amount": 10},
                    {"level": 2, "currencyId": 1, "amount": 20}
                  ],
                  "premiumRewards": [
                    {"level": 1, "currencyId": 101, "amount": 5}
                  ]
                }
                """);
        Files.writeString(tempDir.resolve("ChapterConfigs.json"), """
                {
                  "chapters": [
                    {
                      "chapterId": 1,
                      "title": "序章",
                      "mainlineQuestIds": [10001],
                      "unlockPlaneId": 10000,
                      "unlockFloorId": 1,
                      "requiredChallengeId": 0,
                      "requiredBattleStageId": 1001,
                      "nextChapterId": 2,
                      "priority": 100
                    },
                    {
                      "chapterId": 2,
                      "title": "第一章",
                      "mainlineQuestIds": [10002],
                      "unlockPlaneId": 10001,
                      "unlockFloorId": 1,
                      "requiredChallengeId": 2001,
                      "requiredBattleStageId": 0,
                      "nextChapterId": 0,
                      "priority": 90
                    }
                  ],
                  "defaultUnlockedPlanes": [{"planeId": 10000, "floorId": 1}]
                }
                """);
        Files.writeString(tempDir.resolve("GuildTechConfigs.json"), """
                {
                  "levels": [
                    {"guildLevel": 1, "atkBonusPct": 0.0, "defBonusPct": 0.0, "hpBonusPct": 0.0, "staminaRegenBonusPct": 0.0},
                    {"guildLevel": 3, "atkBonusPct": 0.02, "defBonusPct": 0.01, "hpBonusPct": 0.02, "staminaRegenBonusPct": 0.05}
                  ]
                }
                """);
        Files.writeString(tempDir.resolve("VersionActivityConfigs.json"), """
                {
                  "activities": [
                    {
                      "versionActivityId": 5001,
                      "title": "测试庆典",
                      "tokenItemId": 9001,
                      "openAt": "2020-01-01T00:00:00Z",
                      "closeAt": "2099-12-31T23:59:59Z",
                      "nodes": [
                        {"nodeId": "story", "type": "story_stage", "title": "剧情", "children": ["challenge", "signin"]},
                        {"nodeId": "challenge", "type": "challenge_stage", "title": "挑战", "children": ["shop"], "tokenReward": 100},
                        {"nodeId": "signin", "type": "signin", "title": "签到", "children": [], "tokenReward": 20},
                        {"nodeId": "shop", "type": "exchange_shop", "title": "兑换", "children": [], "tokenCost": 50}
                      ],
                      "rootNodeIds": ["story"]
                    }
                  ]
                }
                """);
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

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static PeriodicResetService mockReset() {
        PeriodicResetService reset = mock(PeriodicResetService.class);
        return reset;
    }

    @Nested
    @DisplayName("体力：恢复→消耗→不足→购买")
    class StaminaFlow {

        @Test
        @DisplayName("打本消耗与不足拦截，购买补充")
        void consumeAndBuy() {
            jdbc.update("INSERT INTO player(uid, stamina) VALUES(?, ?)", 1001, 50);

            PlayerDataRepository players = mock(PlayerDataRepository.class);
            when(players.loadPlayerByUid(1001L)).thenAnswer(inv -> {
                PlayerEntity p = new PlayerEntity();
                p.setUid(1001L);
                Integer st = jdbc.queryForObject("SELECT stamina FROM player WHERE uid=?", Integer.class, 1001);
                p.setStamina(st == null ? 0 : st);
                return p;
            });

            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.deduct(eq(1001), eq(101), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, java.util.Map.of(101, 90), "stamina_buy"));

            StaminaService stamina = new StaminaService(
                    jdbc, players, wallet, new ObjectMapper(), mockReset(),
                    provider(null), tempDir.toString());
            stamina.loadConfig();

            StaminaService.ConsumeResult dungeon = stamina.tryConsumeDungeon(1001);
            assertTrue(dungeon.ok());
            assertEquals(10, dungeon.remaining());

            StaminaService.ConsumeResult fail = stamina.tryConsumeDungeon(1001);
            assertFalse(fail.ok());
            assertEquals(2, fail.retcode());

            StaminaService.ConsumeResult bought = stamina.buyStamina(1001);
            assertTrue(bought.ok());
            assertEquals(40, bought.remaining()); // 10 + 30

            StaminaService.StaminaSnapshot snap = stamina.snapshot(1001);
            assertEquals(40, snap.current());
            assertEquals(100, snap.max());
            assertEquals(1, snap.dailyBuyCount());
            assertEquals(2, snap.dailyBuyLimit());
        }

        @Test
        @DisplayName("Tick 自然恢复受间隔约束")
        void tickRegen() {
            jdbc.update("INSERT INTO player(uid, stamina) VALUES(?, ?)", 2002, 0);
            PlayerDataRepository players = mock(PlayerDataRepository.class);
            when(players.loadPlayerByUid(2002L)).thenAnswer(inv -> {
                PlayerEntity p = new PlayerEntity();
                p.setUid(2002L);
                Integer st = jdbc.queryForObject("SELECT stamina FROM player WHERE uid=?", Integer.class, 2002);
                p.setStamina(st == null ? 0 : st);
                return p;
            });

            StaminaService stamina = new StaminaService(
                    jdbc, players, mock(WalletApplicationService.class), new ObjectMapper(), mockReset(),
                    provider(null), tempDir.toString());
            stamina.loadConfig();

            long t0 = System.currentTimeMillis();
            // 写入旧的 last_regen，确保跨过 1000ms interval
            jdbc.update("""
                    INSERT INTO player_stamina_meta(player_id, last_regen_at_ms, daily_buy_count, buy_day)
                    VALUES(?, ?, 0, '')
                    """, 2002, t0 - 3_500L);

            stamina.onTick(2002, t0, 100L);
            Integer after = jdbc.queryForObject("SELECT stamina FROM player WHERE uid=?", Integer.class, 2002);
            assertNotNull(after);
            assertTrue(after >= 30, "期望至少恢复 3 tick * 10 = 30, actual=" + after);
        }
    }

    @Nested
    @DisplayName("日常任务→战令 XP→领奖")
    class DailyMissionAndBattlePassFlow {

        @Test
        @DisplayName("触发完成→领奖→战令升级→免费轨领取；重复领奖失败")
        void missionClaimBoostsBattlePass() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, java.util.Map.of(), "ok"));
            ItemRepository items = mock(ItemRepository.class);

            BattlePassService bp = new BattlePassService(
                    jdbc, new ObjectMapper(), wallet, items, mockReset(), tempDir.toString());
            bp.loadConfig();

            DailyMissionService daily = new DailyMissionService(
                    jdbc, new ObjectMapper(), wallet, items, provider(bp), mockReset(), tempDir.toString());
            daily.loadConfig();

            daily.onTrigger(3001, 1, 0);
            List<DailyMissionService.MissionView> list = daily.listToday(3001);
            assertEquals(1, list.size());
            assertTrue(list.get(0).completed());
            assertFalse(list.get(0).claimed());

            DailyMissionService.ClaimResult claim = daily.claim(3001, 1);
            assertTrue(claim.ok());
            assertEquals(4, daily.claim(3001, 1).retcode()); // 重复领取

            BattlePassService.ProgressView prog = bp.getProgress(3001);
            assertTrue(prog.xp() >= 500);
            assertTrue(prog.level() >= 5); // 500/100 = 5

            assertTrue(bp.claimLevel(3001, 1, false).ok());
            assertEquals(5, bp.claimLevel(3001, 1, false).retcode()); // 已领
            assertEquals(4, bp.claimLevel(3001, 1, true).retcode()); // 未开通付费轨

            assertTrue(bp.unlockPremium(3001).ok());
            assertTrue(bp.claimLevel(3001, 1, true).ok());
        }
    }

    @Nested
    @DisplayName("主线章节解锁地图")
    class StoryChapterFlow {

        @Test
        @DisplayName("默认可进序章图；通关后解锁下一章图；支线压制")
        void unlockByBattleClear() {
            StoryChapterService story = new StoryChapterService(jdbc, new ObjectMapper(), tempDir.toString());
            story.loadConfig();

            assertTrue(story.canEnterPlane(4001, 10000, 1));
            assertFalse(story.canEnterPlane(4001, 10001, 1));

            assertEquals(List.of(10001), story.activeMainlineQuestIds(4001));
            assertEquals(100, story.currentMainlinePriority(4001));
            assertTrue(story.isMainlineQuest(10001));
            assertFalse(story.isMainlineQuest(99999));

            story.onBattleCleared(4001, 1001);
            assertTrue(story.canEnterPlane(4001, 10001, 1));
            assertTrue(story.list(4001).stream().anyMatch(c -> c.chapterId() == 2 && c.unlocked()));

            // 挑战推进第二章（已在 chapter 2）
            story.onChallengeCleared(4001, 2001);
            assertTrue(story.canEnterPlane(4001, 10001, 1));
            assertEquals(List.of(10002), story.activeMainlineQuestIds(4001));
        }

        @Test
        @DisplayName("QuestTriggerEngine 主线优先接取 + 日常推进")
        void questEngineMainlineAndDaily() {
            StoryChapterService story = new StoryChapterService(jdbc, new ObjectMapper(), tempDir.toString());
            story.loadConfig();

            DailyMissionService daily = new DailyMissionService(
                    jdbc, new ObjectMapper(), mock(WalletApplicationService.class),
                    mock(ItemRepository.class), provider(null), mockReset(), tempDir.toString());
            daily.loadConfig();

            QuestProgressRepository questRepo = mock(QuestProgressRepository.class);
            QuestTriggerEngine engine = new QuestTriggerEngine(questRepo, provider(daily), provider(story));

            engine.onTrigger(4002, 1, 0, 0, 0);
            verify(questRepo).ensureAccepted(4002, 10001);
            verify(questRepo).applyTrigger(4002, 1, 0L, 0L, 0L);

            assertTrue(engine.shouldSuppressSideQuest(4002, 88888));
            assertFalse(engine.shouldSuppressSideQuest(4002, 10001));

            assertTrue(daily.listToday(4002).get(0).completed());
        }
    }

    @Nested
    @DisplayName("好友助战加入战斗参与者")
    class SupportBattleFlow {

        @Test
        @DisplayName("设置外借→好友可借→非好友不可借→加入 participantPlayerIds")
        void setBorrowAndEnroll() {
            FriendRepository friends = mock(FriendRepository.class);
            FriendEntity rel = new FriendEntity();
            rel.setStatus(1);
            rel.setPlayerId1(5001);
            rel.setPlayerId2(5002);
            when(friends.findRelation(5001, 5002)).thenReturn(rel);
            when(friends.findRelation(5001, 5999)).thenReturn(null);

            AvatarEntity avatar = new AvatarEntity();
            avatar.setId(77L);
            avatar.setPlayerId(5002);
            avatar.setAvatarId(1003);
            avatar.setLevel(60);
            avatar.setPromotion(2);
            avatar.setRank(1);
            avatar.setEquippedSkinId(0);

            PlayerDataRepository players = mock(PlayerDataRepository.class);
            when(players.loadAvatars(5002)).thenReturn(List.of(avatar));

            SupportService support = new SupportService(jdbc, friends, players, new ObjectMapper());
            assertTrue(support.setSupportUnit(5002, 77L).ok());
            assertNotNull(support.getOwnSupport(5002));

            SupportService.SupportSnapshot snap = support.borrowFriendSupport(5001, 5002);
            assertNotNull(snap);
            assertEquals(5002, snap.ownerPlayerId());
            assertEquals(1003, snap.avatarId());

            assertNull(support.borrowFriendSupport(5001, 5999));

            BattleContext ctx = BattleContext.createNew(1L, 5001, 1, 100, 1L, List.of());
            ctx.addParticipant(snap.ownerPlayerId());
            assertTrue(ctx.getParticipantPlayerIds().contains(5002));
            assertTrue(ctx.getParticipantPlayerIds().contains(5001));
        }
    }

    @Nested
    @DisplayName("公会科技 Buff")
    class GuildTechFlow {

        @Test
        @DisplayName("等级 3 应提供攻防血与体力恢复加成")
        void buffByGuildLevel() {
            GuildService guild = mock(GuildService.class);
            when(guild.loadGuildForPlayer(6001)).thenReturn(
                    new GuildService.GuildInfo(1L, "g", "", 3, 0, 6001, 1, 30));
            when(guild.loadGuildForPlayer(6002)).thenReturn(null);

            GuildTechService tech = new GuildTechService(guild, new ObjectMapper(), tempDir.toString());
            tech.loadConfig();

            GuildTechService.GuildBuff buff = tech.buffForPlayer(6001);
            assertEquals(0.02, buff.atkBonusPct(), 1e-9);
            assertEquals(0.01, buff.defBonusPct(), 1e-9);
            assertEquals(0.05, buff.staminaRegenBonusPct(), 1e-9);

            assertEquals(GuildTechService.GuildBuff.NONE, tech.buffForPlayer(6002));
        }
    }

    @Nested
    @DisplayName("切图 LoadingTicket 原子性")
    class LoadingTicketFlow {

        @Test
        @DisplayName("切图中拒绝业务；LoadComplete 后恢复")
        void loadingBlocksThenCompletes() {
            PlayerLoadingStateService loading = new PlayerLoadingStateService(new MigrationTicketService());
            var ticket = loading.beginLoading(7001L, 10001, 1, 0, 1f, 2f, 3f);
            assertTrue(loading.isLoading(7001L));
            assertFalse(loading.completeLoading(7001L, "wrong"));
            assertTrue(loading.isLoading(7001L));
            assertTrue(loading.completeLoading(7001L, ticket.ticket()));
            assertFalse(loading.isLoading(7001L));
        }
    }

    @Nested
    @DisplayName("版本活动树：剧情→挑战→兑换")
    class VersionActivityFlow {

        @Test
        @DisplayName("根节点→挑战发币→商店扣币；未解锁节点失败")
        void storyChallengeShop() {
            VersionActivityService vas = new VersionActivityService(jdbc, new ObjectMapper(), tempDir.toString());
            vas.loadConfig();

            assertEquals(6, vas.completeNode(8001, 5001, "challenge").retcode()); // 未完成父节点

            VersionActivityService.OpResult story = vas.completeNode(8001, 5001, "story");
            assertTrue(story.ok());

            VersionActivityService.OpResult challenge = vas.completeNode(8001, 5001, "challenge");
            assertTrue(challenge.ok());
            assertEquals(100, challenge.tokenBalance());

            VersionActivityService.OpResult signin = vas.completeNode(8001, 5001, "signin");
            assertTrue(signin.ok());
            assertEquals(120, signin.tokenBalance());

            assertEquals(7, vas.completeNode(8001, 5001, "shop").retcode()); // shop 走 exchange

            VersionActivityService.OpResult buy = vas.exchange(8001, 5001, "shop");
            assertTrue(buy.ok());
            assertEquals(70, buy.tokenBalance());

            List<VersionActivityService.ActivityView> open = vas.listOpen(8001);
            assertEquals(1, open.size());
            assertEquals(70, open.get(0).tokenBalance());
            assertTrue(open.get(0).completedNodes().contains("story"));
        }
    }

    @Nested
    @DisplayName("SupportedFeatures 隐藏公会战入口")
    class FeatureMaskFlow {

        @Test
        @DisplayName("无 GUILD_WAR 位应返回 retcode=20")
        void hideGuildWarWithoutFeature() {
            GameSessionManager sessions = mock(GameSessionManager.class);
            GameSession session = new GameSession(9001L, new EmbeddedChannel(), null);
            session.setEnabledFeatures(ClientFeatureFlags.GUILD | ClientFeatureFlags.HOME);
            when(sessions.getOrNull(9001L)).thenReturn(session);

            cn.itcast.demo.mylunarcore.player.PlayerContextResolver resolver =
                    mock(cn.itcast.demo.mylunarcore.player.PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(9001);

            cn.itcast.demo.mylunarcore.guild.GuildNettyService netty =
                    new cn.itcast.demo.mylunarcore.guild.GuildNettyService(
                            mock(GuildService.class),
                            mock(cn.itcast.demo.mylunarcore.guild.GuildWarService.class),
                            mock(GuildTechService.class),
                            resolver,
                            sessions);

            assertEquals(20, netty.handleWarMatch(new EmbeddedChannel()).getRetcode());
            assertEquals(20, netty.handleWarRank(
                    cn.itcast.demo.mylunarcore.protocol.GuildSystemProto.GuildWarRankCsReq.newBuilder().build(),
                    new EmbeddedChannel()).getRetcode());

            // 声明公会战后放行到业务（未入会等由 WarService 决定）
            session.setEnabledFeatures(ClientFeatureFlags.SERVER_ALL);
            cn.itcast.demo.mylunarcore.guild.GuildWarService war =
                    mock(cn.itcast.demo.mylunarcore.guild.GuildWarService.class);
            when(war.requestMatch(9001)).thenReturn(
                    new cn.itcast.demo.mylunarcore.guild.GuildWarService.OpResult(false, 3, null));
            cn.itcast.demo.mylunarcore.guild.GuildNettyService netty2 =
                    new cn.itcast.demo.mylunarcore.guild.GuildNettyService(
                            mock(GuildService.class), war, mock(GuildTechService.class), resolver, sessions);
            assertEquals(3, netty2.handleWarMatch(new EmbeddedChannel()).getRetcode());
        }
    }

    @Nested
    @DisplayName("Zone 自适应容量")
    class ZoneAdaptiveFlow {

        @Test
        @DisplayName("tick 超时应下调有效上限")
        void adaptiveCapDown() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getZone().setMaxPlayers(300);
            props.getZone().setAdaptiveCapacityEnabled(true);
            props.getZone().setAdaptiveTickBudgetMs(8L);

            ZoneManager zones = new ZoneManager(new SceneRegistry(), new SceneEntityIdAllocator(), props);
            assertEquals(300, zones.effectiveMaxPlayers());

            zones.reportTickDurationMs(16L); // 2x budget → 约 150
            assertEquals(150, zones.effectiveMaxPlayers());

            zones.reportTickDurationMs(100L); // 大幅超时 → max(50, floor(300*0.25))=75
            assertEquals(75, zones.effectiveMaxPlayers());
            zones.reportTickDurationMs(1000L);
            assertEquals(75, zones.effectiveMaxPlayers()); // 比例下限仍为配置的 25%
        }
    }

    @Nested
    @DisplayName("端到端节奏：体力+章节+日常联动")
    class EndToEndCadence {

        @Test
        @DisplayName("开战扣体力→胜局推进主线→日常进度完成")
        void staminaStoryDailyCadence() {
            jdbc.update("INSERT INTO player(uid, stamina) VALUES(?, ?)", 9100, 80);

            PlayerDataRepository players = mock(PlayerDataRepository.class);
            when(players.loadPlayerByUid(9100L)).thenAnswer(inv -> {
                PlayerEntity p = new PlayerEntity();
                p.setUid(9100L);
                Integer st = jdbc.queryForObject("SELECT stamina FROM player WHERE uid=?", Integer.class, 9100);
                p.setStamina(st == null ? 0 : st);
                return p;
            });

            StaminaService stamina = new StaminaService(
                    jdbc, players, mock(WalletApplicationService.class), new ObjectMapper(),
                    mockReset(), provider(null), tempDir.toString());
            stamina.loadConfig();
            assertTrue(stamina.tryConsumeDungeon(9100).ok());
            assertEquals(40, stamina.snapshot(9100).current());

            StoryChapterService story = new StoryChapterService(jdbc, new ObjectMapper(), tempDir.toString());
            story.loadConfig();
            assertFalse(story.canEnterPlane(9100, 10001, 1));
            story.onBattleCleared(9100, 1001);
            assertTrue(story.canEnterPlane(9100, 10001, 1));

            DailyMissionService daily = new DailyMissionService(
                    jdbc, new ObjectMapper(), mock(WalletApplicationService.class),
                    mock(ItemRepository.class), provider(null), mockReset(), tempDir.toString());
            daily.loadConfig();
            daily.onTrigger(9100, 1, 0);
            assertTrue(daily.listToday(9100).get(0).completed());
        }
    }
}
