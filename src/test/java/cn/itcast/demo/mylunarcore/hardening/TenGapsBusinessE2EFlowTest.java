package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.admin.SensitiveOpApprovalService;
import cn.itcast.demo.mylunarcore.assist.AssistAnswer;
import cn.itcast.demo.mylunarcore.assist.AssistBattleRlAdvisor;
import cn.itcast.demo.mylunarcore.assist.rl.LocalRlPolicyService;
import cn.itcast.demo.mylunarcore.battle.BattleInstancePool;
import cn.itcast.demo.mylunarcore.battle.BattleLockstepService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.ResourcePatchManifestService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.HotWalletCacheService;
import cn.itcast.demo.mylunarcore.infra.db.DataSourceType;
import cn.itcast.demo.mylunarcore.infra.db.ReadWriteRouter;
import cn.itcast.demo.mylunarcore.infra.db.RoutingDataSourceContext;
import cn.itcast.demo.mylunarcore.infra.db.TableShardRouter;
import cn.itcast.demo.mylunarcore.infra.db.UidShardRouter;
import cn.itcast.demo.mylunarcore.net.NetTraceContext;
import cn.itcast.demo.mylunarcore.ops.OpsAutoMitigationService;
import cn.itcast.demo.mylunarcore.ops.SloErrorBudgetService;
import cn.itcast.demo.mylunarcore.ops.TraceRootCauseAnalyzer;
import cn.itcast.demo.mylunarcore.party.MigrateFallbackService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaDeadLetterService;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.player.ClientVersionGateService;
import cn.itcast.demo.mylunarcore.scene.DevicePerfProbeService;
import cn.itcast.demo.mylunarcore.settings.DeviceHapticsConfigService;
import cn.itcast.demo.mylunarcore.settings.QualityPresetService;
import cn.itcast.demo.mylunarcore.settings.UiAdaptConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 十大优化新功能端到端业务流程：覆盖热点钱包、组队迁移、资源补丁、帧同步、
 * HPA 指标、配置审批、AI RL、画质档、追踪 RCA、读写路由全链路。
 */
@DisplayName("十大优化新功能业务流程全量测试")
class TenGapsBusinessE2EFlowTest {

    @AfterEach
    void clearRouting() {
        RoutingDataSourceContext.clear();
        NetTraceContext.clearMdc();
    }

    @Nested
    @DisplayName("1. 热点钱包缓存 + 读写分离 + 分片")
    class HotPathDbFlow {
        @Test
        @DisplayName("缓存双写 → 读命中 → 延迟双删 → 分片路由一致")
        void walletCacheAndShardRouting() {
            HotWalletCacheService cache = new HotWalletCacheService(empty(), providerOf(new ObjectMapper()));
            Map<Integer, Integer> bal = Map.of(1, 999, 2, 50);
            cache.put(10086, bal);
            assertEquals(999, cache.get(10086).get(1));
            cache.invalidateNow(10086);
            assertNull(cache.get(10086));

            cache.put(10086, bal);
            cache.invalidateWithDelayDoubleDelete(10086);

            assertEquals(UidShardRouter.shardIndex(10086, 16), TableShardRouter.dbShard(10086));
            String table = TableShardRouter.physicalTable("gacha_history", 10086L);
            assertTrue(table.startsWith("gacha_history_"));
            assertTrue(TableShardRouter.actualDataNodesHint("player_wallet").contains("ds_${0..15}"));
        }

        @Test
        @DisplayName("ReadWriteRouter：读走 REPLICA，写走 PRIMARY")
        void readWriteRouterSwitches() {
            ReadWriteRouter router = new ReadWriteRouter();
            AtomicReference<DataSourceType> seen = new AtomicReference<>();
            router.read(() -> {
                seen.set(RoutingDataSourceContext.get());
                return null;
            });
            assertEquals(DataSourceType.REPLICA, seen.get());
            router.write(() -> seen.set(RoutingDataSourceContext.get()));
            assertEquals(DataSourceType.PRIMARY, seen.get());
        }
    }

    @Nested
    @DisplayName("2. 组队迁移 Saga + 超时降级 + 混沌 DeadLetter")
    class PartyMigrateFlow {
        @Test
        @DisplayName("完整迁移：INIT→SERIALIZING→TRANSFERRING→CONFIRMED")
        void happyPathMigrateWithProgress() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
            MigrateFallbackService fallback = new MigrateFallbackService(providerOf(saga), empty());
            PartyMigrateSagaService wired = new PartyMigrateSagaService(empty(), empty(), null, null, providerOf(fallback));

            var txn = wired.begin("party-e2e", 2001L, "node-east");
            assertEquals(MigrateState.INIT, txn.state());
            assertTrue(fallback.tryAdmitNewMigrate().admitted());

            txn = wired.advance(txn.txnId(), MigrateState.SERIALIZING, "ser-ticket");
            assertEquals(MigrateState.SERIALIZING, txn.state());
            txn = wired.advance(txn.txnId(), MigrateState.TRANSFERRING, "xfer-ticket");
            assertEquals(MigrateState.TRANSFERRING, txn.state());
            txn = wired.advance(txn.txnId(), MigrateState.CONFIRMED, null);
            assertEquals(MigrateState.CONFIRMED, txn.state());
            fallback.markSuccess(txn.txnId());
            assertTrue(fallback.failureRate() < 1.0);
        }

        @Test
        @DisplayName("超时回滚：armTimeout 到期后进入 ROLLBACK")
        void timeoutTriggersRollback() throws Exception {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
            MigrateFallbackService fallback = new MigrateFallbackService(providerOf(saga), empty());
            var txn = saga.begin("party-to", 3001L, "node-b");
            // armTimeout 内部下限 500ms
            fallback.armTimeout(txn.txnId(), 3001L, 500L);
            MigrateState state = MigrateState.INIT;
            for (int i = 0; i < 20; i++) {
                Thread.sleep(100L);
                var after = saga.get(txn.txnId());
                if (after != null && after.state() == MigrateState.ROLLBACK) {
                    state = after.state();
                    break;
                }
            }
            assertEquals(MigrateState.ROLLBACK, state);
            assertTrue(fallback.failureRate() > 0);
        }

        @Test
        @DisplayName("混沌注入 → DeadLetter 重试队列 → drain 超限进 DLQ")
        void chaosToDeadLetter() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
            PartyMigrateSagaDeadLetterService dlq = new PartyMigrateSagaDeadLetterService(saga);
            var txn = saga.begin("party-chaos2", 4001L, "n3");
            dlq.injectChaosFailNext();
            var rolled = dlq.advanceWithRetry(txn.txnId(), MigrateState.SERIALIZING, "t");
            assertEquals(MigrateState.ROLLBACK, rolled.state());
            assertTrue(dlq.retryQueueSize() >= 1);

            for (int i = 0; i < 5; i++) {
                dlq.enqueueRetry("ghost-" + i, MigrateState.SERIALIZING, "x",
                        PartyMigrateSagaDeadLetterService.MAX_RETRY + 1);
            }
            dlq.drainRetryQueue();
            assertFalse(dlq.listDeadLetters().isEmpty());
        }

        @Test
        @DisplayName("失败率超 1% 熔断拒绝新迁移")
        void circuitRejectsNewMigrate() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
            MigrateFallbackService fallback = new MigrateFallbackService(providerOf(saga), empty());
            fallback.resetCounters();
            for (int i = 0; i < MigrateFallbackService.MIN_SAMPLES + 5; i++) {
                // saga 非空才会真正累计 attempts
                fallback.armTimeout("x" + i, 1L, 60_000L);
                fallback.markFailure("x" + i);
            }
            assertTrue(fallback.failureRate() > MigrateFallbackService.FAIL_RATE_THRESHOLD);
            assertTrue(fallback.isCircuitOpen());
            assertFalse(fallback.tryAdmitNewMigrate().admitted());
        }
    }

    @Nested
    @DisplayName("3. 资源增量补丁 + 强制升级门禁")
    class ResourceAndUpgradeFlow {
        @Test
        @DisplayName("bsdiff 断点续传：resumeOffset 裁剪已完成块")
        void patchResumeOffsetFlow() {
            ResourcePatchManifestService svc = new ResourcePatchManifestService();
            var manifest = new ResourcePatchManifestService.PatchManifest(
                    "1.0.0", "1.1.0",
                    List.of(new ResourcePatchManifestService.FilePatch(
                            "ab/char.bundle", "h1", 2000, "https://cdn/char.bundle",
                            List.of(
                                    new ResourcePatchManifestService.DiffBlock(0, 800, "bsdiff", "https://cdn/p0", "m0"),
                                    new ResourcePatchManifestService.DiffBlock(800, 1200, "xdelta", "https://cdn/p1", "m1")))));
            svc.putManifest(manifest);
            assertNotNull(svc.get("1.0.0", "1.1.0"));

            var blocks = svc.resumeBlocks(manifest.files().get(0), 800);
            assertEquals(1, blocks.size());
            assertEquals("xdelta", blocks.get(0).get("algo"));
            assertEquals(800L, ((Number) blocks.get(0).get("blockOffset")).longValue());

            long preheated = svc.markCdnPreheat("1.1.0", 12);
            assertTrue(preheated >= 12);
        }

        @Test
        @DisplayName("版本比较与强制升级错误码")
        void forceUpgradeCodes() {
            assertEquals(10, ClientVersionGateService.ERR_CLIENT_TOO_OLD);
            assertEquals(11, ClientVersionGateService.ERR_RESOURCE_OUTDATED);
            assertEquals(12, ClientVersionGateService.ERR_FORCE_UPGRADE);
            assertTrue(ClientVersionGateService.compareSemver("1.2.0", "1.2.0") == 0);
            assertTrue(ClientVersionGateService.compareSemver("1.1.9", "1.2.0") < 0);
            ClientVersionGateService.GateResult force =
                    ClientVersionGateService.GateResult.forceUpgrade("1.2.0", "https://store", 1L);
            assertFalse(force.allowed());
            assertEquals(12, force.retcode());
            assertEquals(1L, force.forceUpgradeDeadline());
        }
    }

    @Nested
    @DisplayName("4. 帧同步 + 触控 + 震动 + 画质档")
    class BattleFeelFlow {
        @Test
        @DisplayName("Lockstep 仅关键技能涨帧；移动不涨帧")
        void lockstepSkillVsMove() {
            BattleLockstepService ls = new BattleLockstepService(empty());
            var move = ls.broadcastCritical(99L, List.of(1L), BattleLockstepService.SyncKind.MOVE_STATE, "walk");
            assertEquals(0L, move.frameNo());
            var skill = ls.broadcastCritical(99L, List.of(1L, 2L), BattleLockstepService.SyncKind.SKILL_LOCKSTEP, "ult");
            assertEquals(1L, skill.frameNo());
            var hit = ls.broadcastCritical(99L, List.of(1L), BattleLockstepService.SyncKind.HIT_LOCKSTEP, "dmg");
            assertEquals(2L, hit.frameNo());
            ls.clear(99L);
            assertEquals(0L, ls.currentFrame(99L));
        }

        @Test
        @DisplayName("移动端触控死区/滑动取消；PC 更灵敏")
        void touchAdaptByDevice() {
            UiAdaptConfigService ui = new UiAdaptConfigService(empty());
            ui.resolveAndPush(10, 720, 1280, "mobile_touch");
            var mobile = ui.getHeatmap(10);
            assertTrue(mobile.joystickDeadzone() >= 0.15f);
            assertTrue(mobile.swipeCancelSkill());

            ui.resolveAndPush(11, 1920, 1080, "pc_km");
            var pc = ui.getHeatmap(11);
            assertTrue(pc.joystickDeadzone() <= mobile.joystickDeadzone());
        }

        @Test
        @DisplayName("技能类型震动波形：战技/终结技/受击，iOS 偏移")
        void hapticsBySkillAndDevice() {
            assertEquals(104, DeviceHapticsConfigService.pickWaveformBySkill("skill", "android"));
            assertEquals(105, DeviceHapticsConfigService.pickWaveformBySkill("ultimate", "android"));
            assertEquals(106, DeviceHapticsConfigService.pickWaveformBySkill("hit", "android"));
            assertEquals(115, DeviceHapticsConfigService.pickWaveformBySkill("ultimate", "ios"));
            assertTrue(DeviceHapticsConfigService.pickIntensityBySkill("终结技") >= 90);
        }

        @Test
        @DisplayName("DeviceProfile → QualityPreset → 渲染降级参数")
        void qualityPresetPipeline() {
            QualityPresetService presets = new QualityPresetService(new ObjectMapper(), "data");
            presets.load();
            var ultra = presets.resolve(new QualityPresetService.DeviceProfile("A16 Bionic", 8192, "apple", "ios"));
            assertEquals("ultra", ultra.id());

            var adj = DevicePerfProbeService.decide(
                    new DevicePerfProbeService.Probe(46f, 60, 90, "iphone", System.currentTimeMillis(),
                            "A16", 8192, "apple"), presets);
            assertTrue(adj.isPresent());
            assertEquals("thermal", adj.get().reason());
            assertEquals(0, adj.get().renderTier());
        }
    }

    @Nested
    @DisplayName("5. HPA 指标 + SLO 错误预算 + 自动降级 + RCA")
    class ObservabilityFlow {
        @Test
        @DisplayName("战斗池使用率写入 BusinessMetrics")
        void battlePoolUsageMetric() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getGameLoop().setMaxActiveBattles(10);
            BattleInstancePool pool = new BattleInstancePool(props);
            pool.tryAdmit(1, BattleInstancePool.Priority.PLAYER_ACTIVE);
            pool.tryAdmit(2, BattleInstancePool.Priority.PLAYER_ACTIVE);
            assertEquals(0.2, pool.usageRatio(), 0.001);

            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            metrics.setBattlePoolUsage(pool.usageRatio());
            assertEquals(0.2, metrics.battlePoolUsage(), 0.001);
            metrics.setMatchQueueDepth(pool.queueDepth());
        }

        @Test
        @DisplayName("SLO 预算燃烧触发非核心降级")
        void sloBurnDegradesNonCore() {
            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            OpsAutoMitigationService ops = new OpsAutoMitigationService(metrics);
            SloErrorBudgetService slo = new SloErrorBudgetService(metrics, ops);
            for (int i = 0; i < 50; i++) {
                slo.recordCoreSuccess();
            }
            for (int i = 0; i < 5; i++) {
                slo.recordCoreFailure();
            }
            assertTrue(slo.budgetConsumedRatio() >= SloErrorBudgetService.BUDGET_BURN_ALERT);
            slo.evaluate();
            assertTrue(slo.isNonCoreDegraded());
            assertTrue(ops.isAiVisionDisabled());
            assertTrue(ops.isLogLevelReduced());
            Map<String, Object> snap = slo.snapshot();
            assertTrue(((Number) snap.get("fail")).intValue() >= 5);
        }

        @Test
        @DisplayName("AI fallback 超阈值关闭视觉；RCA 识别慢 SQL")
        void aiFallbackAndRca() {
            BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
            for (int i = 0; i < 10; i++) {
                metrics.recordAiRequest("fallback", 20);
            }
            OpsAutoMitigationService ops = new OpsAutoMitigationService(metrics);
            ops.evaluate();
            assertTrue(ops.isAiVisionDisabled());

            TraceRootCauseAnalyzer rca = new TraceRootCauseAnalyzer();
            var a = rca.analyze("tr-db", List.of("lock wait timeout"), 500, 0);
            assertEquals("database", a.findings().get(0).category());
            assertTrue(rca.toOpsPayload(a).containsKey("hint"));
        }

        @Test
        @DisplayName("Trace 业务标签与 traceparent")
        void traceBusinessTags() {
            NetTraceContext.putBusinessTags(213, 101, 555L);
            String tp = NetTraceContext.toTraceparent("ch-213-9");
            assertTrue(tp.startsWith("00-"));
            assertEquals(55, tp.length()); // 00-32-16-01 = 2+1+32+1+16+1+2 = 55
            NetTraceContext.clearBusinessTags();
        }
    }

    @Nested
    @DisplayName("6-7. 配置多人审批 + AI 多模态 RL")
    class ConfigAndAssistFlow {
        @Test
        @DisplayName("普通配置：2 人批准后可核销")
        void dualApprovalConsume() {
            SensitiveOpApprovalService svc = new SensitiveOpApprovalService(null);
            var t = svc.request("CONFIG_CHANGE", "{\"configName\":\"ShopConfigs.json\"}", "planner");
            assertEquals(2, t.requiredApprovals());
            assertFalse(t.requiresExecutive());
            assertEquals(SensitiveOpApprovalService.Status.PENDING, svc.approve(t.ticketId(), "ops1").status());
            assertEquals(SensitiveOpApprovalService.Status.APPROVED, svc.approve(t.ticketId(), "ops2").status());
            assertTrue(svc.consumeIfApproved(t.ticketId(), "CONFIG_CHANGE"));
        }

        @Test
        @DisplayName("抽卡概率：需总经理级 + 2 人")
        void gachaRateNeedsExecutive() {
            SensitiveOpApprovalService svc = new SensitiveOpApprovalService(null);
            var t = svc.request("GACHA_RATE_CHANGE", "{\"probability\":0.006}", "planner");
            assertTrue(t.requiresExecutive());
            svc.approve(t.ticketId(), "ops1");
            assertEquals(SensitiveOpApprovalService.Status.PENDING,
                    svc.approve(t.ticketId(), "ops2").status());
            assertEquals(SensitiveOpApprovalService.Status.APPROVED,
                    svc.approve(t.ticketId(), "ceo", SensitiveOpApprovalService.EXEC_ROLE).status());
        }

        @Test
        @DisplayName("战斗场景 RL：低血量偏防御；大招就绪偏终结技；反馈可记录")
        void multimodalRlAdvice() {
            LocalRlPolicyService rl = new LocalRlPolicyService();
            var defend = rl.decide(new LocalRlPolicyService.BattleState(
                    List.of("tank"), List.of("a", "b"), 5, Map.of(), false, 0.2));
            assertEquals("defend_heal", defend.action());

            var ult = rl.decide(new LocalRlPolicyService.BattleState(
                    List.of("dps"), List.of("boss"), 1, Map.of(), true, 0.9));
            assertEquals("cast_ultimate", ult.action());

            AssistBattleRlAdvisor advisor = new AssistBattleRlAdvisor(providerOf(rl), providerOf(new ObjectMapper()));
            var ans = advisor.tryAdvise(88L, "下一回合怎么打", "battle",
                    "{\"team\":[\"dps\"],\"enemies\":[\"elite\"],\"remainTurns\":2,\"ultimateReady\":true,\"teamHpRatio\":0.75}");
            assertTrue(ans.isPresent());
            AssistAnswer answer = ans.get();
            assertEquals("local-rl", answer.source());
            assertNotNull(answer.answer());
            assertTrue(answer.answer().contains("建议动作"));
            advisor.onFeedback("cast_ultimate", true);
            assertNotNull(rl.snapshot().get("feedbackUseful"));
        }

        @Test
        @DisplayName("非战斗场景不走 RL 快路径")
        void nonBattleSkipsRl() {
            AssistBattleRlAdvisor advisor = new AssistBattleRlAdvisor(
                    providerOf(new LocalRlPolicyService()), providerOf(new ObjectMapper()));
            assertTrue(advisor.tryAdvise(1L, "今日任务有哪些", "quest", null).isEmpty());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
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
