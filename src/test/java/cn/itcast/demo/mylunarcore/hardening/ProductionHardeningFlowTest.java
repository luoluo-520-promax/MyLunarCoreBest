package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.SensitiveDataMasker;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.hall.ChatService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchQueue;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.KcpSessionCryptoCodec;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.security.BizReplayGuardService;
import cn.itcast.demo.mylunarcore.tutorial.NewbieGuideService;
import cn.itcast.demo.mylunarcore.tx.LocalTxLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

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
 * 生产加固新功能与业务流程。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ProductionHardeningFlowTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("生产加固新功能与业务流程")
class ProductionHardeningFlowTest {

    @Nested
    @DisplayName("协议号段与版本")
    class ProtocolFlow {
    /**
     * 验证点：characterNoLongerCollidesWithParty。
     * <p>测试方法 {@code characterNoLongerCollidesWithParty}：
     * <ul>
     *   <li>{@code assertTrue(CmdIds.CREATE_CHARACTER_CS_REQ != CmdIds.CREATE_PARTY_CS_REQ);}</li>
     *   <li>{@code assertTrue(CmdIds.CREATE_CHARACTER_CS_REQ >= 160);}</li>
     *   <li>{@code assertEquals(3, CmdIds.PROTOCOL_WIRE_VERSION);}</li>
     * </ul>
     */
        @Test
        void characterNoLongerCollidesWithParty() {
            assertTrue(CmdIds.CREATE_CHARACTER_CS_REQ != CmdIds.CREATE_PARTY_CS_REQ);
            assertTrue(CmdIds.CREATE_CHARACTER_CS_REQ >= 160);
            assertEquals(4, CmdIds.PROTOCOL_WIRE_VERSION);
        }

    /**
     * 验证点：protocolCompatRejectsV1。
     * <p>测试方法 {@code protocolCompatRejectsV1}：
     * <ul>
     *   <li>{@code assertFalse(svc.isCompatible(1));}</li>
     *   <li>{@code assertTrue(svc.isCompatible(2));}</li>
     *   <li>{@code assertEquals(CmdIds.CREATE_CHARACTER_CS_REQ, svc.legacyCharacterCmdRemap().get(120));}</li>
     * </ul>
     */
        @Test
        void protocolCompatRejectsV1() {
            ProtocolCompatService svc = new ProtocolCompatService();
            assertFalse(svc.isCompatible(1));
            assertTrue(svc.isCompatible(2));
            assertEquals(CmdIds.CREATE_CHARACTER_CS_REQ, svc.legacyCharacterCmdRemap().get(120));
        }
    }

    @Nested
    @DisplayName("战斗优雅中止")
    class BattleShutdownFlow {
    /**
     * 验证点：abortAllEndsActiveBattlesAndClearsIndex。
     * <p>测试方法 {@code abortAllEndsActiveBattlesAndClearsIndex}：
     * <ul>
     *   <li>{@code assertEquals(2, manager.activeUnendedCount());}</li>
     *   <li>{@code assertEquals(2, aborted);}</li>
     *   <li>{@code assertTrue(a.isEnded());}</li>
     *   <li>{@code assertTrue(b.isEnded());}</li>
     *   <li>{@code assertNull(manager.get(1L));}</li>
     *   <li>{@code assertEquals(0, manager.activeUnendedCount());}</li>
     * </ul>
     */
        @Test
        void abortAllEndsActiveBattlesAndClearsIndex() {
            BattleManager manager = new BattleManager(org.mockito.Mockito.mock(BattleSnapshotService.class));
            BattleContext a = BattleContext.createNew(1L, 10, 1, 100, 1L, List.of());
            BattleContext b = BattleContext.createNew(2L, 20, 1, 100, 1L, List.of());
            manager.put(a);
            manager.put(b);
            assertEquals(2, manager.activeUnendedCount());

            int aborted = manager.abortAllForShutdown();
            assertEquals(2, aborted);
            assertTrue(a.isEnded());
            assertTrue(b.isEnded());
            assertNull(manager.get(1L));
            assertEquals(0, manager.activeUnendedCount());
        }
    }

    @Nested
    @DisplayName("匹配：超时 / 分段 / 成功率")
    class MatchFlow {
    /**
     * 验证点：timedOutEntriesAreEvicted。
     * <p>测试方法 {@code timedOutEntriesAreEvicted}：
     * <ul>
     *   <li>{@code assertEquals(1, timedOut.size());}</li>
     *   <li>{@code assertEquals(1, timedOut.get(0).playerId());}</li>
     *   <li>{@code assertEquals(1, queue.queueDepth());}</li>
     * </ul>
     */
        @Test
        void timedOutEntriesAreEvicted() {
            MatchQueue queue = new MatchQueue();
            long now = System.currentTimeMillis();
            queue.enqueue(new MatchQueue.QueueEntry(1, 1, 10, 100, now - 120_000), 10, 500);
            queue.enqueue(new MatchQueue.QueueEntry(2, 1, 11, 120, now), 10, 500);
            List<MatchQueue.QueueEntry> timedOut = queue.evictTimedOut(now, 60_000);
            assertEquals(1, timedOut.size());
            assertEquals(1, timedOut.get(0).playerId());
            assertEquals(1, queue.queueDepth());
        }

    /**
     * 验证点：expandSegmentCanMatchAcrossBands。
     * <p>测试方法 {@code expandSegmentCanMatchAcrossBands}：
     * <ul>
     *   <li>{@code assertTrue(sameBand.isEmpty());}</li>
     *   <li>{@code assertEquals(2, expanded.size());}</li>
     * </ul>
     */
        @Test
        void expandSegmentCanMatchAcrossBands() {
            MatchQueue queue = new MatchQueue();
            long now = System.currentTimeMillis();
            queue.enqueue(new MatchQueue.QueueEntry(1, 1, 10, 100, now), 10, 500);
            queue.enqueue(new MatchQueue.QueueEntry(2, 1, 50, 9000, now), 10, 500);
            List<MatchQueue.QueueEntry> sameBand = queue.pollMatch(1, 2, false, 10, 500, false);
            assertTrue(sameBand.isEmpty());
            List<MatchQueue.QueueEntry> expanded = queue.pollMatch(1, 2, false, 10, 500, true);
            assertEquals(2, expanded.size());
        }

    /**
     * 验证点：matchmakingRecordsSuccessMetric。
     * <p>测试方法 {@code matchmakingRecordsSuccessMetric}：
     * <ul>
     *   <li>{@code assertTrue(service.joinQueue(1001, 1, 10, 100).success());}</li>
     *   <li>{@code assertTrue(service.joinQueue(1002, 1, 11, 120).success());}</li>
     *   <li>{@code assertTrue(metrics.matchSuccessRate() > 0);}</li>
     * </ul>
     */
        @Test
        void matchmakingRecordsSuccessMetric() {
            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            MatchmakingService service = new MatchmakingService(
                    new RoomService(), mock(GameSessionManager.class),
                    new LunarCoreProperties(), metrics);
            assertTrue(service.joinQueue(1001, 1, 10, 100).success());
            assertTrue(service.joinQueue(1002, 1, 11, 120).success());
            service.batchMatchTick();
            assertTrue(metrics.matchSuccessRate() > 0);
        }
    }

    @Nested
    @DisplayName("组队与私聊编解码")
    class SocialFlow {
    /**
     * 验证点：partyCreateInviteLeaveDisband。
     * <p>测试方法 {@code partyCreateInviteLeaveDisband}：
     * <ul>
     *   <li>{@code when(store.available()).thenReturn(false);}</li>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, party.create(1L).code());}</li>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, party.invite(1L, 2L).code());}</li>
     *   <li>{@code assertEquals(2, party.getByUid(1L).memberUids().size());}</li>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, party.leave(2L).code());}</li>
     *   <li>{@code assertEquals(1, party.getByUid(1L).memberUids().size());}</li>
     * </ul>
     */
        @Test
        void partyCreateInviteLeaveDisband() {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);
            when(store.saveIfNewer(any())).thenReturn(true);
            PartyService party = new PartyService(store);
            assertEquals(PartyService.PartyResultCode.OK, party.create(1L).code());
            assertEquals(PartyService.PartyResultCode.OK, party.invite(1L, 2L).code());
            assertEquals(2, party.getByUid(1L).memberUids().size());
            assertEquals(PartyService.PartyResultCode.OK, party.leave(2L).code());
            assertEquals(1, party.getByUid(1L).memberUids().size());
            assertEquals(PartyService.PartyResultCode.OK, party.disband(1L).code());
            assertNull(party.getByUid(1L));
        }

    /**
     * 验证点：privateChatRoundTripEncode。
     * <p>测试方法 {@code privateChatRoundTripEncode}：
     * <ul>
     *   <li>{@code assertNotNull(decoded);}</li>
     *   <li>{@code assertEquals(1, decoded.channelType());}</li>
     *   <li>{@code assertEquals(20, decoded.targetId());}</li>
     *   <li>{@code assertEquals("hello private", decoded.content());}</li>
     * </ul>
     */
        @Test
        void privateChatRoundTripEncode() {
            ChatService.ChatRecord original = new ChatService.ChatRecord(
                    1, 10, "A", 20, "hello private", 123L);
            ChatService.ChatRecord decoded = ChatService.decode(ChatService.encode(original));
            assertNotNull(decoded);
            assertEquals(1, decoded.channelType());
            assertEquals(20, decoded.targetId());
            assertEquals("hello private", decoded.content());
        }
    }

    @Nested
    @DisplayName("事务日志 / 防重放 / IAP")
    class ConsistencySecurityFlow {
    /**
     * 验证点：localTxLogBeginCommitted。
     * <p>测试方法 {@code localTxLogBeginCommitted}：
     * <ul>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);}</li>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);}</li>
     *   <li>{@code assertNotNull(txId);}</li>
     *   <li>{@code assertFalse(txId.isBlank());}</li>
     *   <li>{@code verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());}</li>
     *   <li>{@code verify(jdbc).update(anyString(), any(), any(), any());}</li>
     * </ul>
     */
        @Test
        void localTxLogBeginCommitted() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
            LocalTxLogService svc = new LocalTxLogService(jdbc);
            String txId = svc.begin("gacha", 7, "{times:1}");
            assertNotNull(txId);
            assertFalse(txId.isBlank());
            svc.markCommitted(txId);
            verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any());
            verify(jdbc).update(anyString(), any(), any(), any());
        }

    /**
     * 验证点：replayGuardRejectsDuplicate。
     * <p>测试方法 {@code replayGuardRejectsDuplicate}：
     * <ul>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any()))}</li>
     *   <li>{@code assertTrue(guard.tryAcquire("iap", 1, "tx-1"));}</li>
     *   <li>{@code assertFalse(guard.tryAcquire("iap", 1, "tx-1"));}</li>
     * </ul>
     */
        @Test
        void replayGuardRejectsDuplicate() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            when(jdbc.update(anyString(), any(), any(), any(), any()))
                    .thenReturn(1)
                    .thenThrow(new DuplicateKeyException("dup"));
            BizReplayGuardService guard = new BizReplayGuardService(jdbc, metrics);
            assertTrue(guard.tryAcquire("iap", 1, "tx-1"));
            assertFalse(guard.tryAcquire("iap", 1, "tx-1"));
        }
    }

    @Nested
    @DisplayName("成就与新手引导")
    class ContentFlow {
    /**
     * 验证点：achievementListAndClaim。
     * <p>测试方法 {@code achievementListAndClaim}：
     * <ul>
     *   <li>{@code when(jdbc.queryForList(anyString(), eq(77))).thenReturn(List.of(}</li>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);}</li>
     *   <li>{@code assertFalse(list.isEmpty());}</li>
     *   <li>{@code assertTrue(svc.claim(77, "gacha_10"));}</li>
     * </ul>
     */
        @Test
        void achievementListAndClaim() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), eq(77))).thenReturn(List.of(
                    Map.of("achievement_id", "gacha_10", "progress", 10, "claimed", 0)
            ));
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
            AchievementService svc = new AchievementService(jdbc);
            List<Map<String, Object>> list = svc.listForPlayer(77);
            assertFalse(list.isEmpty());
            assertTrue(svc.claim(77, "gacha_10"));
        }

    /**
     * 验证点：newbieGuideLoadsAndAdvances。
     * <p>测试方法 {@code newbieGuideLoadsAndAdvances}：
     * <ul>
     *   <li>{@code when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of());}</li>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0, 1);}</li>
     *   <li>{@code assertFalse(svc.steps().isEmpty());}</li>
     *   <li>{@code assertNotNull(progress.get("currentStepId"));}</li>
     *   <li>{@code assertTrue(svc.advance(1, first));}</li>
     *   <li>{@code assertNotNull(svc.currentAssistPrompt(1));}</li>
     * </ul>
     */
        @Test
        void newbieGuideLoadsAndAdvances() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of());
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0, 1);
            NewbieGuideService svc = new NewbieGuideService(jdbc, new DefaultResourceLoader());
            svc.load();
            assertFalse(svc.steps().isEmpty());
            Map<String, Object> progress = svc.progress(1);
            assertNotNull(progress.get("currentStepId"));
            String first = svc.steps().get(0).id();
            assertTrue(svc.advance(1, first));
            assertNotNull(svc.currentAssistPrompt(1));
        }
    }

    @Nested
    @DisplayName("日志脱敏与 KCP 加密")
    class ObservabilitySecurityFlow {
    /**
     * 验证点：maskerHidesSecrets。
     * <p>测试方法 {@code maskerHidesSecrets}：
     * <ul>
     *   <li>{@code assertFalse(masked.contains("abc"));}</li>
     *   <li>{@code assertFalse(masked.contains("xyz"));}</li>
     *   <li>{@code assertFalse(masked.contains("TX1"));}</li>
     * </ul>
     */
        @Test
        void maskerHidesSecrets() {
            String masked = SensitiveDataMasker.mask("password=abc token=xyz channel_tx_id=TX1");
            assertFalse(masked.contains("abc"));
            assertFalse(masked.contains("xyz"));
            assertFalse(masked.contains("TX1"));
        }

    /**
     * 验证点：kcpCryptoRoundTripWhenEnabled。
     * <p>测试方法 {@code kcpCryptoRoundTripWhenEnabled}：
     * <ul>
     *   <li>{@code assertTrue(ch.writeOutbound(plain));}</li>
     *   <li>{@code assertNotNull(encrypted);}</li>
     *   <li>{@code assertTrue(encrypted.readableBytes() > 5);}</li>
     *   <li>{@code assertTrue(ch.writeInbound(encrypted.retain()));}</li>
     *   <li>{@code assertNotNull(decrypted);}</li>
     *   <li>{@code assertEquals(5, out.length);}</li>
     * </ul>
     */
        @Test
        void kcpCryptoRoundTripWhenEnabled() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getKcpCrypto().setEnabled(true);
            KcpSessionCryptoCodec codec = new KcpSessionCryptoCodec(props);
            EmbeddedChannel ch = new EmbeddedChannel(codec);
            byte[] key = new byte[16];
            for (int i = 0; i < 16; i++) {
                key[i] = (byte) i;
            }
            ch.attr(KcpSessionCryptoCodec.SESSION_KEY).set(key);

            ByteBuf plain = Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5});
            assertTrue(ch.writeOutbound(plain));
            ByteBuf encrypted = ch.readOutbound();
            assertNotNull(encrypted);
            assertTrue(encrypted.readableBytes() > 5);

            assertTrue(ch.writeInbound(encrypted.retain()));
            ByteBuf decrypted = ch.readInbound();
            assertNotNull(decrypted);
            byte[] out = new byte[decrypted.readableBytes()];
            decrypted.readBytes(out);
            assertEquals(5, out.length);
            assertEquals(1, out[0]);
            assertEquals(5, out[4]);
            ch.finishAndReleaseAll();
        }

    /**
     * 验证点：analyticsPublisherDoesNotThrow。
     * <p>测试方法 {@code analyticsPublisherDoesNotThrow}：
     * 按用例准备数据后断言返回值或协作对象调用是否符合预期。
     */
        @Test
        void analyticsPublisherDoesNotThrow() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAnalytics().setEnabled(true);
            AnalyticsEventPublisher pub = new AnalyticsEventPublisher(props);
            pub.login(1, true);
            pub.gacha(1, 2, 10);
            pub.battleResult(1, true, 99L);
            pub.iapPaid(1, "o1", 600);
        }
    }
}
