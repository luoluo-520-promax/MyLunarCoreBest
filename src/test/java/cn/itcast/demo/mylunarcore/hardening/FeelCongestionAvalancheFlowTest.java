package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.anticheat.BattleAuditService;
import cn.itcast.demo.mylunarcore.anticheat.BattleDeterministicValidator;
import cn.itcast.demo.mylunarcore.assist.AssistAuditService;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.assist.AssistPersonaService;
import cn.itcast.demo.mylunarcore.assist.AssistQuotaLimiter;
import cn.itcast.demo.mylunarcore.assist.AssistTtsService;
import cn.itcast.demo.mylunarcore.assist.VisualAssistService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleDeltaRecord;
import cn.itcast.demo.mylunarcore.battle.BattleFxComposer;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.HitStopComposer;
import cn.itcast.demo.mylunarcore.battle.PlayerInputBufferService;
import cn.itcast.demo.mylunarcore.common.BattleSceneThrottleService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.economy.WalletWalService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchObjectPools;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.kcp.AdaptiveKcpRetransmitAlgo;
import cn.itcast.demo.mylunarcore.net.kcp.KcpCongestionProfile;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
import cn.itcast.demo.mylunarcore.tx.LocalDistributedLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本轮「战斗手感 + 拥堵防护 + 运维防雪崩」新功能全流程回归。
 */
@DisplayName("战斗手感/拥堵/防雪崩 新业务流程全测")
class FeelCongestionAvalancheFlowTest {

    private static BattleContext sampleBattle(long battleId, int playerId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = List.of(
                new BattleMonsterWaveRepository.WaveConfig(1, 200, 1, "[301]", 5));
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    @Nested
    @DisplayName("A. 战斗手感闭环")
    class BattleFeelFlows {

        @Test
        @DisplayName("Hit-stop 分级 + Proto 序列化咬合")
        void hitStopFramesRoundTripInBattleFxNotify() throws Exception {
            HitStopComposer.HitStopPlan plan = HitStopComposer.compose(3001, true, true, 1200);
            assertTrue(plan.totalMs() >= 200);
            assertTrue(plan.frames().size() >= 2);

            BattleSystemProto.BattleFxScNotify.Builder b = BattleSystemProto.BattleFxScNotify.newBuilder()
                    .setBattleId(1)
                    .setSkillId(3001)
                    .setCasterId(42)
                    .setHitStopTotalMs(plan.totalMs())
                    .setExpectedHitStopEndMs(System.currentTimeMillis() + plan.totalMs());
            for (HitStopComposer.HitStopSpec s : plan.frames()) {
                b.addHitStopFrames(BattleSystemProto.HitStopFrame.newBuilder()
                        .setTriggerFrame(s.triggerFrame())
                        .setDurationMs(s.durationMs())
                        .build());
            }
            BattleSystemProto.BattleFxScNotify parsed =
                    BattleSystemProto.BattleFxScNotify.parseFrom(b.build().toByteArray());
            assertEquals(plan.frames().size(), parsed.getHitStopFramesCount());
            assertEquals(plan.totalMs(), parsed.getHitStopTotalMs());
            assertTrue(parsed.getExpectedHitStopEndMs() > 0);
        }

        @Test
        @DisplayName("缩短顿帧应被确定性校验拒绝")
        void hitStopShortenRejected() {
            BattleDeterministicValidator v = new BattleDeterministicValidator(new BattleAuditService());
            BattleContext ctx = sampleBattle(1L, 42);
            long expected = System.currentTimeMillis() + 200;
            var bad = v.validateHitStopEnd(ctx, expected, expected - 100, System.currentTimeMillis());
            assertFalse(bad.accepted());
            assertEquals("hit_stop_shorten", bad.rejectReason());

            var ok = v.validateHitStopEnd(ctx, expected, expected + 10, System.currentTimeMillis());
            assertTrue(ok.accepted());
        }

        @Test
        @DisplayName("预输入：高优先级挤占低优先级，窗口内不丢终结技")
        void inputBufferCancelHierarchy() {
            PlayerInputBufferService buf = new PlayerInputBufferService();
            long battleId = 100L;
            long now = System.currentTimeMillis();
            buf.openActionWindow(battleId, now + 500);
            assertTrue(buf.enqueueOrExecuteNow(battleId, 1, 1, 1, 1, 1, List.of(9), now)); // BASIC
            assertTrue(buf.enqueueOrExecuteNow(battleId, 1, 1, 1, 3001, 1, List.of(9), now)); // ULT
            assertTrue(buf.enqueueOrExecuteNow(battleId, 1, 1, 1, 2, 1, List.of(9), now)); // SKILL
            PlayerInputBufferService.BufferedInput first = buf.poll(battleId, 1);
            assertNotNull(first);
            assertEquals(PlayerInputBufferService.CancelTier.ULT, first.tier());
        }

        @Test
        @DisplayName("断线重连应下发 BattleReplayDeltaScNotify 含增量")
        void reconnectPushesReplayDeltaNotify() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getBattleSnapshot().setEnabled(true);
            props.getBattleSnapshot().setDeltaRingSize(10);
            props.getRedis().setEnabled(false);
            @SuppressWarnings("unchecked")
            ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            BattleSnapshotService snaps = new BattleSnapshotService(new ObjectMapper(), props, redis);
            BattleContext ctx = sampleBattle(77L, 42);

            snaps.recordDelta(77L, 42, 1001, 101, 300, true, false, 700);
            snaps.recordDelta(77L, 42, 3001, 101, 900, true, true, 0);
            List<BattleDeltaRecord> recent = snaps.recentDeltas(77L);
            assertEquals(2, recent.size());

            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            snaps.pushReplayOnReconnect(77L, ctx, ch);

            var cap = org.mockito.ArgumentCaptor.forClass(Object.class);
            verify(ch, atLeastOnce()).writeAndFlush(cap.capture());
            GamePacket pkt = (GamePacket) cap.getAllValues().stream()
                    .filter(o -> o instanceof GamePacket gp && gp.getCmdId() == CmdIds.BATTLE_REPLAY_DELTA_SC_NOTIFY)
                    .findFirst()
                    .orElseThrow();
            BattleSystemProto.BattleReplayDeltaScNotify notify =
                    BattleSystemProto.BattleReplayDeltaScNotify.parseFrom(pkt.getPayload());
            assertEquals(77L, notify.getBattleId());
            assertEquals(2, notify.getDeltasCount());
            assertTrue(notify.getFinalState().getEntitiesCount() > 0);
        }
    }

    @Nested
    @DisplayName("B. 拥堵确定性闭环")
    class CongestionFlows {

        @Test
        @DisplayName("Battle 积压超 50ms 应触发 ThrottleScNotify 语义")
        void battleBacklogEngagesSceneThrottle() throws Exception {
            BattleSceneThrottleService throttle = new BattleSceneThrottleService(mock(GameSessionManager.class));
            long now = System.currentTimeMillis();
            throttle.markBattleEnqueued(now - 80);
            throttle.markBattleStarted(now);
            assertTrue(throttle.isThrottled(now));
            assertEquals(BattleSceneThrottleService.THROTTLED_SCENE_HZ, throttle.resolveSceneHz(now));
            assertTrue(throttle.getLastObservedLagMs() >= 50);

            SceneSystemProto.ThrottleScNotify n = SceneSystemProto.ThrottleScNotify.newBuilder()
                    .setSceneTickHz(2)
                    .setReason("battle_backlog")
                    .setBattleQueueLagMs(80)
                    .setUntilMs(now + 3000)
                    .build();
            assertEquals(2, SceneSystemProto.ThrottleScNotify.parseFrom(n.toByteArray()).getSceneTickHz());
            assertEquals(CmdIds.THROTTLE_SC_NOTIFY, 1106);
        }

        @Test
        @DisplayName("钱包 WAL：内存预扣成功并进入 pending")
        void walletWalLocalDeductAndFlush(@TempDir Path tmp) {
            WalletRepository repo = mock(WalletRepository.class);
            when(repo.loadCurrency(1001)).thenReturn(Map.of(101, 1000));

            PlatformTransactionManager tm = new PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }

                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {
                }

                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {
                }
            };

            WalletWalService wal = new WalletWalService(repo, tm, tmp.toString());
            // 不启动定时 flush，仅验证本地预扣路径
            var r = wal.applyLocal(1001, 101, -40, "stamina");
            assertTrue(r.success());
            assertEquals(960, r.balance().get(101));
            assertTrue(wal.pendingSize() >= 1);

            when(repo.lockCurrency(1001)).thenReturn(new WalletRepository.CurrencyLockRow(Map.of(101, 960), 1L));
            when(repo.updateCurrencyOptimistic(anyInt(), any(), anyLong())).thenReturn(true);
            int ok = wal.flushOnce();
            assertTrue(ok >= 1);
            assertEquals(0, wal.pendingSize());
        }

        @Test
        @DisplayName("WalletApplicationService 开启 WAL 时走本地预扣")
        void walletAppRoutesToWal(@TempDir Path tmp) {
            WalletRepository repo = mock(WalletRepository.class);
            when(repo.loadCurrency(7)).thenReturn(Map.of(1, 500));
            PlatformTransactionManager tm = new PlatformTransactionManager() {
                @Override
                public org.springframework.transaction.TransactionStatus getTransaction(
                        org.springframework.transaction.TransactionDefinition definition) {
                    return new org.springframework.transaction.support.SimpleTransactionStatus();
                }

                @Override
                public void commit(org.springframework.transaction.TransactionStatus status) {
                }

                @Override
                public void rollback(org.springframework.transaction.TransactionStatus status) {
                }
            };
            WalletWalService wal = new WalletWalService(repo, tm, tmp.toString());
            LunarCoreProperties props = new LunarCoreProperties();
            props.getWalletWal().setEnabled(true);
            @SuppressWarnings("unchecked")
            ObjectProvider<cn.itcast.demo.mylunarcore.tx.LocalTxLogService> tx = mock(ObjectProvider.class);
            when(tx.getIfAvailable()).thenReturn(null);
            @SuppressWarnings("unchecked")
            ObjectProvider<WalletWalService> walProvider = mock(ObjectProvider.class);
            when(walProvider.getIfAvailable()).thenReturn(wal);
            when(walProvider.getObject()).thenReturn(wal);

            WalletApplicationService app = new WalletApplicationService(
                    repo, new LocalDistributedLockService(), tx, walProvider, props);
            var result = app.deduct(7, 1, 50, "gacha");
            assertTrue(result.success());
            assertEquals(450, result.balance().get(1));
        }

        @Test
        @DisplayName("匹配对象池借用归还 + 批次成房")
        void matchPoolAndBatchRoom() throws Exception {
            MatchObjectPools pools = new MatchObjectPools();
            MatchObjectPools.MatchSession s = pools.borrowSession();
            s.assign(11, 1, 20, 1500, System.currentTimeMillis(), 1200);
            assertEquals(11, s.playerId());
            pools.returnSession(s);

            RoomService rooms = new RoomService();
            @SuppressWarnings("unchecked")
            ObjectProvider<MatchObjectPools> poolProvider = mock(ObjectProvider.class);
            when(poolProvider.getIfAvailable()).thenReturn(pools);
            when(poolProvider.getObject()).thenReturn(pools);

            MatchmakingService mm = new MatchmakingService(
                    rooms,
                    mock(GameSessionManager.class),
                    new LunarCoreProperties(),
                    new cn.itcast.demo.mylunarcore.common.BusinessMetrics(new SimpleMeterRegistry()),
                    null, null, poolProvider);

            mm.joinQueue(101, 1, 10, 1000);
            mm.joinQueue(102, 1, 11, 1050);
            mm.batchMatchTick();
            assertNotNull(rooms.findRoomByPlayer(101));
            assertNotNull(rooms.findRoomByPlayer(102));
            pools.close();
        }

        @Test
        @DisplayName("KCP 强网/弱网画像切换")
        void kcpCongestionProfilesByRtt() {
            AdaptiveKcpRetransmitAlgo algo = new AdaptiveKcpRetransmitAlgo(20, 250, 256, true, 50, 200);
            KcpCongestionProfile strong = algo.resolveProfile(30);
            KcpCongestionProfile weak = algo.resolveProfile(250);
            assertTrue(strong.fastAck());
            assertTrue(weak.nodelay());
            assertTrue(strong.rtoMultiplierBp() < weak.rtoMultiplierBp()
                    || strong.intervalMs() <= weak.intervalMs());
            int strongInterval = algo.nextSendIntervalMs(30, 0);
            int weakInterval = algo.nextSendIntervalMs(250, 0);
            assertTrue(strongInterval <= 20);
            assertTrue(weakInterval <= 15);
        }
    }

    @Nested
    @DisplayName("C. 运维防雪崩闭环")
    class AvalancheFlows {

        @Test
        @DisplayName("预加载 10s 内第 4 次触发风暴 BLACK")
        void preloadStormThrottlesFourthRequest() {
            ScenePreloadService preload = new ScenePreloadService();
            long uid = 9001L;
            assertFalse(preload.isPreloadStorm(uid));
            assertFalse(preload.isPreloadStorm(uid));
            assertFalse(preload.isPreloadStorm(uid));
            assertTrue(preload.isPreloadStorm(uid));

            SceneSystemProto.ScenePreloadScRsp rsp = SceneSystemProto.ScenePreloadScRsp.newBuilder()
                    .setRetcode(5)
                    .setStormThrottled(true)
                    .setMaskInfo(SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                            .setTransitionType("BLACK")
                            .setDurationMs(ScenePreloadService.BLACK_DURATION_MS)
                            .build())
                    .build();
            assertTrue(rsp.getStormThrottled());
            assertEquals("BLACK", rsp.getMaskInfo().getTransitionType());
        }

        @Test
        @DisplayName("VLM 队列>100 直接繁忙，不堵塞调用线程")
        void visualAssistBusyWhenQueueOver100() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAiAssist().setEnabled(true);
            props.getAiAssist().setVlmEnabled(true);
            props.getAiAssist().setSyncMode(false);

            // 队列容量 120，预填 101 个任务占坑
            ArrayBlockingQueue<Runnable> q = new ArrayBlockingQueue<>(120);
            for (int i = 0; i < 101; i++) {
                q.offer(() -> {
                });
            }
            ExecutorService busy = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, q,
                    new ThreadPoolExecutor.AbortPolicy());

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolveUid(any())).thenReturn(java.util.OptionalLong.of(55L));
            AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
            when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
            AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
            when(feature.current()).thenReturn(new AssistFeatureContent(
                    1, List.of(), List.of(), List.of(), Map.of(), List.of(), List.of()));
            AssistPersonaService persona = mock(AssistPersonaService.class);
            when(persona.wrapAnswer(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));

            VisualAssistService visual = new VisualAssistService(props, resolver, quota,
                    new AssistAuditService(), feature, persona, new AssistTtsService(props),
                    new cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer(),
                    new cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder(), busy);

            byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, 0x01};
            Channel ch = mock(Channel.class);
            AssistSystemProto.UploadScreenshotScRsp rsp = visual.handleUpload(
                    AssistSystemProto.UploadScreenshotCsReq.newBuilder()
                            .setJpegBytes(ByteString.copyFrom(jpeg))
                            .setPlaneId(1)
                            .setQuestion("?")
                            .build(),
                    ch);
            assertEquals(5, rsp.getRetcode());
            assertTrue(rsp.getAnswer().contains("繁忙"));
            busy.shutdownNow();
        }

        @Test
        @DisplayName("VLM 正常异步受理 retcode=0 accepted")
        void visualAssistAsyncAccept() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAiAssist().setEnabled(true);
            props.getAiAssist().setVlmEnabled(true);
            props.getAiAssist().setSyncMode(false);

            ExecutorService pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(16));
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolveUid(any())).thenReturn(java.util.OptionalLong.of(55L));
            AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
            when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
            AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
            when(feature.current()).thenReturn(new AssistFeatureContent(
                    1, List.of(), List.of(), List.of(), Map.of(), List.of(), List.of()));
            AssistPersonaService persona = mock(AssistPersonaService.class);
            when(persona.wrapAnswer(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
            when(persona.personaId(anyLong())).thenReturn("tingyun");
            when(persona.voiceId(anyLong())).thenReturn("tingyun_zh");

            VisualAssistService visual = new VisualAssistService(props, resolver, quota,
                    new AssistAuditService(), feature, persona, new AssistTtsService(props),
                    new cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer(),
                    new cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder(), pool);
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

            AssistSystemProto.UploadScreenshotScRsp rsp = visual.handleUpload(
                    AssistSystemProto.UploadScreenshotCsReq.newBuilder()
                            .setJpegBytes(ByteString.copyFrom(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2}))
                            .setPlaneId(1)
                            .setQuestion("机关")
                            .build(),
                    ch);
            assertEquals(0, rsp.getRetcode());
            assertEquals("accepted", rsp.getAnswer());
            pool.shutdownNow();
        }
    }

    @Nested
    @DisplayName("D. 协议 CmdId / FX 合成一致性")
    class ProtocolConsistency {

        @Test
        @DisplayName("新 CmdId 1105/1106 已占用且 FX 含 hitStop")
        void newCmdIdsAndFxHint() {
            assertEquals(1105, CmdIds.BATTLE_REPLAY_DELTA_SC_NOTIFY);
            assertEquals(1106, CmdIds.THROTTLE_SC_NOTIFY);
            BattleFxComposer.FxHint fx = BattleFxComposer.compose(-500, false, 2, true, 8);
            assertTrue(fx.hitStop().totalMs() > 0);
            assertEquals(8, fx.targetEntityId());
        }

        @Test
        @DisplayName("多人共战 Hit-stop 同源：同一 skill 合成结果稳定")
        void hitStopDeterministicForSameInput() {
            AtomicInteger total = new AtomicInteger();
            for (int i = 0; i < 20; i++) {
                total.set(HitStopComposer.compose(1001, true, false, 400).totalMs());
            }
            assertEquals(HitStopComposer.compose(1001, true, false, 400).totalMs(), total.get());
        }
    }
}
