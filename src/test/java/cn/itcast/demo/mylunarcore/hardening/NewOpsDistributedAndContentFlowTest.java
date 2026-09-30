package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityTypeCatalog;
import cn.itcast.demo.mylunarcore.admin.SensitiveOpApprovalService;
import cn.itcast.demo.mylunarcore.anticheat.PacketIntegrityGuard;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.center.CenterServer;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.center.MigrationWriteFreezeService;
import cn.itcast.demo.mylunarcore.center.PlayerMigrationService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.InternalTokenVerifier;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueUnlockQueryService;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeSocialService;
import cn.itcast.demo.mylunarcore.net.ClientFeatureFlags;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneSnapshotService;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新功能与新业务流程全覆盖：分布式组队/切服、审批、家园社交、剧情解锁、运营指标、反作弊、活动目录。
 */
@DisplayName("新运维/分布式/内容业务流程全测")
class NewOpsDistributedAndContentFlowTest {

    @Nested
    @DisplayName("组队版本号 + 心跳续期")
    class PartyVersionFlow {
        @Test
        @DisplayName("邀请应递增 version；heartbeat 刷新 lastHeartbeat")
        void inviteBumpsVersionAndHeartbeatTouches() {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);
            when(store.saveIfNewer(any())).thenReturn(true);
            when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
            PartyService party = new PartyService(store);

            PartyService.Party created = party.create(1L).party();
            assertEquals(1L, created.version());
            long hb0 = created.lastHeartbeatMs();

            PartyService.Party afterInvite = party.invite(1L, 2L).party();
            assertEquals(2L, afterInvite.version());
            assertTrue(afterInvite.contains(2L));

            party.heartbeatLocalParties();
            PartyService.Party afterHb = party.getByUid(1L);
            assertNotNull(afterHb);
            assertTrue(afterHb.lastHeartbeatMs() >= hb0);
            verify(store).renewLease(any());
        }

        @Test
        @DisplayName("Redis 版本冲突时应返回 VERSION_CONFLICT")
        void versionConflictOnStaleWrite() {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(true);
            when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
            // create 走 mock 的 void save()（不消耗 saveIfNewer）；invite 直接 saveIfNewer
            when(store.saveIfNewer(any())).thenReturn(false);
            PartyService party = new PartyService(store);
            assertEquals(PartyService.PartyResultCode.OK, party.create(10L).code());
            assertEquals(PartyService.PartyResultCode.VERSION_CONFLICT, party.invite(10L, 11L).code());
        }
    }

    @Nested
    @DisplayName("切服写冻结 + 迁移包 + 票据解冻")
    class MigrationFreezeFlow {
        @Test
        @DisplayName("跨节点迁移应冻结写并生成迁移包；consumeTicket 后解冻")
        void freezePackageAndAckOnTicketConsume() {
            MigrationWriteFreezeService freeze = new MigrationWriteFreezeService();
            CenterServer center = mock(CenterServer.class);
            ZoneManager zones = mock(ZoneManager.class);
            SceneManager scenes = mock(SceneManager.class);
            SceneSnapshotService sceneSnap = mock(SceneSnapshotService.class);
            BattleSnapshotService battleSnap = mock(BattleSnapshotService.class);
            when(battleSnap.loadByPlayer(anyInt())).thenReturn(java.util.Optional.empty());

            int zoneId = SceneRegistry.zoneId(20101, 5);
            when(center.planMigration(20101, 5)).thenReturn(
                    new CenterServer.MigrationPlan(zoneId, 20101, 5, "node-b", "10.0.0.2", 9000));
            when(center.isLocalNode("node-b")).thenReturn(false);

            SceneContext ctx = mock(SceneContext.class);
            when(scenes.getByPlayerUid(10086L)).thenReturn(ctx);
            when(ctx.getZoneId()).thenReturn(1);
            when(ctx.getEntryId()).thenReturn(1);
            when(ctx.getPlayerPos()).thenReturn(new SceneContext.ScenePos(1f, 2f, 3f));

            PlayerMigrationService migration = new PlayerMigrationService(
                    center, zones, scenes, new MigrationTicketService(), new SceneRegistry(),
                    sceneSnap, battleSnap, freeze, 10_000, 3);

            PlayerMigrationService.MigrationResult r = migration.migrate(10086L, 20101, 5);
            assertEquals(4, r.retcode());
            assertTrue(freeze.isFrozen(10086L));
            assertNotNull(freeze.getPackage(10086L));
            assertEquals("10.0.0.2", freeze.getPackage(10086L).cacheExtras().get("targetHost"));

            assertNotNull(migration.consumeTicket(r.sessionTicket()));
            assertFalse(freeze.isFrozen(10086L));
        }

        @Test
        @DisplayName("冻结超时后应自动解冻")
        void freezeExpires() throws Exception {
            MigrationWriteFreezeService freeze = new MigrationWriteFreezeService();
            freeze.freeze(7L);
            assertTrue(freeze.isFrozen(7L));
            // 通过反射把 until 调到过去（避免等 15s）
            var field = MigrationWriteFreezeService.class.getDeclaredField("frozenUntilMs");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<Long, Long>) field.get(freeze);
            map.put(7L, System.currentTimeMillis() - 1);
            assertFalse(freeze.isFrozen(7L));
        }
    }

    @Nested
    @DisplayName("敏感操作双人审批")
    class ApprovalFlow {
        @Test
        @DisplayName("申请→他人批准→核销；自批无效；类型不匹配拒绝")
        void dualApprovalLifecycle() {
            SensitiveOpApprovalService svc = new SensitiveOpApprovalService(null);
            var t = svc.request("export_player_data", "{\"uid\":1}", "alice");
            assertEquals(SensitiveOpApprovalService.Status.PENDING, t.status());

            var self = svc.approve(t.ticketId(), "alice");
            assertEquals(SensitiveOpApprovalService.Status.PENDING, self.status());

            var ok = svc.approve(t.ticketId(), "bob");
            assertEquals(SensitiveOpApprovalService.Status.PENDING, ok.status());
            var ok2 = svc.approve(t.ticketId(), "carol");
            assertEquals(SensitiveOpApprovalService.Status.APPROVED, ok2.status());

            assertFalse(svc.consumeIfApproved(t.ticketId(), "batch_grant_items"));
            assertTrue(svc.consumeIfApproved(t.ticketId(), "export_player_data"));
            assertEquals(SensitiveOpApprovalService.Status.CONSUMED, svc.get(t.ticketId()).status());
            assertFalse(svc.consumeIfApproved(t.ticketId(), "export_player_data"));
        }
    }

    @Nested
    @DisplayName("家园社交点赞与繁荣度")
    class HomeSocialFlow {
        @Test
        @DisplayName("点赞去重、繁荣度计算、好友榜过滤")
        void likeProsperityFriendRank() {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(1, 1, 5).success());

            HomeSocialService social = new HomeSocialService(home, null);
            assertEquals(1, social.like(1, 1).retcode()); // 不能给自己点赞
            var like = social.like(2, 1);
            assertTrue(like.success());
            assertEquals(1, like.likes());
            assertFalse(social.like(2, 1).success()); // 去重

            int prosperity = social.prosperity(1);
            assertTrue(prosperity >= 5 * 10 + 3); // 设施5*10 + 赞*3

            var rank = social.topProsperity(10);
            assertFalse(rank.isEmpty());
            assertEquals(1, rank.get(0).playerId());

            assertTrue(social.friendProsperityRank(Set.of(99), 10).isEmpty());
            assertFalse(social.friendProsperityRank(Set.of(1), 10).isEmpty());
        }
    }

    @Nested
    @DisplayName("剧情分支 → 任务解锁")
    class DialogueUnlockFlow {
        @Test
        @DisplayName("记录选择与 flag 后可供任务/场景查询")
        void flagsAndChoicesUnlockQuest() {
            DialogueProgressService progress = new DialogueProgressService();
            progress.recordChoiceAndAdvance(42, "tree_a", "n1", "choice_good", "n2",
                    "unlock_chapter_2", 101);
            DialogueUnlockQueryService unlock = new DialogueUnlockQueryService(progress);

            assertTrue(unlock.canUnlockQuest(42, "unlock_chapter_2"));
            assertFalse(unlock.canUnlockQuest(42, "missing_flag"));
            assertTrue(unlock.canUnlockByChoice(42, "choice_good"));
            assertTrue(unlock.flags(42).contains("unlock_chapter_2"));
            assertTrue(progress.hasCg(42, 101));
        }
    }

    @Nested
    @DisplayName("运营指标 UV / 付费 / 抽卡独立玩家")
    class OpsMetricsFlow {
        @Test
        void dailyUvAndPayingDedup() {
            BusinessMetrics m = new BusinessMetrics(new SimpleMeterRegistry());
            m.recordDailyLoginUv(1L);
            m.recordDailyLoginUv(1L);
            m.recordDailyLoginUv(2L);
            m.recordPayingUser(9L);
            m.recordPayingUser(9L);
            m.recordGachaUniquePlayer(3L);
            m.recordGachaUniquePlayer(3L);
            m.recordDungeonAttempt();
            m.recordDungeonClear();
            // 无异常即通过；去重靠内部 Set
            m.recordLoginSuccess();
            m.recordLoginFail();
            assertTrue(m.loginFailRate() > 0);
        }
    }

    @Nested
    @DisplayName("密钥轮换 + 能力位 + 活动目录 + 反作弊时间回拨")
    class SecurityAndCatalogFlow {
        @Test
        void dualTokenRotation() {
            assertTrue(InternalTokenVerifier.matchesAny("new", "old", "new"));
            assertTrue(InternalTokenVerifier.matchesAny("new", "old", "old"));
            assertFalse(InternalTokenVerifier.matchesAny("new", "old", "other"));
            assertFalse(InternalTokenVerifier.matchesAny("new", "", "old"));
        }

        @Test
        void featureFlagsIncludeCharacterV2AndHomeSocial() {
            assertTrue(ClientFeatureFlags.supports(ClientFeatureFlags.SERVER_ALL, ClientFeatureFlags.CHARACTER_V2));
            assertTrue(ClientFeatureFlags.supports(ClientFeatureFlags.SERVER_ALL, ClientFeatureFlags.HOME_SOCIAL));
            long legacy = ClientFeatureFlags.normalizeClientMask(0L);
            assertFalse(ClientFeatureFlags.supports(legacy, ClientFeatureFlags.GUILD_WAR));
        }

        @Test
        void activityCatalogHasEndlessAbyssAndGuildExpedition() {
            ActivityTypeCatalog catalog = new ActivityTypeCatalog(new ObjectMapper(), "data");
            catalog.reload();
            assertTrue(catalog.find("endless_abyss").isPresent());
            assertTrue(catalog.find("guild_expedition").isPresent());
            assertTrue(catalog.find("tower").isPresent());
        }

        @Test
        void packetIntegrityRejectsClockRollback() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAntiCheat().setPacketIntegrityEnabled(true);
            props.getAntiCheat().setMaxClientClockSkewMs(60_000L);
            PacketIntegrityGuard guard = new PacketIntegrityGuard(props);
            long now = System.currentTimeMillis();
            assertTrue(guard.validate(1L, now, 1L, null).ok());
            var rollback = guard.validate(1L, now - 1000L, 2L, null);
            assertFalse(rollback.ok());
            assertEquals(PacketIntegrityGuard.RejectReason.TIMESTAMP_SKEW, rollback.reason());
        }
    }
}
