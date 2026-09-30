package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.assist.AssistEmotionNotifyService;
import cn.itcast.demo.mylunarcore.assist.memory.AssistPlayerProfileVectorService;
import cn.itcast.demo.mylunarcore.assist.visual.VisualFeatureMatchService;
import cn.itcast.demo.mylunarcore.battle.BattleCorrectionService;
import cn.itcast.demo.mylunarcore.battle.PlayerInputBufferService;
import cn.itcast.demo.mylunarcore.common.StructuredJsonLogger;
import cn.itcast.demo.mylunarcore.hall.ReliablePrivateChatDelivery;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.social.FriendIntimacyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 稳定性/体验深化：迁移 Saga、私聊可靠投递、战斗修正、协议能力、亲密度、AI 画像等。
 */
@DisplayName("稳定性与体验深化")
class StabilityEnhancementFlowTest {

    @Test
    @DisplayName("迁移状态机合法推进与非法回滚")
    void migrateSagaAdvancesAndRollsBack() {
        PartyMigrateSagaService saga = new PartyMigrateSagaService(null, null);
        var txn = saga.begin("p1", 1001, "node-a");
        assertEquals(MigrateState.INIT, txn.state());
        txn = saga.advance(txn.txnId(), MigrateState.SERIALIZING, null);
        assertEquals(MigrateState.SERIALIZING, txn.state());
        txn = saga.advance(txn.txnId(), MigrateState.TRANSFERRING, "pfm-1");
        assertEquals(MigrateState.TRANSFERRING, txn.state());
        txn = saga.advance(txn.txnId(), MigrateState.CONFIRMED, "pfm-1");
        assertEquals(MigrateState.CONFIRMED, txn.state());
        assertTrue(txn.state().isTerminal());

        var txn2 = saga.begin("p2", 1002, "node-b");
        saga.advance(txn2.txnId(), MigrateState.SERIALIZING, null);
        var rolled = saga.advance(txn2.txnId(), MigrateState.CONFIRMED, "x");
        assertEquals(MigrateState.ROLLBACK, rolled.state());
    }

    @Test
    @DisplayName("私聊 ACK 与超时重试")
    void privateChatAckAndRetry() {
        ReliablePrivateChatDelivery d = new ReliablePrivateChatDelivery();
        var pm = d.enqueue(1, 2, "hi");
        assertTrue(d.ack(pm.msgId(), 2));
        assertFalse(d.ack(pm.msgId(), 2));
        var pm2 = d.enqueue(1, 2, "again");
        assertTrue(d.drainRetries(System.currentTimeMillis() + 10_000).stream()
                .anyMatch(x -> x.msgId() == pm2.msgId()));
    }

    @Test
    @DisplayName("战斗时间对齐与预输入缓冲扩容")
    void battleCorrectionAndInputBuffer() {
        BattleCorrectionService corr = new BattleCorrectionService(null);
        long now = 1_000_000L;
        assertEquals(now, corr.alignClientEstimatedTime(now + 200, now));
        assertEquals(now + 50, corr.alignClientEstimatedTime(now + 50, now));
        assertEquals(5, PlayerInputBufferService.MAX_BUFFER);
    }

    @Test
    @DisplayName("协议能力协商屏蔽 wire&lt;4 新命令")
    void protocolCapabilityNegotiates() {
        ProtocolCompatService compat = new ProtocolCompatService();
        var r = compat.negotiate(9, 3, 0, Integer.MAX_VALUE);
        assertTrue(r.compatible());
        assertFalse(r.enabledCmdIds().contains(CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY));
        var r4 = compat.negotiate(9, 4, 0, Integer.MAX_VALUE);
        assertTrue(r4.enabledCmdIds().contains(CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY));
        assertEquals(4, CmdIds.PROTOCOL_WIRE_VERSION);
    }

    @Test
    @DisplayName("好友亲密度升级与 AI 情感/画像/特征匹配")
    void socialAndAssistEnhancements() {
        FriendIntimacyService intimacy = new FriendIntimacyService(null);
        var snap = intimacy.add(1, 2, FriendIntimacyService.Action.BATTLE_TOGETHER);
        assertEquals(5, snap.points());
        assertTrue(intimacy.levelOf(80) >= 3);

        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.player.GameSessionManager> sessions = mock(ObjectProvider.class);
        when(sessions.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);
        AssistEmotionNotifyService emotion = new AssistEmotionNotifyService(
                new cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService(
                        new cn.itcast.demo.mylunarcore.config.LunarCoreProperties(), redis),
                sessions);
        assertEquals(AssistEmotionNotifyService.Emotion.NEGATIVE, emotion.analyze("我太难受了"));

        AssistPlayerProfileVectorService profiles = new AssistPlayerProfileVectorService();
        profiles.upsert(1, Map.of(1001, 10, 1002, 3), "clara-tingyun");
        assertFalse(profiles.personalizedHint(1).isBlank());

        VisualFeatureMatchService vlm = new VisualFeatureMatchService();
        assertTrue(vlm.catalogSize() >= 3);
        assertTrue(StructuredJsonLogger.toJson("INFO", "t", 1, 68, 3, "ok").contains("traceId"));
    }
}
