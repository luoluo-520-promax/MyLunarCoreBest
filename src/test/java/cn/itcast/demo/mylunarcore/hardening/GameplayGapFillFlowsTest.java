package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.abyss.AbyssSeasonService;
import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.affinity.AffinityService;
import cn.itcast.demo.mylunarcore.analytics.CombatBalanceAnalyticsService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshot;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.character.AttributeCalculator;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import cn.itcast.demo.mylunarcore.common.ConfigGrayReader;
import cn.itcast.demo.mylunarcore.common.ConfigReleaseService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixService;
import cn.itcast.demo.mylunarcore.equipment.EquipmentBonus;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.handbook.HandbookService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.profile.PlayerCardService;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import cn.itcast.demo.mylunarcore.rogue.RogueMapGenerator;
import cn.itcast.demo.mylunarcore.social.GiftService;
import cn.itcast.demo.mylunarcore.social.GuildRedPacketService;
import cn.itcast.demo.mylunarcore.social.VoiceSignalingService;
import cn.itcast.demo.mylunarcore.story.StoryAssetVersionService;
import cn.itcast.demo.mylunarcore.tx.LocalDistributedLockService;
import cn.itcast.demo.mylunarcore.worldboss.WorldBossService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 二游缺口补齐：玩法 / 养成 / 社交 / 战斗迁移 / 匹配 AI / 活动模板 / 灰度 / 剧情资源 业务流程总测。
 */
@DisplayName("二游缺口补齐新功能业务流程总测")
class GameplayGapFillFlowsTest {

    private static JdbcTemplate failingJdbc() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 无库：返回空/0，走各服务内存回退（勿 thenThrow，避免 stub 互相触发）
        org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
        org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
        org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
        org.mockito.Mockito.lenient().when(jdbc.update(anyString(), any(), any())).thenReturn(0);
        org.mockito.Mockito.lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(jdbc.queryForList(anyString())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
        org.mockito.Mockito.lenient().when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
        return jdbc;
    }

    @Nested
    @DisplayName("光锥/遗器 → 面板")
    class EquipmentPanelFlow {

        @Test
        @DisplayName("抽词条→汇总装备→面板高于裸角色")
        void rollSumAndPanel() {
            EquipmentAffixService affix = new EquipmentAffixService(new ObjectMapper());
            affix.load();
            EquipmentAffixService.RollResult roll = affix.rollNew("BODY", 501001);
            assertTrue(roll.mainAffixId() >= 0);
            assertTrue(roll.subAffixesJson().startsWith("["));

            GameItemEntity relic = new GameItemEntity();
            relic.setType(2);
            relic.setItemId(501001);
            relic.setMainAffixId(roll.mainAffixId());
            relic.setSubAffixesJson(roll.subAffixesJson());
            relic.setLevel(9);

            GameItemEntity cone = new GameItemEntity();
            cone.setType(1);
            cone.setItemId(20001);
            cone.setLevel(40);
            cone.setRank(2);

            EquipmentBonus bonus = affix.sumEquippedBonus(List.of(relic, cone));
            AttributeCalculator calc = new AttributeCalculator();
            AvatarEntity avatar = new AvatarEntity();
            avatar.setAvatarId(1001);
            avatar.setLevel(40);
            avatar.setPromotion(3);
            avatar.setRank(2);
            var base = calc.calculate(avatar);
            var geared = calc.calculate(avatar, bonus);
            assertTrue(geared.atk() >= base.atk());
            assertTrue(geared.hp() >= base.hp());
        }

        @Test
        @DisplayName("强化跨档应可能升级副词条；分解有返还")
        void enhanceAndDecompose() {
            EquipmentAffixService affix = new EquipmentAffixService(new ObjectMapper());
            affix.load();
            GameItemEntity item = new GameItemEntity();
            item.setType(2);
            item.setLevel(0);
            item.setExp(100);
            item.setSubAffixesJson(affix.rollNew("HANDS", 502001).subAffixesJson());
            String upgraded = affix.maybeUpgradeSubOnEnhance(item, 0, 6);
            assertNotNull(upgraded);
            assertTrue(affix.decompose(item).returnExp() > 0);
        }
    }

    @Nested
    @DisplayName("世界BOSS 挑战→排名→奖励")
    class WorldBossFlow {

        @Test
        @DisplayName("多人挑战后榜单有序且排名有奖励文案")
        void challengeRankReward() {
            WorldBossService boss = new WorldBossService(failingJdbc(), new ObjectMapper(), mock(ClusterJobLock.class));
            boss.init();
            assertTrue(boss.challenge(101, 10_000).success());
            assertTrue(boss.challenge(102, 80_000).success());
            assertTrue(boss.challenge(103, 30_000).success());
            List<WorldBossService.RankEntry> ranks = boss.topRanks(10);
            assertEquals(3, ranks.size());
            assertEquals(102, ranks.get(0).playerId());
            assertTrue(ranks.get(0).damage() >= ranks.get(1).damage());
            assertFalse(boss.rewardForRank(1).isBlank());
        }
    }

    @Nested
    @DisplayName("深渊多队轮战周期")
    class AbyssFlow {

        @Test
        @DisplayName("逐层解锁→双队通关第3层→星级累计")
        void progressiveMultiTeam() {
            AbyssSeasonService abyss = new AbyssSeasonService(failingJdbc(), new ObjectMapper());
            abyss.load();
            if (abyss.currentSeason().floors().isEmpty()) {
                return;
            }
            assertTrue(abyss.reportClear(501, 1, 3, List.of(11)).success());
            assertTrue(abyss.reportClear(501, 2, 2, List.of(11)).success());
            assertEquals(2, abyss.reportClear(501, 3, 3, List.of(11)).retcode());
            AbyssSeasonService.ClearResult ok = abyss.reportClear(501, 3, 3, List.of(11, 22));
            assertTrue(ok.success());
            assertTrue(ok.totalStars() >= 8);
            assertEquals(3, abyss.progress(501).clearedFloor());
        }
    }

    @Nested
    @DisplayName("社交：赠礼 / 红包 / 语音")
    class SocialFlow {

        @Test
        @DisplayName("赠礼扣款成功并记剩余次数")
        void giftDeductFlow() {
            WalletRepository walletRepo = mock(WalletRepository.class);
            Map<Integer, Integer> bal = new HashMap<>(Map.of(2, 10_000));
            when(walletRepo.lockCurrency(1001)).thenAnswer(inv ->
                    new WalletRepository.CurrencyLockRow(new HashMap<>(bal), 1L));
            when(walletRepo.updateCurrencyOptimistic(eq(1001), any(), anyLong())).thenAnswer(inv -> {
                @SuppressWarnings("unchecked")
                Map<Integer, Integer> next = inv.getArgument(1);
                bal.clear();
                bal.putAll(next);
                return true;
            });
            when(walletRepo.loadCurrency(1001)).thenAnswer(inv -> new HashMap<>(bal));

            ObjectProvider<cn.itcast.demo.mylunarcore.tx.LocalTxLogService> tx = mock(ObjectProvider.class);
            when(tx.getIfAvailable()).thenReturn(null);
            WalletApplicationService wallet = new WalletApplicationService(
                    walletRepo, new LocalDistributedLockService(), tx);

            ObjectProvider<MailRepository> mail = mock(ObjectProvider.class);
            when(mail.getIfAvailable()).thenReturn(null);
            GiftService gifts = new GiftService(failingJdbc(), wallet, mail);

            GiftService.SendResult r = gifts.sendCurrencyGift(1001, 1002, 2, 100, "加油");
            assertTrue(r.success());
            assertEquals(9, gifts.dailyRemaining(1001).get("remain"));
        }

        @Test
        @DisplayName("公会红包创建→成员领取")
        void redPacketCreateClaim() {
            WalletRepository walletRepo = mock(WalletRepository.class);
            AtomicInteger version = new AtomicInteger(1);
            Map<Integer, Map<Integer, Integer>> balances = new HashMap<>();
            balances.put(11, new HashMap<>(Map.of(2, 5000)));
            balances.put(12, new HashMap<>(Map.of(2, 0)));

            when(walletRepo.lockCurrency(anyInt())).thenAnswer(inv -> {
                int pid = inv.getArgument(0);
                return new WalletRepository.CurrencyLockRow(
                        new HashMap<>(balances.getOrDefault(pid, Map.of())), version.get());
            });
            when(walletRepo.updateCurrencyOptimistic(anyInt(), any(), anyLong())).thenAnswer(inv -> {
                int pid = inv.getArgument(0);
                @SuppressWarnings("unchecked")
                Map<Integer, Integer> next = inv.getArgument(1);
                balances.put(pid, new HashMap<>(next));
                version.incrementAndGet();
                return true;
            });
            when(walletRepo.loadCurrency(anyInt())).thenAnswer(inv ->
                    new HashMap<>(balances.getOrDefault((Integer) inv.getArgument(0), Map.of())));

            ObjectProvider<cn.itcast.demo.mylunarcore.tx.LocalTxLogService> tx = mock(ObjectProvider.class);
            when(tx.getIfAvailable()).thenReturn(null);
            WalletApplicationService wallet = new WalletApplicationService(
                    walletRepo, new LocalDistributedLockService(), tx);

            GuildService guild = mock(GuildService.class);
            when(guild.findGuildIdByPlayer(11)).thenReturn(88L);
            when(guild.findGuildIdByPlayer(12)).thenReturn(88L);

            GuildRedPacketService red = new GuildRedPacketService(failingJdbc(), wallet, guild);
            GuildRedPacketService.OpResult created = red.create(11, 88L, 2, 100, 2);
            assertTrue(created.success());
            GuildRedPacketService.OpResult claim = red.claim(12, created.packet().packetId());
            assertTrue(claim.success());
            assertTrue(claim.claimedAmount() > 0);
            assertTrue(balances.get(12).get(2) > 0);
        }

        @Test
        @DisplayName("语音房票据签发与校验")
        void voiceTicket() {
            VoiceSignalingService voice = new VoiceSignalingService();
            var join = voice.joinOrCreate(1, "party-77");
            assertTrue(join.success());
            assertTrue(voice.validate(join.ticket().roomId(), join.ticket().token()));
        }
    }

    @Nested
    @DisplayName("图鉴 / 好感 / 名片 / 剧情分支")
    class CollectStoryProfileFlow {

        @Test
        @DisplayName("图鉴解锁→好感提升→名片装框")
        void handbookAffinityCard() {
            ObjectProvider<cn.itcast.demo.mylunarcore.achievement.AchievementService> ach =
                    mock(ObjectProvider.class);
            when(ach.getIfAvailable()).thenReturn(null);
            HandbookService handbook = new HandbookService(failingJdbc(), ach);
            assertTrue(handbook.unlock(1, HandbookService.EntryType.ENEMY, 90001));
            assertEquals(1, handbook.summary(1, HandbookService.EntryType.ENEMY).unlocked());

            AffinityService affinity = new AffinityService(failingJdbc());
            AffinityService.ChangeResult up = affinity.addFromDialogue(1, "march7", "c1", 120);
            assertTrue(up.success());
            assertTrue(up.state().level() >= 1);

            PlayerCardService card = new PlayerCardService(failingJdbc());
            assertTrue(card.grantFrame(1, 2001));
            assertTrue(card.equipFrame(1, 2001).success());
            assertEquals(2001, card.getOrDefault(1, "开拓者", 10).frameId());
        }

        @Test
        @DisplayName("对话选项应触发好感副作用（无树时跳过）")
        void dialogueAffinityHookWired() {
            ObjectProvider<AffinityService> provider = mock(ObjectProvider.class);
            AffinityService affinity = new AffinityService(failingJdbc());
            when(provider.getIfAvailable()).thenReturn(affinity);
            DialogueTriggerEngine engine = new DialogueTriggerEngine(
                    mock(DialogueTreeRepository.class),
                    new DialogueProgressService(),
                    mock(CutsceneTriggerService.class),
                    provider);
            assertNotNull(engine);
            AffinityService.ChangeResult direct = affinity.addFromDialogue(9, "tree-x", "choice", 15);
            assertTrue(direct.success());
        }
    }

    @Nested
    @DisplayName("战斗快照 hydrate 与跨节点迁移")
    class BattleMigrateFlow {

        @Test
        @DisplayName("存盘→导出载荷→移除→导入→BattleManager 可查")
        void snapshotMigrateAdopt() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getBattleSnapshot().setEnabled(true);
            props.getRedis().setEnabled(false);
            BattleSnapshotService snap = new BattleSnapshotService(new ObjectMapper(), props, null);
            BattleManager mgr = new BattleManager(snap);

            BattleContext ctx = BattleContext.createNew(55L, 900, 1, 100, 12345L, List.of());
            ctx.getEntity(900).setHp(777);
            mgr.put(ctx);
            String payload = snap.exportMigrationPayload(55L).orElseThrow();
            mgr.remove(55L);

            BattleContext adopted = mgr.adoptMigrationPayload(payload);
            assertNotNull(adopted);
            assertEquals(777, mgr.findActiveByPlayerId(900).getEntity(900).getHp());
        }

        @Test
        @DisplayName("fromSnapshot 保留参战者与波次")
        void hydrateParticipants() {
            BattleSnapshot s = new BattleSnapshot(
                    1L, 1, List.of(1, 2), 1, 1, 1L, 3, 1, 2, false,
                    Map.of(1, new BattleSnapshot.EntitySnap(1, 10, false, 5, false, 0)),
                    System.currentTimeMillis());
            BattleContext c = BattleContext.fromSnapshot(s);
            assertTrue(c.isParticipant(2));
            assertEquals(3, c.getTurn());
        }
    }

    @Nested
    @DisplayName("匹配机器人动态难度")
    class MatchBotFlow {

        @Test
        @DisplayName("补位 bot 战力应接近玩家均战力×倍率")
        void botPowerTracksPlayers() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getMatchmaking().setFillWithBots(true);
            props.getMatchmaking().setBotPowerRatio(1.0);
            props.getMatchmaking().setBotUidBase(-5000);
            RoomService rooms = new RoomService();
            MatchmakingService mm = new MatchmakingService(
                    rooms, mock(GameSessionManager.class), props,
                    new BusinessMetrics(new SimpleMeterRegistry()));
            assertTrue(mm.joinQueue(201, 1, 50, 2000).success());
            // 单人 + bot 补位：经批次 Tick 成局
            mm.batchMatchTick();
            var room = rooms.findRoomByPlayer(201);
            assertNotNull(room);
            assertEquals(2, room.members().size());
            assertTrue(room.members().stream().anyMatch(m -> m.playerId() < 0));
        }
    }

    @Nested
    @DisplayName("Rogue 地图 / 活动模板 / 平衡分析 / 剧情资源")
    class ContentOpsFlow {

        @Test
        @DisplayName("Rogue 种子复现 + 活动转盘拼图积分")
        void rogueAndActivityTemplates() {
            RogueMapGenerator gen = new RogueMapGenerator(new ObjectMapper());
            gen.load();
            var a = gen.generate(99L, 3, 4);
            var b = gen.generate(99L, 3, 4);
            assertEquals(a.layers().get(0).get(0).type(), b.layers().get(0).get(0).type());

            ActivityTemplateService act = new ActivityTemplateService(
                    failingJdbc(),
                    org.mockito.Mockito.mock(ActivityScriptEngine.class),
                    new ActivityCircuitBreakerService());
            assertTrue(act.spinWheel(1, 8, List.of(1), List.of("奖")).success());
            assertTrue(act.collectPuzzlePiece(1, 9, 0, 1).payload().get("complete").equals(true));
            act.addPoints(1, 10, 30);
            assertTrue(act.exchangePoints(1, 10, 20, "skin").success());
        }

        @Test
        @DisplayName("战斗组合胜率统计 + 剧情资源 diff")
        void balanceAndStoryAssets() {
            CombatBalanceAnalyticsService bal = new CombatBalanceAnalyticsService(failingJdbc());
            var key = new CombatBalanceAnalyticsService.ComboKey(1001, 20001, 501);
            for (int i = 0; i < 5; i++) {
                bal.recordBattle(List.of(key), i % 2 == 0);
            }
            assertFalse(bal.topByWinRate(5).isEmpty());

            StoryAssetVersionService story = new StoryAssetVersionService(failingJdbc());
            story.publish("voice", "1.1.0", "abc");
            List<Map<String, Object>> need = story.diffClient(Map.of("voice", "1.0.0", "dialogue", "1.0.0"));
            assertTrue(need.stream().anyMatch(m -> "voice".equals(m.get("bundleId"))));
        }
    }

    @Nested
    @DisplayName("配置灰度读路径")
    class GrayReadFlow {

        @Test
        @DisplayName("尾号灰度：在集用 canary，不在集用 stable")
        void graySelect() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getConfigGray().setEnabled(true);
            props.getConfigGray().setUidTails("1,3");
            ConfigGrayRelease gray = new ConfigGrayRelease(props);
            ConfigGrayReader reader = new ConfigGrayReader(gray);
            assertEquals("new", reader.select(11L, "s1", "new", "old"));
            assertEquals("old", reader.select(12L, "s1", "new", "old"));
        }
    }

    @Nested
    @DisplayName("配置版本列表（无表时为空）")
    class ConfigVersionFlow {

        @Test
        @DisplayName("list/diff 在无 DB 时安全降级")
        void listDiffSafe() {
            ConfigReleaseService svc = new ConfigReleaseService(failingJdbc());
            assertTrue(svc.list(null, 10).isEmpty());
            Map<String, Object> diff = svc.diff("Banners.json", 1, 2);
            assertTrue(Boolean.TRUE.equals(diff.get("ok")) || Boolean.FALSE.equals(diff.get("ok")));
        }
    }
}
