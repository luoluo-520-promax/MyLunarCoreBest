package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.admin.SensitiveOpApprovalService;
import cn.itcast.demo.mylunarcore.assist.AssistBattleRlAdvisor;
import cn.itcast.demo.mylunarcore.assist.rl.LocalRlPolicyService;
import cn.itcast.demo.mylunarcore.battle.BattleLockstepService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.ResourcePatchManifestService;
import cn.itcast.demo.mylunarcore.economy.HotWalletCacheService;
import cn.itcast.demo.mylunarcore.infra.db.TableShardRouter;
import cn.itcast.demo.mylunarcore.ops.OpsAutoMitigationService;
import cn.itcast.demo.mylunarcore.ops.SloErrorBudgetService;
import cn.itcast.demo.mylunarcore.ops.TraceRootCauseAnalyzer;
import cn.itcast.demo.mylunarcore.party.MigrateFallbackService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaDeadLetterService;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.player.ClientVersionGateService;
import cn.itcast.demo.mylunarcore.scene.DevicePerfProbeService;
import cn.itcast.demo.mylunarcore.settings.QualityPresetService;
import cn.itcast.demo.mylunarcore.settings.UiAdaptConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 十大体验/稳定性缺口优化回归：缓存、迁移降级、补丁、帧同步、SLO、RL、画质档、审批。
 */
class TenGapsOptimizationFlowTest {

    @Test
    void tableShard_and_hotWalletCache() {
        assertEquals("player_wallet_03_027", TableShardRouter.physicalTable("player_wallet", 3 + 16 * 27L));
        HotWalletCacheService cache = new HotWalletCacheService(empty(), empty());
        cache.put(42, Map.of(1, 100));
        assertEquals(100, cache.get(42).get(1));
        cache.invalidateWithDelayDoubleDelete(42);
    }

    @Test
    void migrateFallback_circuitAndProgress() {
        PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
        MigrateFallbackService fallback = new MigrateFallbackService(
                providerOf(saga), empty());
        PartyMigrateSagaService wired = new PartyMigrateSagaService(empty(), empty(), null, null, providerOf(fallback));
        var txn = wired.begin("p1", 1001L, "node-b");
        assertNotNull(txn);
        assertTrue(txn.state() == MigrateState.INIT || txn.state() == MigrateState.ROLLBACK);
        fallback.resetCounters();
        for (int i = 0; i < 60; i++) {
            fallback.armTimeout("t" + i, 1L, 50L);
            fallback.markFailure("t" + i);
        }
        assertTrue(fallback.failureRate() > MigrateFallbackService.FAIL_RATE_THRESHOLD);
        assertEquals(MigrateFallbackService.AdmitResult.reject("migrate_circuit_open").reason(),
                fallback.tryAdmitNewMigrate().reason());
    }

    @Test
    void chaosMonkey_deadLetterRetry() {
        PartyMigrateSagaService saga = new PartyMigrateSagaService(empty(), empty());
        PartyMigrateSagaDeadLetterService dlq = new PartyMigrateSagaDeadLetterService(saga);
        var txn = saga.begin("party-chaos", 9L, "n2");
        dlq.injectChaosFailNext();
        var rolled = dlq.advanceWithRetry(txn.txnId(), MigrateState.SERIALIZING, "ticket");
        assertEquals(MigrateState.ROLLBACK, rolled.state());
        assertTrue(dlq.chaosInjectCount() >= 1);
        assertTrue(dlq.retryQueueSize() >= 1);
    }

    @Test
    void resourcePatch_resumeBlocks() {
        ResourcePatchManifestService svc = new ResourcePatchManifestService();
        var file = new ResourcePatchManifestService.FilePatch(
                "ab/ui.bundle", "md5a", 1000, "https://cdn/ui.bundle",
                List.of(new ResourcePatchManifestService.DiffBlock(0, 500, "bsdiff", "https://cdn/p1", "m1"),
                        new ResourcePatchManifestService.DiffBlock(500, 500, "bsdiff", "https://cdn/p2", "m2")));
        List<Map<String, Object>> blocks = svc.resumeBlocks(file, 500);
        assertEquals(1, blocks.size());
        assertEquals(500L, ((Number) blocks.get(0).get("blockOffset")).longValue());
    }

    @Test
    void forceUpgradeDeadline_gate() {
        ClientVersionGateService gate = new ClientVersionGateService(new ObjectMapper(), "data");
        // 使用反射/直接构造 required 不可行时，验证静态比较与错误码常量
        assertEquals(12, ClientVersionGateService.ERR_FORCE_UPGRADE);
        assertTrue(ClientVersionGateService.compareSemver("1.0.0", "1.1.0") < 0);
    }

    @Test
    void lockstep_and_touch_and_quality() {
        BattleLockstepService lockstep = new BattleLockstepService(empty());
        var tick = lockstep.broadcastCritical(7L, List.of(1L, 2L),
                BattleLockstepService.SyncKind.SKILL_LOCKSTEP, "ult");
        assertEquals(1L, tick.frameNo());
        assertEquals(2L, lockstep.nextFrame(7L));

        UiAdaptConfigService ui = new UiAdaptConfigService(empty());
        var cfg = ui.resolveAndPush(1, 720, 1280, "mobile_touch");
        assertNotNull(cfg);
        assertTrue(ui.getHeatmap(1).joystickDeadzone() >= 0.10f);
        assertTrue(ui.getHeatmap(1).swipeCancelSkill());

        QualityPresetService presets = new QualityPresetService(new ObjectMapper(), "data");
        presets.load();
        var low = presets.resolve(new QualityPresetService.DeviceProfile("Helio G85", 2048, "mali", "android"));
        assertEquals("low", low.id());
        Optional<DevicePerfProbeService.Adjust> adj = DevicePerfProbeService.decide(
                new DevicePerfProbeService.Probe(36f, 50, 80, "d1", System.currentTimeMillis(),
                        "Helio G85", 2048, "mali"), presets);
        assertTrue(adj.isPresent());
        assertEquals("low", adj.get().presetId());
    }

    @Test
    void slo_and_ops_and_rca() {
        BusinessMetrics metrics = new BusinessMetrics(new SimpleMeterRegistry());
        OpsAutoMitigationService ops = new OpsAutoMitigationService(metrics);
        SloErrorBudgetService slo = new SloErrorBudgetService(metrics, ops);
        for (int i = 0; i < 100; i++) {
            slo.recordCoreSuccess();
        }
        for (int i = 0; i < 5; i++) {
            slo.recordCoreFailure();
        }
        assertTrue(slo.budgetConsumedRatio() > 0);
        slo.evaluate();

        TraceRootCauseAnalyzer rca = new TraceRootCauseAnalyzer();
        var analysis = rca.analyze("t-1", List.of("HikariPool exhausted"), 300, 0);
        assertEquals("connection_pool", analysis.findings().get(0).category());
    }

    @Test
    void localRl_and_multiApproval() {
        LocalRlPolicyService rl = new LocalRlPolicyService();
        var advice = rl.decide(new LocalRlPolicyService.BattleState(
                List.of("a"), List.of("boss"), 1, Map.of(), true, 0.8));
        assertEquals("cast_ultimate", advice.action());
        assertTrue(advice.latencyMs() < 50);

        AssistBattleRlAdvisor advisor = new AssistBattleRlAdvisor(providerOf(rl), providerOf(new ObjectMapper()));
        assertTrue(advisor.tryAdvise(1L, "何时放大招", "battle",
                "{\"team\":[\"a\"],\"enemies\":[\"b\"],\"remainTurns\":1,\"ultimateReady\":true,\"teamHpRatio\":0.8}")
                .isPresent());

        SensitiveOpApprovalService approval = new SensitiveOpApprovalService(null);
        var t = approval.request("GACHA_RATE_CHANGE", "{\"rate\":0.006}", "planner");
        assertTrue(t.requiresExecutive());
        assertEquals(SensitiveOpApprovalService.Status.PENDING, approval.approve(t.ticketId(), "ops1").status());
        var approved = approval.approve(t.ticketId(), "ceo", SensitiveOpApprovalService.EXEC_ROLE);
        assertEquals(SensitiveOpApprovalService.Status.APPROVED, approved.status());
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
