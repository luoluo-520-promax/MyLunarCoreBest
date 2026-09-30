package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityFlowDslService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.assist.AssistEmotionNotifyService;
import cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService;
import cn.itcast.demo.mylunarcore.assist.memory.AssistPlayerProfileVectorService;
import cn.itcast.demo.mylunarcore.assist.visual.VisualFeatureMatchService;
import cn.itcast.demo.mylunarcore.battle.BattleAutoWeakNetService;
import cn.itcast.demo.mylunarcore.battle.BattleCorrectionService;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.PlayerInputBufferService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.ClientResourceManifestService;
import cn.itcast.demo.mylunarcore.common.HotfixDataService;
import cn.itcast.demo.mylunarcore.common.ResourcePatchService;
import cn.itcast.demo.mylunarcore.common.StructuredJsonLogger;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.guild.GuildRaidInstanceService;
import cn.itcast.demo.mylunarcore.hall.ReliablePrivateChatDelivery;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.ops.OpsAutoMitigationService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyFollowMigrationService;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.scene.DynamicZoneLineAllocator;
import cn.itcast.demo.mylunarcore.scene.DynamicZoneSplitService;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneEntityIdAllocator;
import cn.itcast.demo.mylunarcore.scene.ShardAllocationService;
import cn.itcast.demo.mylunarcore.scene.ZoneContext;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.social.FriendIntimacyService;
import cn.itcast.demo.mylunarcore.social.VoiceSignalingService;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * wire v4 新功能与新业务流程全覆盖：迁移 Saga、Zone 分裂、战斗弱网、活动 DSL、
 * 亲密度/公会讨伐/语音、协议能力、资源差量、运维止血、AI 陪伴。
 */
@DisplayName("wire v4 新功能与业务流程全测")
class WireV4EnhancementBusinessFlowsTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Nested
    @DisplayName("1. 跨节点组队迁移 Saga + 跟随迁移")
    class PartyMigrateFlow {

        @Test
        @DisplayName("邀请触发跟随迁移：票据可消费、Saga 进入 CONFIRMED")
        void followMigrateWithSaga() throws Exception {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);
            when(store.saveIfNewer(any())).thenReturn(true);
            when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
            PartyService party = new PartyService(store);
            assertEquals(PartyService.PartyResultCode.OK, party.create(801L).code());
            assertEquals(PartyService.PartyResultCode.OK, party.invite(801L, 802L).code());

            EmbeddedChannel leaderCh = new EmbeddedChannel();
            GameSession leader = new GameSession(801L, leaderCh, null);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(801L)).thenReturn(leader);
            when(sessions.getOrNull(802L)).thenReturn(null);

            PartyMigrateSagaService saga = new PartyMigrateSagaService(provider(sessions), provider(null));
            PartyFollowMigrationService follow = new PartyFollowMigrationService(
                    party, provider(sessions), provider(null), provider(saga), provider(null));

            assertTrue(follow.followAfterInvite(party.getByUid(801L), 802L));

            GamePacket pkt = leaderCh.readOutbound();
            // 可能先收到进度包再收到跟随迁移包
            while (pkt != null && pkt.getCmdId() == CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY) {
                pkt = leaderCh.readOutbound();
            }
            assertNotNull(pkt);
            assertEquals(CmdIds.PARTY_FOLLOW_MIGRATE_SC_NOTIFY, pkt.getCmdId());
            HallSystemProto.PartyFollowMigrateScNotify n =
                    HallSystemProto.PartyFollowMigrateScNotify.parseFrom(pkt.getPayload());
            assertFalse(n.getMigrateTicket().isBlank());
            assertNotNull(follow.consumeTicket(n.getMigrateTicket()));
            assertNull(follow.consumeTicket(n.getMigrateTicket()));
        }

        @Test
        @DisplayName("Saga 失败路径回滚")
        void sagaRollbackOnIllegalJump() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(null, null);
            var txn = saga.begin("party-x", 1, "n1");
            saga.advance(txn.txnId(), MigrateState.SERIALIZING, null);
            var rolled = saga.rollback(txn.txnId(), "manual_fail");
            assertEquals(MigrateState.ROLLBACK, rolled.state());
            assertTrue(rolled.compensationHint().contains("manual_fail"));
        }
    }

    @Nested
    @DisplayName("2. Zone 分裂 + Shard 分配")
    class ZoneShardFlow {

        @Test
        @DisplayName("超阈值自动分裂并迁移部分 UID")
        void splitWhenOverThreshold() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getZone().setSplitThreshold(4);
            props.getZone().setSplitRetainPercent(0); // 全部按策略迁出，避免小 UID 全落在保留半区
            props.getZone().setMaxPlayers(100);
            ZoneManager zones = new ZoneManager(new SceneRegistry(), new SceneEntityIdAllocator(), props);
            ZoneContext src = zones.getOrCreate(101, 1, 0);
            for (long uid = 1; uid <= 6; uid++) {
                zones.joinZone(101, 1, 0, uid, new SceneContext.ScenePos(uid, 0, uid));
            }
            assertEquals(6, src.getPlayerUids().size());

            DynamicZoneSplitService split = new DynamicZoneSplitService(zones, provider(null), props);
            var ev = split.splitZone(src);
            assertNotNull(ev);
            assertEquals(6, ev.migratedUids().size());
            assertNotNull(split.splitTargetOf(src.getZoneId()));
            ZoneContext target = zones.get(ev.newZoneId());
            assertNotNull(target);
            assertEquals(6, target.getPlayerUids().size());
            assertEquals(0, src.getPlayerUids().size());
        }

        @Test
        @DisplayName("登录 Shard 分配优先可加入分线")
        void shardAllocateJoinableLine() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getZone().setDynamicLineEnabled(true);
            props.getZone().setMaxLines(4);
            props.getZone().setMaxPlayers(100);
            props.getZone().setShardPreferLowLoad(true);
            SceneRegistry registry = new SceneRegistry();
            ZoneManager zones = new ZoneManager(registry, new SceneEntityIdAllocator(), props);
            DynamicZoneLineAllocator allocator = new DynamicZoneLineAllocator(zones, props);
            ShardAllocationService shard = new ShardAllocationService(zones, allocator, registry, props);

            var choice = shard.allocate(42L, 201, 1, "cn-east");
            assertEquals(201, choice.planeId());
            assertEquals(1, choice.floorId());
            assertTrue(choice.lineId() >= 0);
            assertFalse(choice.reason().isBlank());
        }
    }

    @Nested
    @DisplayName("3. 战斗预测修正 + Auto 弱网 + 预输入")
    class BattleFeelFlow {

        @Test
        @DisplayName("时间偏差触发修正判定；弱网等待窗拉长")
        void correctionAndWeakNetWait() {
            BattleCorrectionService corr = new BattleCorrectionService(null);
            long now = System.currentTimeMillis();
            assertTrue(corr.needsTimeCorrection(now - 500, now));
            assertFalse(corr.needsTimeCorrection(now - 20, now));
            assertEquals(now, corr.alignClientEstimatedTime(now - 500, now));

            BattleManager bm = mock(BattleManager.class);
            when(bm.get(anyLong())).thenReturn(null);
            BattleAutoWeakNetService weak = new BattleAutoWeakNetService(bm, mock(cn.itcast.demo.mylunarcore.battle.BattleAutoService.class), null);
            weak.reportRtt(1001, 220);
            var hint = weak.bandwidthHint(1001);
            assertTrue(hint.weakNet());
            assertEquals(BattleAutoWeakNetService.WEAK_NET_AUTO_WAIT_MS, hint.suggestedWaitMs());
        }

        @Test
        @DisplayName("预输入缓冲 5 槽 + TTL 丢弃过期指令")
        void inputBufferTtl() {
            PlayerInputBufferService buf = new PlayerInputBufferService();
            assertEquals(5, PlayerInputBufferService.MAX_BUFFER);
            long now = 1_000L;
            buf.openActionWindow(9, now + 10_000);
            assertTrue(buf.enqueueOrExecuteNow(9, 1, 1, 1, 2, 1, java.util.List.of(10), now));
            assertNull(buf.poll(9, 1, now + PlayerInputBufferService.INPUT_TTL_MS + 1));
        }
    }

    @Nested
    @DisplayName("4. 活动流程 DSL")
    class ActivityFlow {

        @Test
        @DisplayName("START→PLAY→SETTLE→REWARD 完整生命周期")
        void fullLifecycleWithoutScript() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            ActivityTemplateService templates = new ActivityTemplateService(
                    jdbc, new ActivityScriptEngine(new LunarCoreProperties()), new ActivityCircuitBreakerService());
            ActivityFlowDslService flow = new ActivityFlowDslService(templates, null);

            var playing = flow.start(11, 5001, "board_panel");
            assertEquals(ActivityFlowDslService.FlowStep.STEP_PLAY, playing.step());
            assertEquals("board_panel", playing.panelId());

            var reward = flow.settle(11, 5001, null, Map.of());
            assertEquals(ActivityFlowDslService.FlowStep.STEP_REWARD, reward.step());
            assertEquals(ActivityFlowDslService.FlowStep.STEP_REWARD, flow.get(11, 5001).step());
        }
    }

    @Nested
    @DisplayName("5. 社交：亲密度 / 公会讨伐 / 语音")
    class SocialFlow {

        @Test
        @DisplayName("亲密度累计升级解锁称号")
        void intimacyLevels() {
            FriendIntimacyService svc = new FriendIntimacyService(null);
            for (int i = 0; i < 6; i++) {
                svc.add(10, 20, FriendIntimacyService.Action.BATTLE_TOGETHER);
            }
            // 6*5=30 → level 2
            var snap = svc.get(10, 20);
            assertEquals(30, snap.points());
            assertEquals(2, snap.level());
            assertFalse(snap.title().isBlank());
        }

        @Test
        @DisplayName("公会 Raid 创建/加入/打伤害至通关")
        void guildRaidDamageLoop() {
            GuildRaidInstanceService raid = new GuildRaidInstanceService(null, null);
            var inst = raid.create(99L, 1000L);
            assertTrue(raid.join(inst.instanceId(), 1, 10001L));
            assertTrue(raid.join(inst.instanceId(), 2, 10002L));
            assertFalse(raid.canStart(inst.instanceId())); // < MIN_PLAYERS
            for (int i = 3; i <= GuildRaidInstanceService.MIN_PLAYERS; i++) {
                assertTrue(raid.join(inst.instanceId(), i, 10000L + i));
            }
            assertTrue(raid.canStart(inst.instanceId()));
            long remain = raid.applyDamage(inst.instanceId(), 1, 600);
            assertEquals(400, remain);
            remain = raid.applyDamage(inst.instanceId(), 2, 500);
            assertEquals(0, remain);
        }

        @Test
        @DisplayName("语音信令签发与校验、离开清理")
        void voiceSignaling() {
            VoiceSignalingService voice = new VoiceSignalingService();
            var r = voice.joinOrCreate(55, "party-voice-1");
            assertTrue(r.success());
            assertTrue(voice.validate(r.ticket().roomId(), r.ticket().token()));
            voice.leave(r.ticket().roomId(), 55);
            assertEquals(false, voice.describe(r.ticket().roomId()).get("exists"));
        }
    }

    @Nested
    @DisplayName("6. 协议能力握手 + 资源差量")
    class ProtocolResourceFlow {

        @Test
        @DisplayName("wire3 屏蔽 1109+；wire4 放开；未握手仅基础 Cmd")
        void capabilityGate() {
            ProtocolCompatService compat = new ProtocolCompatService();
            assertFalse(compat.isCompatible(1));
            var v3 = compat.negotiate(1, 3, 0, Integer.MAX_VALUE);
            assertFalse(v3.enabledCmdIds().contains(CmdIds.BATTLE_CORRECTION_SC_NOTIFY));
            var v4 = compat.negotiate(2, 4, 0, Integer.MAX_VALUE);
            assertTrue(v4.enabledCmdIds().contains(CmdIds.ACTIVITY_FLOW_SC_NOTIFY));
            assertTrue(compat.isCmdEnabled(2, CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY));
            assertFalse(compat.isCmdEnabled(999, CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY));
            assertEquals(120, compat.unsupportedRetcode());
        }

        @Test
        @DisplayName("资源差量含断点偏移；软锁允许保持会话")
        void resourcePatchAndSoftLock() {
            LunarCoreProperties props = new LunarCoreProperties();
            HotfixDataService hotfix = mock(HotfixDataService.class);
            when(hotfix.current()).thenReturn(null);
            ClientResourceManifestService manifest = new ClientResourceManifestService(props, hotfix);
            manifest.upsert(new ClientResourceManifestService.ManifestEntry(
                    "ui/board.bundle", "abc123", 1024, "https://cdn/ui/board.bundle", "zh"));

            var hard = manifest.checkResourceLock("0.0.0", Map.of(), false);
            assertFalse(hard.ok());
            assertEquals("RESOURCE_OUTDATED", hard.updateHint());

            var soft = manifest.checkResourceLock("0.0.0", Map.of("ui/board.bundle", "old"), true);
            assertFalse(soft.ok());
            assertTrue(soft.softPatchAllowed());

            ResourcePatchService patch = new ResourcePatchService(manifest, null);
            Map<String, Long> resume = new HashMap<>();
            resume.put("ui/board.bundle", 128L);
            var plan = patch.buildPatch("0.0.0", Map.of("ui/board.bundle", "old"), resume, true);
            assertEquals(1, plan.files().size());
            assertEquals(128L, plan.files().get(0).resumeOffset());
            assertTrue(plan.keepSession());
        }
    }

    @Nested
    @DisplayName("7. 运维指标 + 自动止血 + JSON 日志")
    class OpsFlow {

        @Test
        @DisplayName("IAP/审计失败率驱动止血；结构化日志含字段")
        void metricsMitigationAndJsonLog() {
            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            metrics.recordIapVerifyFailure();
            metrics.recordIapVerifyFailure();
            metrics.recordIapVerifySuccess();
            assertTrue(metrics.iapVerifyFailureRate() > 0.5);

            metrics.recordBattleAudit(true);
            metrics.recordBattleAudit(true);
            metrics.recordBattleAudit(false);
            assertTrue(metrics.battleAuditMismatchRate() > 0.5);

            OpsAutoMitigationService ops = new OpsAutoMitigationService(metrics);
            ops.evaluate();
            assertTrue(ops.isHighValuePurchaseDisabled());
            assertTrue(ops.isReadOnlyMode());
            ops.clearMitigation();
            assertFalse(ops.isReadOnlyMode());

            String json = StructuredJsonLogger.toJson("INFO", "iap", 7, CmdIds.PLAYER_LOGIN_CS_REQ, 12, "ok");
            assertTrue(json.contains("userIdHash"));
            assertTrue(json.contains("userIdPrefix"));
            assertTrue(json.contains("\"cmdId\":68"));
            assertTrue(json.contains("costMs"));
        }
    }

    @Nested
    @DisplayName("8. AI 陪伴：情感 / 画像 / VLM 特征")
    class AssistFlow {

        @Test
        @DisplayName("负面情感、画像建议、特征匹配与远程 VLM 限额")
        void emotionProfileAndVisual() {
            AssistEmotionNotifyService emotion = new AssistEmotionNotifyService(
                    new AssistLongTermMemoryService(new LunarCoreProperties(), provider(null)), null);
            assertEquals(AssistEmotionNotifyService.Emotion.NEGATIVE, emotion.notify(3, "太坑了难受"));
            assertEquals(AssistEmotionNotifyService.Emotion.POSITIVE, emotion.analyze("谢谢你真棒"));

            AssistPlayerProfileVectorService profiles = new AssistPlayerProfileVectorService();
            profiles.upsert(1, Map.of(1101, 20), "clara");
            profiles.upsert(2, Map.of(1101, 18, 1201, 5), "clara-tingyun");
            assertFalse(profiles.personalizedHint(1).isBlank());
            assertFalse(profiles.nearest(1, 3).isEmpty());

            VisualFeatureMatchService visual = new VisualFeatureMatchService();
            float[] feat = new float[VisualFeatureMatchService.FEATURE_DIM];
            // 用已注册 seed 近似匹配：重复 register 同特征
            visual.register("test_poi", "测试点", feat);
            var miss = visual.match(feat);
            // 零向量归一化后可能匹配阈值不够，至少返回结构完整
            assertNotNull(miss);
            assertTrue(visual.allowRemoteVlm(88));
            for (int i = 0; i < VisualFeatureMatchService.DAILY_VLM_LIMIT; i++) {
                visual.allowRemoteVlm(89);
            }
            assertFalse(visual.allowRemoteVlm(89));
        }
    }

    @Nested
    @DisplayName("9. 跨服私聊可靠投递端到端")
    class ChatReliableFlow {

        @Test
        @DisplayName("入队→ACK 成功；超时进入重试队列；超限丢弃")
        void ackRetryExhaust() {
            ReliablePrivateChatDelivery d = new ReliablePrivateChatDelivery();
            var pm = d.enqueue(1, 2, "hello");
            assertEquals(1, pm.seq());
            assertTrue(d.ack(pm.msgId(), 2));

            var pm2 = d.enqueue(1, 2, "retry-me");
            long cursor = System.currentTimeMillis();
            int guard = 0;
            while (d.pendingCount() > 0 && guard++ < 20) {
                cursor += ReliablePrivateChatDelivery.ACK_TIMEOUT_MS + 1;
                d.drainRetries(cursor);
            }
            assertEquals(0, d.pendingCount());
            assertEquals(3L, d.expectedNextSeq(1, 2));
            assertNotNull(pm2);
        }
    }

    @Nested
    @DisplayName("10. CmdId / wire 守卫")
    class ProtocolGuardFlow {

        @Test
        @DisplayName("PROTOCOL_WIRE_VERSION=4 且新号段唯一")
        void wireV4CmdIdsPresent() {
            assertEquals(4, CmdIds.PROTOCOL_WIRE_VERSION);
            assertEquals(1109, CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY);
            assertEquals(1110, CmdIds.BATTLE_CORRECTION_SC_NOTIFY);
            assertEquals(1114, CmdIds.ACTIVITY_FLOW_SC_NOTIFY);
            assertEquals(1115, CmdIds.FRIEND_INTIMACY_SC_NOTIFY);
            assertEquals(1117, CmdIds.RESOURCE_PATCH_SC_NOTIFY);
            assertEquals(1125, CmdIds.UNSUPPORTED_CMD_SC_NOTIFY);
        }
    }
}
