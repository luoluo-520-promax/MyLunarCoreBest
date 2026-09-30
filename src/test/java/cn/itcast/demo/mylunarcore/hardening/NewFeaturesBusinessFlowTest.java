package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityTypeCatalog;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.config.ProductionSecretsValidator;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.guild.GuildRaidScheduler;
import cn.itcast.demo.mylunarcore.guild.InMemoryGuildRaidScheduler;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.NetTraceContext;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.net.ProtocolHmacSigner;
import cn.itcast.demo.mylunarcore.net.kcp.AdaptiveKcpRetransmitAlgo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 针对本轮新功能 / 新业务流程的回归总测。
 */
@DisplayName("新功能与业务流程回归")
class NewFeaturesBusinessFlowTest {

    @Test
    @DisplayName("公会 CmdId：基础 970–989，扩展 Notify 允许 1100+，且唯一")
    void guildCmdIdsInReservedBandAndUnique() throws Exception {
        Set<Integer> guildCmds = new HashSet<>();
        for (Field field : CmdIds.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || field.getType() != int.class) {
                continue;
            }
            if (!field.getName().contains("GUILD")) {
                continue;
            }
            int value = field.getInt(null);
            boolean inClassic = value >= 970 && value <= 989;
            boolean inExtension = value >= 1100 && value <= 1200;
            assertTrue(inClassic || inExtension, field.getName() + "=" + value);
            assertTrue(guildCmds.add(value), "duplicate guild cmd " + value);
        }
        assertTrue(guildCmds.contains(CmdIds.CREATE_GUILD_CS_REQ));
        assertTrue(guildCmds.contains(CmdIds.BUY_GUILD_SHOP_CS_REQ));
        assertTrue(guildCmds.contains(CmdIds.GUILD_WAR_MATCH_CS_REQ));
        assertTrue(guildCmds.contains(CmdIds.GUILD_WAR_RANK_SC_RSP));
        assertTrue(guildCmds.contains(CmdIds.GUILD_RAID_STATE_SC_NOTIFY));
        assertEquals(CmdIds.CREATE_GUILD_CS_REQ + 1, CmdIds.CREATE_GUILD_SC_RSP);
    }

    @Test
    @DisplayName("旧客户端 wire version 被拒绝")
    void legacyClientStillRejected() {
        ProtocolCompatService compat = new ProtocolCompatService();
        assertFalse(compat.isCompatible(1));
        assertTrue(compat.isCompatible(CmdIds.PROTOCOL_WIRE_VERSION));
    }

    @Test
    @DisplayName("prod 要求环境变量密钥与 KCP 加密")
    void prodRequiresEnvBackedSecretsAndKcp() {
        MockEnvironment weak = new MockEnvironment();
        weak.setProperty("spring.datasource.password", "StrongPass!99");
        weak.setProperty("lunarcore.internal-api-token", "prod-internal-token-ok");
        List<String> errors = ProductionSecretsValidator.validate(weak);
        assertTrue(errors.stream().anyMatch(e -> e.contains("INTERNAL_API_TOKEN")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("DB_PASSWORD")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("kcp-crypto")));
    }

    @Test
    @DisplayName("allow-plaintext 可跳过 KCP，但必须开启游戏 TCP TLS")
    void allowPlaintextSkipsKcpRequirement() {
        String strong = "Prod-Internal-Token-9f3a!SecureKey01";
        String strongDb = "S3cure-Db-Pass!Word-Length32xxxx";
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", strongDb);
        env.setProperty("DB_PASSWORD", strongDb);
        env.setProperty("lunarcore.internal-api-token", strong);
        env.setProperty("INTERNAL_API_TOKEN", strong);
        env.setProperty("lunarcore.kcp-crypto.enabled", "false");
        env.setProperty("lunarcore.kcp-crypto.allow-plaintext", "true");
        env.setProperty("lunarcore.tls.game-tcp-enabled", "true");
        env.setProperty("lunarcore.protocol-hmac.enabled", "true");
        env.setProperty("lunarcore.admin.ip-whitelist", "10.0.0.0/8");
        env.setProperty("mylunarcore.iap.mock-verify", "false");
        assertTrue(ProductionSecretsValidator.validate(env).isEmpty(),
                () -> String.join("; ", ProductionSecretsValidator.validate(env)));
    }

    @Test
    @DisplayName("活动类型目录覆盖可配置玩法模板")
    void catalogSupportsConfigurableGenerationTypes() {
        ActivityTypeCatalog catalog = new ActivityTypeCatalog(new ObjectMapper(), "data");
        catalog.reload();
        for (String type : List.of("signin", "limited_challenge", "tower", "coop", "seasonal", "exchange_shop")) {
            assertTrue(catalog.isKnown(type), "missing activity type " + type);
            assertFalse(catalog.find(type).orElseThrow().requiredFields().isEmpty());
        }
    }

    @Nested
    @DisplayName("本轮增量能力闭环")
    class IncrementalCapabilities {

        @Test
        @DisplayName("对话→分支→过场→CG 闭环")
        void dialogueToCutsceneCgLoop() {
            ObjectMapper mapper = new ObjectMapper();
            DialogueTreeRepository trees = new DialogueTreeRepository(mapper, "data");
            assertTrue(trees.reload());
            CutsceneTriggerService cutscenes = new CutsceneTriggerService(mapper, "data");
            assertTrue(cutscenes.reload());
            DialogueTriggerEngine engine = new DialogueTriggerEngine(
                    trees, new DialogueProgressService(), cutscenes);

            assertTrue(engine.startByNpc(42, "1001").ok());
            var step = engine.choose(42, "1001", "1");
            assertTrue(step.ok());
            assertEquals("cs_intro_01", step.cutsceneId());
            assertEquals(10001, step.unlockedCgId());
            assertTrue(cutscenes.trigger(42, step.cutsceneId()).ok());
        }

        @Test
        @DisplayName("家园放置与入驻闭环")
        void homePlaceAndStationLoop() {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(1, 1, 2).success());
            assertTrue(home.stationAvatar(1, 1, 888).success());
            assertEquals(888, home.getOrCreate(1).stationedAvatars().get(1));
            assertTrue(home.placeFurniture(1, 9, 1f, 0f, 1f, 0).success());
            assertTrue(home.visit(2, 1).success());
        }

        @Test
        @DisplayName("公会战匹配报分闭环")
        void guildWarMatchReportLoop() {
            org.springframework.jdbc.core.JdbcTemplate jdbc = org.mockito.Mockito.mock(
                    org.springframework.jdbc.core.JdbcTemplate.class);
            org.mockito.Mockito.when(jdbc.update(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Object[].class)))
                    .thenThrow(new RuntimeException("mem"));
            org.mockito.Mockito.when(jdbc.query(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.ResultSetExtractor.class),
                    org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new RuntimeException("mem"));
            org.mockito.Mockito.when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(Object[].class)))
                    .thenThrow(new RuntimeException("mem"));
            cn.itcast.demo.mylunarcore.guild.GuildService guild =
                    org.mockito.Mockito.mock(cn.itcast.demo.mylunarcore.guild.GuildService.class);
            org.mockito.Mockito.when(guild.findGuildIdByPlayer(1)).thenReturn(10L);
            org.mockito.Mockito.when(guild.findGuildIdByPlayer(2)).thenReturn(20L);
            cn.itcast.demo.mylunarcore.guild.GuildWarService war =
                    new cn.itcast.demo.mylunarcore.guild.GuildWarService(jdbc, guild);
            assertTrue(war.requestMatch(1).success());
            var m = war.requestMatch(2);
            assertTrue(m.success());
            assertEquals("MATCHED", m.match().status());
            assertTrue(war.reportBattleResult(1, m.match().matchId(), 9, 1).success());
        }

        @Test
        @DisplayName("公会 Raid 排期闭环")
        void guildRaidScheduleLoop() {
            GuildRaidScheduler scheduler = new InMemoryGuildRaidScheduler();
            var slot = scheduler.schedule(new GuildRaidScheduler.ScheduleRequest(
                    7L, "guild_raid", Instant.now().plusSeconds(7200))).orElseThrow();
            assertEquals(1, scheduler.listActive(7L).size());
            assertTrue(scheduler.cancel(slot.raidId(), 7L));
        }

        @Test
        @DisplayName("战斗快照保存与按玩家恢复")
        void battleSnapshotRecoverByPlayer() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getBattleSnapshot().setEnabled(true);
            props.getRedis().setEnabled(false);
            @SuppressWarnings("unchecked")
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            BattleSnapshotService snaps = new BattleSnapshotService(new ObjectMapper(), props, redis);
            BattleManager manager = new BattleManager(snaps);
            BattleContext ctx = BattleContext.createNew(55L, 77, 1, 100, 1L, List.of());
            manager.put(ctx);
            assertTrue(snaps.loadByPlayer(77).isPresent());
            assertEquals(55L, snaps.loadByPlayer(77).orElseThrow().battleId());
        }

        @Test
        @DisplayName("配置灰度按尾号命中 + HMAC/自适应 KCP 可启用")
        void grayHmacAndAdaptiveKcpGates() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getConfigGray().setEnabled(true);
            props.getConfigGray().setUidTails("7");
            ConfigGrayRelease gray = new ConfigGrayRelease(props);
            assertTrue(gray.inGray(17L, "s1"));
            assertFalse(gray.inGray(18L, "s1"));

            props.getProtocolHmac().setEnabled(true);
            assertTrue(ProtocolHmacSigner.isEnabled(props));
            byte[] mac = ProtocolHmacSigner.sign("k".repeat(16).getBytes(StandardCharsets.UTF_8),
                    100, 1L, "body".getBytes(StandardCharsets.UTF_8));
            assertEquals(ProtocolHmacSigner.HMAC_LEN, mac.length);

            var algo = new AdaptiveKcpRetransmitAlgo(20, 250, 256, true, 50, 200);
            assertTrue(algo.nextSendIntervalMs(800, 100) >= 10);
            assertEquals(cn.itcast.demo.mylunarcore.net.kcp.KcpCongestionProfile.strong().intervalMs(),
                    algo.resolveProfile(30).intervalMs());
            assertTrue(algo.resolveProfile(250).nodelay());
        }

        @Test
        @DisplayName("NetTrace 每包生成新 TraceId 且写入 Channel")
        void netTraceGeneratesPerPacketId() {
            EmbeddedChannel ch = new EmbeddedChannel();
            var p1 = new cn.itcast.demo.mylunarcore.net.GamePacket(1, new byte[0]);
            var p2 = new cn.itcast.demo.mylunarcore.net.GamePacket(2, new byte[0]);
            String t1 = NetTraceContext.next(ch, p1);
            String t2 = NetTraceContext.next(ch, p2);
            assertFalse(t1.isBlank());
            assertFalse(t2.isBlank());
            assertFalse(t1.equals(t2));
            assertEquals(t2, ch.attr(NetTraceContext.TRACE_ID).get());
            assertEquals("X-Trace-Id", NetTraceContext.HTTP_HEADER);
        }
    }
}
