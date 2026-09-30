package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.arena.ArenaRatingService;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.ConfigGrayReader;
import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.guild.GuildWarService;
import cn.itcast.demo.mylunarcore.hall.ChatService;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.tx.DistributedLockService;
import cn.itcast.demo.mylunarcore.tx.LocalDistributedLockService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口补齐功能：公会战 / 家园产出互访 / 剧情跳过 / 竞技场 / Party 权威 / 离线私聊 / 灰度读 / 集群锁。
 */
@DisplayName("缺口补齐业务流程总测")
class GapFillBusinessFlowTest {

    @Nested
    @DisplayName("公会战匹配→报分→排行")
    class GuildWarFlow {

        @Test
        @DisplayName("双公会匹配后报分应 SETTLED 且榜上有名")
        void matchReportRank() {
            JdbcTemplate jdbc = failingJdbc();
            GuildService guild = mock(GuildService.class);
            when(guild.findGuildIdByPlayer(11)).thenReturn(110L);
            when(guild.findGuildIdByPlayer(22)).thenReturn(220L);
            GuildWarService war = new GuildWarService(jdbc, guild);

            assertTrue(war.requestMatch(11).success());
            GuildWarService.OpResult matched = war.requestMatch(22);
            assertTrue(matched.success());
            assertEquals("MATCHED", matched.match().status());

            GuildWarService.OpResult settled = war.reportBattleResult(11, matched.match().matchId(), 20, 5);
            assertTrue(settled.success());
            assertEquals("SETTLED", settled.match().status());
            assertEquals(110L, settled.match().winnerGuildId());

            List<GuildWarService.RankEntry> rank = war.seasonRank(settled.match().seasonId(), 10);
            assertFalse(rank.isEmpty());
            assertEquals(110L, rank.get(0).guildId());
        }

        @Test
        @DisplayName("未入会申请匹配应失败")
        void notInGuildShouldFail() {
            GuildService guild = mock(GuildService.class);
            when(guild.findGuildIdByPlayer(99)).thenReturn(null);
            GuildWarService war = new GuildWarService(failingJdbc(), guild);
            assertEquals(3, war.requestMatch(99).retcode());
        }
    }

    @Nested
    @DisplayName("家园产出 / 家具 / 互访")
    class HomeFlow {

        @Test
        @DisplayName("放置→家具→互访→领取产出（空产出时 retcode=5）")
        void placeFurnitureVisitClaim() {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(701, 1, 2).success());
            assertTrue(home.placeFurniture(701, 42, 3f, 0f, 4f, 45).success());
            assertEquals(1, home.getOrCreate(701).furniture().size());

            HomeBaseService.VisitResult visit = home.visit(702, 701);
            assertTrue(visit.success());
            assertEquals(701, visit.hostState().playerId());
            assertFalse(home.visit(701, 701).success());

            // 刚放置未满 1 分钟，无产出
            HomeBaseService.OpResult claim = home.claimProduction(701);
            assertFalse(claim.success());
            assertEquals(5, claim.retcode());
        }

        @Test
        @DisplayName("强制推进生产时钟后应有 pending 产出")
        void forcedProductionTickShouldAccumulate() throws Exception {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(710, 2, 1).success());
            HomeBaseService.HomeState cur = home.getOrCreate(710);
            // 把 lastProduceAtMs 拨回 2 小时前
            Field homes = HomeBaseService.class.getDeclaredField("homes");
            homes.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Integer, HomeBaseService.HomeState>) homes.get(home);
            map.put(710, new HomeBaseService.HomeState(
                    cur.playerId(), cur.stamina(), cur.staminaCap(), cur.facilities(),
                    cur.stationedAvatars(), cur.pendingProduce(), cur.furniture(),
                    cur.lastRecoverAtMs(), System.currentTimeMillis() - 7_200_000L));
            HomeBaseService.HomeState after = home.tickProduction(home.getOrCreate(710));
            assertFalse(after.pendingProduce().isEmpty());
            assertTrue(home.claimProduction(710).success());
            assertTrue(home.getOrCreate(710).pendingProduce().isEmpty());
        }
    }

    @Nested
    @DisplayName("剧情分支存档与过场跳过")
    class StoryFlow {

        @Test
        @DisplayName("选项写入 choiceHistory，skip/complete 更新过场进度")
        void choiceHistoryAndCutsceneSkip() {
            ObjectMapper mapper = new ObjectMapper();
            DialogueTreeRepository trees = new DialogueTreeRepository(mapper, "data");
            assertTrue(trees.reload());
            DialogueProgressService progress = new DialogueProgressService();
            CutsceneTriggerService cutscenes = new CutsceneTriggerService(mapper, "data");
            assertTrue(cutscenes.reload());
            DialogueTriggerEngine engine = new DialogueTriggerEngine(trees, progress, cutscenes);

            assertTrue(engine.startByNpc(801, "1001").ok());
            assertTrue(engine.choose(801, "1001", "1").ok());
            assertFalse(progress.choiceHistory(801, "1001").isEmpty());
            assertEquals("1", progress.choiceHistory(801, "1001").get(0).choiceId());

            assertTrue(engine.skip(801, "1001").ok());
            assertTrue(progress.hasPlayed(801, "1001"));

            assertTrue(cutscenes.trigger(801, "cs_intro_01").ok());
            assertTrue(cutscenes.skip(801, "cs_intro_01").ok());
            assertTrue(cutscenes.hasCompleted(801, "cs_intro_01"));
            assertTrue(cutscenes.complete(802, "cs_intro_01").ok());
            assertTrue(cutscenes.hasCompleted(802, "cs_intro_01"));
        }
    }

    @Nested
    @DisplayName("竞技场 ELO + 匹配 mode=10")
    class ArenaFlow {

        @Test
        @DisplayName("结算后胜者分上升，mode=10 可入队")
        void eloAndArenaQueue() {
            ArenaRatingService arena = new ArenaRatingService(failingJdbc());
            int before = arena.getOrCreate(1).rating();
            ArenaRatingService.MatchResult r = arena.settleMatch(1, 2);
            assertTrue(r.winnerRatingAfter() > before);
            assertTrue(r.loserRatingAfter() < before);

            LunarCoreProperties props = new LunarCoreProperties();
            MatchmakingService mm = new MatchmakingService(
                    new RoomService(), mock(GameSessionManager.class), props,
                    new BusinessMetrics(new SimpleMeterRegistry()),
                    providerOf(arena));
            MatchmakingService.JoinResult join = mm.joinQueue(1, ArenaRatingService.MATCH_MODE_ARENA, 40, 0);
            assertTrue(join.success());
            assertTrue(mm.cancelQueue(1, ArenaRatingService.MATCH_MODE_ARENA));
        }
    }

    @Nested
    @DisplayName("Party 跨节点权威")
    class PartyAuthorityFlow {

        @Test
        @DisplayName("非 owner 节点变更应返回 NOT_OWNER_NODE")
        void foreignNodeMutationRejected() {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);

            LunarCoreProperties nodeA = new LunarCoreProperties();
            nodeA.getCenter().setLocalNodeId("node-a");
            PartyService partyA = new PartyService(store, nodeA);
            assertEquals(PartyService.PartyResultCode.OK, partyA.create(1L).code());
            PartyService.Party party = partyA.getByUid(1L);
            assertEquals("node-a", party.ownerNodeId());

            // 模拟 node-b 仅从 Redis 看到队伍（本地无权威）
            when(store.available()).thenReturn(true);
            when(store.getByUid(1L)).thenReturn(party);
            when(store.getByUid(2L)).thenReturn(null);

            LunarCoreProperties nodeB = new LunarCoreProperties();
            nodeB.getCenter().setLocalNodeId("node-b");
            PartyService partyB = new PartyService(store, nodeB);
            assertEquals(PartyService.PartyResultCode.NOT_OWNER_NODE, partyB.invite(1L, 2L).code());
            assertEquals(PartyService.PartyResultCode.NOT_OWNER_NODE, partyB.leave(1L).code());
            assertEquals(PartyService.PartyResultCode.NOT_OWNER_NODE, partyB.disband(1L).code());
        }
    }

    @Nested
    @DisplayName("离线私聊 / 灰度读 / 集群锁 / Cmd 号段")
    class InfraFlow {

        @Test
        @DisplayName("目标离线时 send 落库，flushOffline 推送并标记已达")
        void offlineChatFlush() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
            when(jdbc.queryForList(anyString(), org.mockito.ArgumentMatchers.<Object>any()))
                    .thenReturn(List.of(
                    java.util.Map.of(
                            "id", 1L,
                            "channel_type", 1,
                            "sender_id", 9,
                            "sender_name", "a",
                            "target_id", 8,
                            "content", "hi",
                            "send_time_ms", 123L)
            ));

            @SuppressWarnings("unchecked")
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.listener.RedisMessageListenerContainer> listeners =
                    mock(ObjectProvider.class);
            when(listeners.getIfAvailable()).thenReturn(null);

            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.hall.ChatDomainEvents> chatEvents =
                    mock(ObjectProvider.class);
            when(chatEvents.getIfAvailable()).thenReturn(null);
            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.hall.ChatSensitiveWordFilter> words =
                    mock(ObjectProvider.class);
            when(words.getIfAvailable()).thenReturn(null);
            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.hall.ChatModerationService> moderation =
                    mock(ObjectProvider.class);
            when(moderation.getIfAvailable()).thenReturn(null);
            ChatService chat = new ChatService(
                    mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class),
                    new LunarCoreProperties(), redis, listeners, jdbc, chatEvents, words, moderation,
                    mock(ObjectProvider.class));
            assertEquals(0, chat.send(9, "a", 1, 8, "hi"));
            assertEquals(1, chat.flushOffline(8));
        }

        @Test
        @DisplayName("ConfigGrayReader 按尾号分流 canary/stable")
        void grayReaderSelects() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getConfigGray().setEnabled(true);
            props.getConfigGray().setUidTails("1");
            ConfigGrayReader reader = new ConfigGrayReader(new ConfigGrayRelease(props));
            assertEquals("new", reader.select(11L, "s", "new", "old"));
            assertEquals("old", reader.select(12L, "s", "new", "old"));
        }

        @Test
        @DisplayName("ClusterJobLock 同 key 仅执行一次")
        void clusterJobRunsOnce() {
            DistributedLockService lock = new LocalDistributedLockService();
            @SuppressWarnings("unchecked")
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            ClusterJobLock jobs = new ClusterJobLock(redis, new LunarCoreProperties(), lock);
            AtomicInteger n = new AtomicInteger();
            assertTrue(jobs.tryRun("ut-job", Duration.ofSeconds(5), () -> {
                n.incrementAndGet();
            }));
            assertEquals(1, n.get());
            assertTrue(Boolean.TRUE.equals(jobs.tryRun("ut-job-2", Duration.ofSeconds(5), () -> true)));
        }

        @Test
        @DisplayName("新增 CmdId 号段唯一且落在约定区间")
        void newCmdIdBandsUnique() throws Exception {
            Set<Integer> all = new HashSet<>();
            for (Field f : CmdIds.class.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || f.getType() != int.class) {
                    continue;
                }
                if (f.getName().equals("PROTOCOL_WIRE_VERSION")) {
                    continue;
                }
                int v = f.getInt(null);
                assertTrue(all.add(v), "duplicate cmd " + f.getName() + "=" + v);
            }
            assertTrue(all.contains(CmdIds.GUILD_WAR_MATCH_CS_REQ));
            assertTrue(all.contains(CmdIds.GET_HOME_INFO_CS_REQ));
            assertTrue(all.contains(CmdIds.DIALOGUE_CHOOSE_CS_REQ));
            assertTrue(all.contains(CmdIds.GET_ARENA_INFO_CS_REQ));
            assertTrue(CmdIds.GUILD_WAR_RANK_SC_RSP <= 989);
            assertTrue(CmdIds.HOME_VISIT_SC_RSP >= 850 && CmdIds.HOME_VISIT_SC_RSP <= 857);
            assertTrue(CmdIds.CUTSCENE_SC_NOTIFY >= 860 && CmdIds.CUTSCENE_SC_NOTIFY <= 868);
        }
    }

    private static JdbcTemplate failingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new RuntimeException("no db"));
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenThrow(new RuntimeException("no db"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new RuntimeException("no db"));
        return jdbc;
    }

    private static <T> ObjectProvider<T> providerOf(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return bean;
            }

            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getIfUnique() {
                return bean;
            }
        };
    }
}
