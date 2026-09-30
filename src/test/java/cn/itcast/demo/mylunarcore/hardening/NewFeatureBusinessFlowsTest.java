package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.admin.AdminIpWhitelistFilter;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.ConfigImportService;
import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import cn.itcast.demo.mylunarcore.common.ConfigReleaseService;
import cn.itcast.demo.mylunarcore.common.ActivityImportService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.config.ProductionSecretsValidator;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueCutsceneNettyService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.guild.GuildNettyService;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.guild.GuildWarService;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.DialogueCutscenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.GuildPacketHandlers;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.KcpGcmNonceGuard;
import cn.itcast.demo.mylunarcore.net.KcpSessionCryptoCodec;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.net.ProtocolHmacSigner;
import cn.itcast.demo.mylunarcore.net.SessionCryptoBinder;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerSessionService;
import cn.itcast.demo.mylunarcore.protocol.DialogueCutsceneProto;
import cn.itcast.demo.mylunarcore.protocol.GuildSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import cn.itcast.demo.mylunarcore.repo.GameDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本轮新功能与新业务流程全面回归：登录协议绑定、会话加密、管理白名单、
 * 公会战/家园/对话协议、配置 dry-run 与生产密钥门禁。
 */
@DisplayName("新功能与新业务流程全面测试")
class NewFeatureBusinessFlowsTest {

    @Nested
    @DisplayName("登录协议版本绑定")
    class LoginWireVersionFlow {

        @Test
        @DisplayName("旧客户端 wire_version&lt;2 应拒绝（retcode=9）")
        void rejectLegacyWireVersion() {
            PlayerSessionService svc = mockSessionServiceShell();
            // 通过真实 ProtocolCompat + 反射不可行，直接测兼容服务与常量契约
            ProtocolCompatService compat = new ProtocolCompatService();
            assertFalse(compat.isCompatible(0));
            assertFalse(compat.isCompatible(1));
            assertTrue(compat.isCompatible(CmdIds.PROTOCOL_WIRE_VERSION));
            assertEquals(9, PlayerSessionService.RET_PROTOCOL_INCOMPATIBLE);
            assertNotNull(svc);
        }

        @Test
        @DisplayName("登录请求/响应含 wire_version 与 session_crypto_key 字段")
        void loginProtoCarriesCryptoFields() {
            PlayerSessionProto.PlayerLoginCsReq req = PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                    .setUsername("u")
                    .setPassword("p")
                    .setWireVersion(2)
                    .build();
            assertEquals(2, req.getWireVersion());

            PlayerSessionProto.PlayerLoginScRsp rsp = PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(0)
                    .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                    .setSessionCryptoKey(Base64.getEncoder().encodeToString(new byte[16]))
                    .build();
            assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());
            assertFalse(rsp.getSessionCryptoKey().isBlank());
            assertEquals(16, Base64.getDecoder().decode(rsp.getSessionCryptoKey()).length);
        }

        private PlayerSessionService mockSessionServiceShell() {
            return mock(PlayerSessionService.class);
        }
    }

    @Nested
    @DisplayName("会话密钥绑定与 KCP Nonce 防重放")
    class SessionCryptoAndNonceFlow {

        @Test
        @DisplayName("SessionCryptoBinder 写入 KCP/HMAC Attribute 并返回 Base64")
        void binderSetsChannelAttrs() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getKcpCrypto().setEnabled(true);
            props.getKcpCrypto().setKeyBytes(16);
            props.getProtocolHmac().setEnabled(true);
            SessionCryptoBinder binder = new SessionCryptoBinder(props);
            EmbeddedChannel ch = new EmbeddedChannel();

            String b64 = binder.bindNewSessionKey(ch);
            assertFalse(b64.isBlank());
            byte[] key = ch.attr(KcpSessionCryptoCodec.SESSION_KEY).get();
            byte[] hmac = ch.attr(ProtocolHmacSigner.SESSION_HMAC_KEY).get();
            assertNotNull(key);
            assertNotNull(hmac);
            assertEquals(16, key.length);
            assertEquals(b64, Base64.getEncoder().encodeToString(key));
        }

        @Test
        @DisplayName("加密关闭时不生成会话密钥")
        void binderNoopWhenCryptoOff() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getKcpCrypto().setEnabled(false);
            props.getProtocolHmac().setEnabled(false);
            assertEquals("", new SessionCryptoBinder(props).bindNewSessionKey(new EmbeddedChannel()));
        }

        @Test
        @DisplayName("同一 IV 第二次解密应被丢弃（防重放）")
        void duplicateIvRejected() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getKcpCrypto().setEnabled(true);
            KcpSessionCryptoCodec codec = new KcpSessionCryptoCodec(props);
            EmbeddedChannel ch = new EmbeddedChannel(codec);
            byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
            ch.attr(KcpSessionCryptoCodec.SESSION_KEY).set(key);

            assertTrue(ch.writeOutbound(Unpooled.wrappedBuffer(new byte[]{9, 8, 7})));
            ByteBuf encrypted = ch.readOutbound();
            assertNotNull(encrypted);
            ByteBuf copy = encrypted.copy();

            assertTrue(ch.writeInbound(encrypted.retain()));
            assertNotNull(ch.readInbound());

            // 重放同一密文（相同 IV）：解码丢弃，writeInbound 可能返回 false
            ch.writeInbound(copy);
            assertNull(ch.readInbound());
            ch.finishAndReleaseAll();
        }

        @Test
        @DisplayName("KcpGcmNonceGuard 窗口内拒绝重复 nonce")
        void nonceGuardWindow() {
            EmbeddedChannel ch = new EmbeddedChannel();
            byte[] iv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            assertTrue(KcpGcmNonceGuard.accept(ch, iv));
            assertFalse(KcpGcmNonceGuard.accept(ch, iv));
            assertTrue(KcpGcmNonceGuard.accept(ch, new byte[]{2, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}));
        }
    }

    @Nested
    @DisplayName("管理后台 IP 白名单")
    class AdminIpWhitelistFlow {

        @Test
        @DisplayName("未配置白名单时放行")
        void disabledWhenEmpty() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAdmin().setIpWhitelist("");
            AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(props);
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/ops/reload");
            req.setRemoteAddr("8.8.8.8");
            MockHttpServletResponse rsp = new MockHttpServletResponse();
            filter.doFilter(req, rsp, chain);
            verify(chain).doFilter(req, rsp);
        }

        @Test
        @DisplayName("CIDR/精确 IP 允许，公网拒绝")
        void allowlistEnforced() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAdmin().setIpWhitelist("10.0.0.0/8,127.0.0.1");
            AdminIpWhitelistFilter filter = new AdminIpWhitelistFilter(props);
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/api/admin/login");
            ok.setRemoteAddr("10.1.2.3");
            MockHttpServletResponse okRsp = new MockHttpServletResponse();
            filter.doFilter(ok, okRsp, chain);
            verify(chain).doFilter(ok, okRsp);

            MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/api/admin/login");
            bad.setRemoteAddr("8.8.8.8");
            MockHttpServletResponse badRsp = new MockHttpServletResponse();
            filter.doFilter(bad, badRsp, chain);
            assertEquals(403, badRsp.getStatus());
            assertTrue(badRsp.getContentAsString().contains("admin_ip_not_allowed"));
            verify(chain, never()).doFilter(eq(bad), any());
        }
    }

    @Nested
    @DisplayName("公会战协议业务流程")
    class GuildWarProtocolFlow {

        private GuildService guild;
        private GuildWarService war;
        private GuildNettyService netty;
        private GuildPacketHandlers handlers;

        @BeforeEach
        void setUp() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
            when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                    .thenReturn(null);
            when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
            guild = mock(GuildService.class);
            when(guild.findGuildIdByPlayer(1)).thenReturn(100L);
            when(guild.findGuildIdByPlayer(2)).thenReturn(200L);
            war = new GuildWarService(jdbc, guild);
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(1);
            netty = new GuildNettyService(guild, war, mock(cn.itcast.demo.mylunarcore.guild.GuildTechService.class),
                    resolver, mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class));
            handlers = new GuildPacketHandlers(netty);
        }

        @Test
        @DisplayName("匹配→报分→排行 闭环")
        void matchReportRank() {
            GuildSystemProto.GuildWarMatchScRsp m1 = netty.handleWarMatch(new EmbeddedChannel());
            assertEquals(0, m1.getRetcode());
            assertTrue(m1.hasMatch());

            PlayerContextResolver r2 = mock(PlayerContextResolver.class);
            when(r2.resolvePlayerId(any())).thenReturn(2);
            GuildNettyService netty2 = new GuildNettyService(guild, war,
                    mock(cn.itcast.demo.mylunarcore.guild.GuildTechService.class), r2,
                    mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class));
            GuildSystemProto.GuildWarMatchScRsp m2 = netty2.handleWarMatch(new EmbeddedChannel());
            assertEquals(0, m2.getRetcode());
            assertEquals("MATCHED", m2.getMatch().getStatus());

            GuildSystemProto.GuildWarReportScRsp report = netty.handleWarReport(
                    GuildSystemProto.GuildWarReportCsReq.newBuilder()
                            .setMatchId(m2.getMatch().getMatchId())
                            .setScoreSelf(12)
                            .setScoreOpponent(4)
                            .build(),
                    new EmbeddedChannel());
            assertEquals(0, report.getRetcode());
            assertEquals("SETTLED", report.getMatch().getStatus());

            GuildSystemProto.GuildWarRankScRsp rank = netty.handleWarRank(
                    GuildSystemProto.GuildWarRankCsReq.newBuilder().setTopN(10).build(),
                    new EmbeddedChannel());
            assertEquals(0, rank.getRetcode());
            assertTrue(rank.getRanksCount() >= 1);
        }

        @Test
        @DisplayName("PacketHandler 写出 GUILD_WAR_MATCH_SC_RSP")
        void packetHandlerWritesMatchRsp() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            EmbeddedChannel ch = new EmbeddedChannel();
            when(ctx.channel()).thenReturn(ch);
            handlers.onWarMatch(ctx, new GamePacket(CmdIds.GUILD_WAR_MATCH_CS_REQ, new byte[0]));
            verify(ctx).writeAndFlush(any(GamePacket.class));
        }
    }

    @Nested
    @DisplayName("家园协议业务流程")
    class HomeProtocolFlow {

        private HomeBaseService home;
        private HomeNettyService netty;
        private HomePacketHandlers handlers;

        @BeforeEach
        void setUp(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("HomeFacilityConfigs.json"), """
                    [{"facilityId":1,"name":"矿场","maxLevel":5,"staminaPerHour":10,"slotCount":2,"produceItemId":2001,"producePerHour":3}]
                    """);
            home = new HomeBaseService(new ObjectMapper(), dir.toString());
            home.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(42);
            netty = new HomeNettyService(home, resolver);
            handlers = new HomePacketHandlers(netty);
        }

        @Test
        @DisplayName("获取信息→放置基建→领取产出→互访")
        void homeLifecycle() {
            HomeSystemProto.GetHomeInfoScRsp info = netty.handleGetInfo(new EmbeddedChannel());
            assertEquals(0, info.getRetcode());
            assertEquals(42, info.getHome().getPlayerId());

            int facilityId = home.getOrCreate(42).facilities().isEmpty() ? 1 : 1;
            HomeSystemProto.HomePlaceFacilityScRsp place = netty.handlePlace(
                    HomeSystemProto.HomePlaceFacilityCsReq.newBuilder()
                            .setFacilityId(facilityId).setLevel(2).build(),
                    new EmbeddedChannel());
            assertEquals(0, place.getRetcode());
            assertEquals(2, place.getHome().getFacilitiesMap().get(facilityId));

            // 无产出时领取失败
            HomeSystemProto.HomeClaimProduceScRsp claim = netty.handleClaim(new EmbeddedChannel());
            assertEquals(5, claim.getRetcode());

            PlayerContextResolver visitor = mock(PlayerContextResolver.class);
            when(visitor.resolvePlayerId(any())).thenReturn(99);
            HomeNettyService visitorNetty = new HomeNettyService(home, visitor);
            HomeSystemProto.HomeVisitScRsp visit = visitorNetty.handleVisit(
                    HomeSystemProto.HomeVisitCsReq.newBuilder().setHostPlayerId(42).build(),
                    new EmbeddedChannel());
            assertEquals(0, visit.getRetcode());
            assertEquals(42, visit.getHostHome().getPlayerId());
        }

        @Test
        @DisplayName("PacketHandler 写出 GET_HOME_INFO_SC_RSP")
        void packetGetInfo() {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            handlers.onGetInfo(ctx, new GamePacket(CmdIds.GET_HOME_INFO_CS_REQ, new byte[0]));
            verify(ctx).writeAndFlush(any(GamePacket.class));
        }
    }

    @Nested
    @DisplayName("对话/过场协议业务流程")
    class DialogueCutsceneProtocolFlow {

        private DialogueCutsceneNettyService netty;
        private DialogueCutscenePacketHandlers handlers;

        @BeforeEach
        void setUp() {
            ObjectMapper om = new ObjectMapper();
            DialogueTreeRepository trees = new DialogueTreeRepository(om, "data");
            assertTrue(trees.reload(), "应加载 data/DialogueTrees.json");
            DialogueProgressService progress = new DialogueProgressService();
            CutsceneTriggerService cutscenes = new CutsceneTriggerService(om, "data");
            assertTrue(cutscenes.reload(), "应加载 data/CutsceneConfigs.json");
            DialogueTriggerEngine engine = new DialogueTriggerEngine(trees, progress, cutscenes);
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(7007);
            netty = new DialogueCutsceneNettyService(engine, cutscenes, resolver);
            handlers = new DialogueCutscenePacketHandlers(netty);
            assertTrue(engine.startByNpc(7007, "1001").ok());
        }

        @Test
        @DisplayName("选择分支→跳过对话→跳过/完成过场")
        void dialogueAndCutscene() {
            DialogueCutsceneProto.DialogueChooseScRsp choose = netty.handleChoose(
                    DialogueCutsceneProto.DialogueChooseCsReq.newBuilder()
                            .setTreeId("1001").setChoiceId("1").build(),
                    new EmbeddedChannel());
            assertEquals(0, choose.getRetcode());
            assertEquals("n2", choose.getNode().getNodeId());
            assertEquals("cs_intro_01", choose.getCutsceneId());

            DialogueCutsceneProto.DialogueSkipScRsp skip = netty.handleDialogueSkip(
                    DialogueCutsceneProto.DialogueSkipCsReq.newBuilder().setTreeId("1001").build(),
                    new EmbeddedChannel());
            assertEquals(0, skip.getRetcode());

            DialogueCutsceneProto.CutsceneSkipScRsp csSkip = netty.handleCutsceneSkip(
                    DialogueCutsceneProto.CutsceneSkipCsReq.newBuilder().setCutsceneId("cs_intro_01").build(),
                    new EmbeddedChannel());
            assertEquals(0, csSkip.getRetcode());
            assertTrue(csSkip.getSkipped());

            DialogueCutsceneProto.CutsceneCompleteScRsp done = netty.handleCutsceneComplete(
                    DialogueCutsceneProto.CutsceneCompleteCsReq.newBuilder().setCutsceneId("cs_intro_01").build(),
                    new EmbeddedChannel());
            assertEquals(0, done.getRetcode());
        }

        @Test
        @DisplayName("PacketHandler 写出 DIALOGUE_CHOOSE_SC_RSP")
        void packetChoose() throws Exception {
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            byte[] payload = DialogueCutsceneProto.DialogueChooseCsReq.newBuilder()
                    .setTreeId("1001").setChoiceId("1").build().toByteArray();
            handlers.onChoose(ctx, new GamePacket(CmdIds.DIALOGUE_CHOOSE_CS_REQ, payload));
            verify(ctx).writeAndFlush(any(GamePacket.class));
        }
    }

    @Nested
    @DisplayName("配置导入 dry-run 与发布指纹")
    class ConfigDryRunFlow {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("dry-run 输出 changed/unchanged/new 差异且不落盘")
        void dryRunDiffDoesNotWrite() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.setDataDir(tempDir.toString());
            ConfigFileService files = new ConfigFileService(props);
            files.writeTextAtomic("hotfix.json", "{\"v\":1}", true);

            ConfigReleaseService release = mock(ConfigReleaseService.class);
            ConfigImportService importService = new ConfigImportService(
                    files,
                    mock(ActivityImportService.class),
                    mock(GameDataRepository.class),
                    mock(cn.itcast.demo.mylunarcore.common.HotReloadCoordinator.class),
                    new ConfigPublishAuditService(),
                    release);

            Map<String, Object> result = importService.importJsonFiles(
                    Map.of("hotfix.json", "{\"v\":2}"), false, true);
            assertTrue((Boolean) result.get("ok"));
            assertTrue((Boolean) result.get("dryRun"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> diff = (List<Map<String, Object>>) result.get("diff");
            assertNotNull(diff);
            assertEquals(1, diff.size());
            assertEquals("changed", diff.get(0).get("status"));
            assertEquals("{\"v\":1}", Files.readString(tempDir.resolve("hotfix.json")));
            verify(release, never()).record(anyString(), anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("正式导入应记录 ConfigRelease 指纹")
        void importRecordsRelease() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.setDataDir(tempDir.toString());
            ConfigFileService files = new ConfigFileService(props);
            ConfigReleaseService release = mock(ConfigReleaseService.class);
            ConfigImportService importService = new ConfigImportService(
                    files,
                    mock(ActivityImportService.class),
                    mock(GameDataRepository.class),
                    mock(cn.itcast.demo.mylunarcore.common.HotReloadCoordinator.class),
                    new ConfigPublishAuditService(),
                    release);

            Map<String, Object> result = importService.importJsonFiles(
                    Map.of("hotfix.json", "{\"v\":9}"), false, false);
            assertTrue((Boolean) result.get("ok"));
            assertEquals("{\"v\":9}", Files.readString(tempDir.resolve("hotfix.json")));
            verify(release).record(eq("hotfix.json"), anyString(), anyString(), anyString(), eq("importJsonFiles"));
        }
    }

    @Nested
    @DisplayName("生产门禁与协议 CmdId")
    class ProductionGuardAndCmdIds {

        @Test
        @DisplayName("prod 缺 HMAC/白名单/开启 mock 应失败")
        void prodRejectsWeakConfig() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("spring.datasource.password", "StrongPass!99");
            env.setProperty("DB_PASSWORD", "StrongPass!99");
            env.setProperty("lunarcore.internal-api-token", "prod-token-ok");
            env.setProperty("INTERNAL_API_TOKEN", "prod-token-ok");
            env.setProperty("lunarcore.kcp-crypto.enabled", "true");
            env.setProperty("mylunarcore.iap.mock-verify", "true");
            List<String> errors = ProductionSecretsValidator.validate(env);
            assertTrue(errors.stream().anyMatch(e -> e.contains("mock-verify")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("protocol-hmac")));
            assertTrue(errors.stream().anyMatch(e -> e.contains("ip-whitelist")));
        }

        @Test
        @DisplayName("家园/对话/公会战 CmdId 成对且无冲突")
        void cmdIdsPaired() {
            assertEquals(CmdIds.GET_HOME_INFO_CS_REQ + 1, CmdIds.GET_HOME_INFO_SC_RSP);
            assertEquals(CmdIds.DIALOGUE_CHOOSE_CS_REQ + 1, CmdIds.DIALOGUE_CHOOSE_SC_RSP);
            assertEquals(CmdIds.GUILD_WAR_MATCH_CS_REQ + 1, CmdIds.GUILD_WAR_MATCH_SC_RSP);
            assertEquals(CmdIds.GUILD_WAR_RANK_CS_REQ + 1, CmdIds.GUILD_WAR_RANK_SC_RSP);
            assertTrue(CmdIds.CUTSCENE_SC_NOTIFY > CmdIds.CUTSCENE_COMPLETE_CS_REQ);
        }
    }
}
