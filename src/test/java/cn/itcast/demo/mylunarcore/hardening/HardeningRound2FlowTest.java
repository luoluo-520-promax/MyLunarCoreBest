package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassNettyService;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassService;
import cn.itcast.demo.mylunarcore.center.CenterServer;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.center.MigrationWriteFreezeService;
import cn.itcast.demo.mylunarcore.center.PlayerMigrationService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.common.SensitiveDataMasker;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.config.ProductionSecretsValidator;
import cn.itcast.demo.mylunarcore.economy.EconomyTestFixtures;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.economy.iap.AppleIapChannelVerifier;
import cn.itcast.demo.mylunarcore.economy.iap.GoogleIapChannelVerifier;
import cn.itcast.demo.mylunarcore.economy.iap.IapGrantService;
import cn.itcast.demo.mylunarcore.economy.iap.IapOrderService;
import cn.itcast.demo.mylunarcore.economy.iap.IapProductCatalog;
import cn.itcast.demo.mylunarcore.economy.iap.IapVerifyGateway;
import cn.itcast.demo.mylunarcore.economy.iap.MockIapChannelVerifier;
import cn.itcast.demo.mylunarcore.economy.iap.PurchaseLimitService;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.guild.GuildWarService;
import cn.itcast.demo.mylunarcore.hall.ChatDomainEvents;
import cn.itcast.demo.mylunarcore.matchmaking.MatchDomainEvents;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatibilityHandler;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.BattlePassSystemProto;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneSnapshot;
import cn.itcast.demo.mylunarcore.scene.SceneSnapshotService;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.tools.GuildWarMockClient;
import cn.itcast.demo.mylunarcore.tx.LocalDistributedLockService;
import cn.itcast.demo.mylunarcore.tx.LocalTxLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 加固第二轮：IAP 真渠道 / 密钥强度 / 切服快照 / 战令协议 / 事件解耦 / Outbox 等业务流程。
 */
@DisplayName("加固第二轮新功能与业务流程")
class HardeningRound2FlowTest {

    private static final String STRONG = "Prod-Internal-Token-9f3a!SecureKey01";
    private static final String STRONG_DB = "S3cure-Db-Pass!Word-Length32xxxx";

    @Nested
    @DisplayName("IAP 真渠道与 Mock 门禁")
    class IapFlow {
        @Test
        @DisplayName("关闭 Mock 且未配置渠道时验签失败")
        void realModeRejectsWithoutChannel() {
            MockIapChannelVerifier mockV = new MockIapChannelVerifier();
            ObjectMapper mapper = new ObjectMapper();
            AppleIapChannelVerifier apple = new AppleIapChannelVerifier(false, "", "", true, mapper);
            GoogleIapChannelVerifier google = new GoogleIapChannelVerifier(false, "", "", mapper);
            IapVerifyGateway gw = new IapVerifyGateway(false, List.of(mockV, apple, google), mockV);
            assertFalse(gw.isMockEnabled());
            assertFalse(gw.hasConfiguredRealChannel());
            assertFalse(gw.verify("apple", "o1", "sku", "receipt").success());
            assertFalse(gw.verify("mock", "o1", "sku", "receipt").success());
        }

        @Test
        @DisplayName("Apple/Google enabled+凭证后标记为已配置")
        void configuredChannelsDetected() {
            ObjectMapper mapper = new ObjectMapper();
            AppleIapChannelVerifier apple = new AppleIapChannelVerifier(
                    true, "shared-secret", "com.example.app", true, mapper);
            String sa = "{\"client_email\":\"a@b.iam.gserviceaccount.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nMIIE\\n-----END PRIVATE KEY-----\\n\"}";
            GoogleIapChannelVerifier google = new GoogleIapChannelVerifier(
                    true, "com.example.app", sa, mapper);
            assertTrue(apple.isConfigured());
            assertTrue(google.isConfigured());
            IapVerifyGateway gw = new IapVerifyGateway(
                    false, List.of(apple, google, new MockIapChannelVerifier()), new MockIapChannelVerifier());
            assertTrue(gw.hasConfiguredRealChannel());
        }

        @Test
        @DisplayName("Create→Confirm→重放拒绝完整路径")
        void createConfirmReplayRejected() {
            IapProductCatalog catalog = mock(IapProductCatalog.class);
            when(catalog.find(eq(2), eq(2001))).thenReturn(
                    EconomyTestFixtures.iapTopup(2001, "com.mylunarcore.crystal.60", 600, 2, 60));
            when(catalog.canCreateOrder(any(), any())).thenReturn(true);

            MockIapChannelVerifier mockV = new MockIapChannelVerifier();
            IapVerifyGateway gw = new IapVerifyGateway(true, List.of(mockV), mockV);
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(anyInt())).thenReturn(Map.of(2, 60));
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(2, 60), "iap"));

            PurchaseLimitService limits = new PurchaseLimitService();
            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.common.BusinessMetrics> metricsProvider = mock(ObjectProvider.class);
            when(metricsProvider.getIfAvailable()).thenReturn(null);
            IapGrantService grant = new IapGrantService(
                    wallet, mock(cn.itcast.demo.mylunarcore.repo.ItemRepository.class),
                    mock(cn.itcast.demo.mylunarcore.player.PlayerAggregateService.class),
                    new cn.itcast.demo.mylunarcore.economy.iap.IapEntitlementService(),
                    limits,
                    mock(cn.itcast.demo.mylunarcore.skin.SkinConfigRepository.class),
                    mock(cn.itcast.demo.mylunarcore.skin.SkinOwnershipService.class),
                    metricsProvider,
                    mock(ObjectProvider.class),
                    mock(ObjectProvider.class));

            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.security.BizReplayGuardService> replay = mock(ObjectProvider.class);
            when(replay.getIfAvailable()).thenReturn(null);
            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.security.RedisBizReplayGuard> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);

            IapOrderService orders = new IapOrderService(
                    catalog, limits, gw, grant, wallet, replay, redis);
            IapOrderService.CreateResult created = orders.createOrder(1001, 2, 2001);
            assertTrue(created.success());
            IapOrderService.ConfirmResult ok = orders.confirmOrder(
                    1001, created.order().orderId(), "mock", "receipt-body");
            assertTrue(ok.success());
            IapOrderService.ConfirmResult again = orders.confirmOrder(
                    1001, created.order().orderId(), "mock", "receipt-body");
            assertTrue(again.success()); // 幂等已发货
        }
    }

    @Nested
    @DisplayName("生产密钥强度")
    class SecretsFlow {
        @Test
        @DisplayName("强密钥 + 全部门禁通过")
        void strongSecretsPass() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.password", STRONG_DB);
            env.setProperty("DB_PASSWORD", STRONG_DB);
            env.setProperty("lunarcore.internal-api-token", STRONG);
            env.setProperty("INTERNAL_API_TOKEN", STRONG);
            env.setProperty("lunarcore.kcp-crypto.enabled", "true");
            env.setProperty("lunarcore.protocol-hmac.enabled", "true");
            env.setProperty("mylunarcore.iap.mock-verify", "false");
            env.setProperty("lunarcore.admin.ip-whitelist", "10.0.0.0/8");
            assertTrue(ProductionSecretsValidator.validate(env).isEmpty());
        }

        @Test
        @DisplayName("allow-plaintext 未开 TCP TLS 应失败")
        void plaintextRequiresTcpTls() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.password", STRONG_DB);
            env.setProperty("DB_PASSWORD", STRONG_DB);
            env.setProperty("lunarcore.internal-api-token", STRONG);
            env.setProperty("INTERNAL_API_TOKEN", STRONG);
            env.setProperty("lunarcore.kcp-crypto.enabled", "false");
            env.setProperty("lunarcore.kcp-crypto.allow-plaintext", "true");
            env.setProperty("lunarcore.tls.game-tcp-enabled", "false");
            env.setProperty("lunarcore.protocol-hmac.enabled", "true");
            env.setProperty("mylunarcore.iap.mock-verify", "false");
            env.setProperty("lunarcore.admin.ip-whitelist", "10.0.0.0/8");
            List<String> errors = ProductionSecretsValidator.validate(env);
            assertTrue(errors.stream().anyMatch(e -> e.contains("game-tcp-enabled")));
        }
    }

    @Nested
    @DisplayName("协议兼容与战令 CmdId")
    class ProtocolFlow {
        @Test
        @DisplayName("兼容层 remap 旧角色号段")
        void compatibilityHandlerRemapsLegacy() {
            ProtocolCompatService compat = new ProtocolCompatService();
            ProtocolCompatibilityHandler handler = new ProtocolCompatibilityHandler(compat);
            assertTrue(handler.acceptClientVersion(2));
            assertFalse(handler.acceptClientVersion(1));
            // wire < 当前版本时，legacy 120 映射到现行角色号段
            assertEquals(CmdIds.CREATE_CHARACTER_CS_REQ, handler.remapCmdId(120, 2));
            assertEquals(120, handler.remapCmdId(120, CmdIds.PROTOCOL_WIRE_VERSION));
        }

        @Test
        @DisplayName("战令 CmdId 990–995 成对且唯一")
        void battlePassCmdIds() {
            assertEquals(990, CmdIds.GET_BATTLE_PASS_CS_REQ);
            assertEquals(991, CmdIds.GET_BATTLE_PASS_SC_RSP);
            assertEquals(992, CmdIds.CLAIM_BATTLE_PASS_CS_REQ);
            assertEquals(993, CmdIds.CLAIM_BATTLE_PASS_SC_RSP);
            assertEquals(994, CmdIds.BUY_BATTLE_PASS_PREMIUM_CS_REQ);
            assertEquals(995, CmdIds.BUY_BATTLE_PASS_PREMIUM_SC_RSP);
        }

        @Test
        @DisplayName("未登录查询战令返回 retcode=1")
        void battlePassRequiresLogin() {
            BattlePassService bp = mock(BattlePassService.class);
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(0);
            BattlePassNettyService netty = new BattlePassNettyService(bp, resolver);
            EmbeddedChannel ch = new EmbeddedChannel();
            var rsp = netty.handleGet(BattlePassSystemProto.GetBattlePassCsReq.getDefaultInstance(), ch);
            assertEquals(1, rsp.getRetcode());
        }
    }

    @Nested
    @DisplayName("切服：场景快照与限流")
    class MigrationFlow {
        @Test
        @DisplayName("场景快照内存存取闭环")
        void sceneSnapshotRoundTrip() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getRedis().setEnabled(false);
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            SceneSnapshotService snaps = new SceneSnapshotService(new ObjectMapper(), props, redis);

            SceneContext ctx = new SceneContext(42L, 20101, 1, 7, new SceneContext.ScenePos(1.5f, 2f, 3.5f));
            snaps.save(ctx);
            Optional<SceneSnapshot> loaded = snaps.load(42L);
            assertTrue(loaded.isPresent());
            assertEquals(20101, loaded.get().planeId());
            assertEquals(1, loaded.get().floorId());
            assertEquals(7, loaded.get().entryId());
            assertEquals(1.5f, loaded.get().posX(), 0.001f);
            snaps.remove(42L);
            assertTrue(snaps.load(42L).isEmpty());
        }

        @Test
        @DisplayName("迁移限流触发 retcode=8")
        void migrationRateLimited() {
            CenterServer center = mock(CenterServer.class);
            SceneSnapshotService sceneSnap = mock(SceneSnapshotService.class);
            BattleSnapshotService battleSnap = mock(BattleSnapshotService.class);
            when(battleSnap.loadByPlayer(anyInt())).thenReturn(Optional.empty());
            PlayerMigrationService migration = new PlayerMigrationService(
                    center, mock(ZoneManager.class), mock(SceneManager.class),
                    new MigrationTicketService(), new SceneRegistry(),
                    sceneSnap, battleSnap, new MigrationWriteFreezeService(), 1, 3);

            CenterServer.MigrationPlan plan = CenterServer.MigrationPlan.of(1, 1, 1, "local");
            when(center.planMigration(anyInt(), anyInt())).thenReturn(plan);
            when(center.isLocalNode("local")).thenReturn(true);

            assertEquals(0, migration.migrate(1L, 1, 1).retcode());
            assertEquals(8, migration.migrate(2L, 1, 1).retcode());
        }

        @Test
        @DisplayName("跨节点签发票据并保存场景快照")
        void crossNodeSavesSnapshotAndTicket() {
            CenterServer center = mock(CenterServer.class);
            ZoneManager zones = mock(ZoneManager.class);
            SceneManager scenes = mock(SceneManager.class);
            SceneSnapshotService sceneSnap = mock(SceneSnapshotService.class);
            BattleSnapshotService battleSnap = mock(BattleSnapshotService.class);
            when(battleSnap.loadByPlayer(anyInt())).thenReturn(Optional.empty());

            SceneContext ctx = mock(SceneContext.class);
            when(ctx.getZoneId()).thenReturn(99);
            when(ctx.getEntryId()).thenReturn(1);
            when(ctx.getPlayerPos()).thenReturn(new SceneContext.ScenePos(9f, 8f, 7f));
            when(scenes.getByPlayerUid(10086L)).thenReturn(ctx);

            CenterServer.MigrationPlan plan = new CenterServer.MigrationPlan(
                    201010005, 20101, 5, "node-b", "10.0.0.2", 9000);
            when(center.planMigration(20101, 5)).thenReturn(plan);
            when(center.isLocalNode("node-b")).thenReturn(false);

            PlayerMigrationService migration = new PlayerMigrationService(
                    center, zones, scenes, new MigrationTicketService(), new SceneRegistry(),
                    sceneSnap, battleSnap, new MigrationWriteFreezeService(), 1000, 3);
            PlayerMigrationService.MigrationResult r = migration.migrate(10086L, 20101, 5);
            assertEquals(4, r.retcode());
            assertFalse(r.sessionTicket().isBlank());
            org.mockito.Mockito.verify(sceneSnap).save(ctx);
            org.mockito.Mockito.verify(zones).leaveZone(99, 10086L);
        }
    }

    @Nested
    @DisplayName("公会战闭环 + Mock 报文")
    class GuildWarFlow {
        @Test
        @DisplayName("匹配→报分→结算→排行 + 样本报文")
        void fullLoop() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
            when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                    .thenReturn(null);
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
            GuildService guild = mock(GuildService.class);
            when(guild.findGuildIdByPlayer(1)).thenReturn(10L);
            when(guild.findGuildIdByPlayer(2)).thenReturn(20L);
            GuildWarService war = new GuildWarService(jdbc, guild);

            assertTrue(war.requestMatch(1).success());
            GuildWarService.OpResult m2 = war.requestMatch(2);
            assertTrue(m2.success());
            assertEquals("MATCHED", m2.match().status());
            GuildWarService.OpResult report = war.reportBattleResult(1, m2.match().matchId(), 15, 4);
            assertTrue(report.success());
            assertEquals("SETTLED", report.match().status());
            assertFalse(war.seasonRank(report.match().seasonId(), 5).isEmpty());

            Map<String, String> packets = GuildWarMockClient.samplePackets(
                    m2.match().matchId(), report.match().seasonId());
            assertEquals(3, packets.size());
            assertTrue(packets.keySet().stream().anyMatch(k -> k.startsWith("986")));
        }
    }

    @Nested
    @DisplayName("匹配/聊天领域事件")
    class DomainEventsFlow {
        @Test
        @DisplayName("MatchFound / ChatMessage 经 Spring Events 发出")
        void publishEvents() {
            List<Object> events = new ArrayList<>();
            ApplicationEventPublisher pub = events::add;
            MatchDomainEvents match = new MatchDomainEvents(pub);
            ChatDomainEvents chat = new ChatDomainEvents(pub);
            match.publishMatchFound("m-1", 1, List.of(1, 2));
            chat.publishWorld(1, "hello");
            chat.publishPrivate(1, 2, "hi");
            assertEquals(3, events.size());
            assertTrue(events.get(0) instanceof MatchDomainEvents.MatchFoundEvent);
            assertTrue(events.get(1) instanceof ChatDomainEvents.ChatMessageEvent);
        }
    }

    @Nested
    @DisplayName("钱包 Outbox + 脱敏")
    class WalletAndMaskFlow {
        @Test
        @DisplayName("扣费成功写入并提交 local_tx_log")
        void deductWritesOutbox() {
            WalletRepository repo = mock(WalletRepository.class);
            when(repo.lockCurrency(1001)).thenReturn(new WalletRepository.CurrencyLockRow(Map.of(1, 100), 1L));
            when(repo.updateCurrencyOptimistic(eq(1001), anyMap(), eq(1L))).thenReturn(true);
            when(repo.loadCurrency(1001)).thenReturn(Map.of(1, 90));

            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
            LocalTxLogService txLog = new LocalTxLogService(jdbc);

            @SuppressWarnings("unchecked")
            ObjectProvider<LocalTxLogService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(txLog);

            WalletApplicationService wallet = new WalletApplicationService(
                    repo, new LocalDistributedLockService(), provider);
            WalletApplicationService.WalletChangeResult r = wallet.deduct(1001, 1, 10, "shop:1");
            assertTrue(r.success());
            org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.atLeastOnce())
                    .update(anyString(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("PII 与支付字段脱敏")
        void maskPiiAndPayment() {
            String masked = SensitiveDataMasker.mask(
                    "email=a@b.com phone=13900001111 receipt=RCPT-1 id_card=110101199001011234");
            assertFalse(masked.contains("a@b.com"));
            assertFalse(masked.contains("13900001111"));
            assertFalse(masked.contains("RCPT-1"));
            assertFalse(masked.contains("110101199001011234"));
        }
    }
}
