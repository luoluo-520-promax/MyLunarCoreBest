package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.VersionThemeService;
import cn.itcast.demo.mylunarcore.battle.BattleFxComposer;
import cn.itcast.demo.mylunarcore.battle.BattleQteService;
import cn.itcast.demo.mylunarcore.battle.CancelWindowService;
import cn.itcast.demo.mylunarcore.battle.PlayerInputBufferService;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassThemeService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.ConfigGrayAutoRollbackService;
import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import cn.itcast.demo.mylunarcore.common.StructuredJsonLogger;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.economy.WalletReconciliationJob;
import cn.itcast.demo.mylunarcore.economy.WalletTccSagaService;
import cn.itcast.demo.mylunarcore.guild.RaidDamageStatisticsService;
import cn.itcast.demo.mylunarcore.ops.GradedCircuitBreakerService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaDeadLetterService;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.player.FastReconnectService;
import cn.itcast.demo.mylunarcore.privacy.PrivacyConsentService;
import cn.itcast.demo.mylunarcore.privacy.UserDataService;
import cn.itcast.demo.mylunarcore.scene.ScenePingService;
import cn.itcast.demo.mylunarcore.settings.UiAdaptConfigService;
import cn.itcast.demo.mylunarcore.social.VoiceSignalingService;
import cn.itcast.demo.mylunarcore.tools.PresentationMockClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 十大体验/生产缺口补齐：联调 Mock、TCC/对账、战斗手感、社交、运维、隐私、灰度、重连、跨端、主题。
 */
@DisplayName("十大缺口补齐业务流程")
class ExperienceGapFillFlowTest {

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
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

    private static <T> ObjectProvider<T> of(T bean) {
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

    @Nested
    @DisplayName("1. 客户端联调 Mock")
    class PresentationMock {
        @Test
        @DisplayName("DISSOLVE 遮罩与 BattleFx 样本可解析")
        void mockCatalog() throws Exception {
            Map<String, Object> cat = PresentationMockClient.previewCatalog();
            assertTrue(cat.containsKey("dissolveB64"));
            byte[] raw = PresentationMockClient.sceneLoadMaskDissolve(20101, 1);
            var mask = cn.itcast.demo.mylunarcore.protocol.SceneSystemProto.SceneLoadMaskInfo.parseFrom(raw);
            assertEquals("DISSOLVE", mask.getTransitionType());
            assertTrue(mask.getDurationMs() > 0);
        }
    }

    @Nested
    @DisplayName("2. 钱包 TCC + 对账")
    class WalletTx {
        @Test
        @DisplayName("Try→Confirm 扣款；Rollback 释放预留")
        void tccFlow() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(anyInt())).thenReturn(Map.of(1, 1000));
            when(wallet.deduct(eq(7), eq(1), eq(100), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 900), "ok"));
            WalletTccSagaService tcc = new WalletTccSagaService(wallet, emptyProvider());
            var tryR = tcc.tryReserve(7, 1, 100, "gacha");
            assertTrue(tryR.ok());
            assertEquals(100, tcc.reservedAmount(7, 1));
            assertTrue(tcc.confirm(tryR.txId()).ok());
            assertEquals(0, tcc.reservedAmount(7, 1));

            var try2 = tcc.tryReserve(7, 1, 50, "x");
            assertTrue(tcc.rollback(try2.txId()).ok());
            assertEquals(0, tcc.reservedAmount(7, 1));
        }

        @Test
        @DisplayName("对账异常可一键修正")
        void reconcileCorrect() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(9)).thenReturn(Map.of(1, 100));
            when(wallet.deduct(eq(9), eq(1), eq(20), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 80), "ok"));
            WalletReconciliationJob job = new WalletReconciliationJob(null, wallet);
            job.putAnomaly(new WalletReconciliationJob.Anomaly(
                    "wr-9-1", 9, 1, 100, 80, 20, WalletReconciliationJob.AnomalyStatus.OPEN, 1L));
            assertTrue(job.correct("wr-9-1", "admin"));
            assertTrue(job.listOpen().isEmpty());
        }
    }

    @Nested
    @DisplayName("2b. Saga 混沌 + DeadLetter")
    class SagaChaos {
        @Test
        @DisplayName("混沌失败应 rollback 并可进 DeadLetter")
        void chaosAndDlq() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(emptyProvider(), emptyProvider());
            PartyMigrateSagaDeadLetterService dlq = new PartyMigrateSagaDeadLetterService(saga);
            var txn = saga.begin("party-1", 100L, "node-b");
            dlq.injectChaosFailNext();
            var r = dlq.advanceWithRetry(txn.txnId(), MigrateState.SERIALIZING, "ticket");
            assertEquals(MigrateState.ROLLBACK, r.state());
            assertTrue(dlq.chaosInjectCount() >= 1);
            dlq.markDeadLetter(txn.txnId(), "test", 4);
            assertEquals(1, dlq.listDeadLetters().size());
        }
    }

    @Nested
    @DisplayName("3. 战斗取消窗口 / QTE / 镜头")
    class BattleFeel {
        @Test
        @DisplayName("取消窗口与优先级")
        void cancelWindow() {
            CancelWindowService svc = new CancelWindowService(emptyProvider());
            var w = svc.open(1L, 10, 1, PlayerInputBufferService.CancelTier.BASIC.priority(), 100);
            assertTrue(svc.isOpen(1L, 10, w.openAtMs() + 50));
            assertTrue(svc.canCancelWith(1L, 10, PlayerInputBufferService.CancelTier.SKILL.priority(), w.openAtMs() + 50));
            assertFalse(svc.canCancelWith(1L, 10, PlayerInputBufferService.CancelTier.BASIC.priority(), w.openAtMs() + 50));
        }

        @Test
        @DisplayName("QTE 成功发放加成")
        void qteBonus() {
            BattleQteService qte = new BattleQteService(emptyProvider());
            var ev = qte.maybeTrigger(1L, 5, true, false);
            assertNotNull(ev);
            var r = qte.respond(ev.qteId(), 5, System.currentTimeMillis());
            assertTrue(r.ok());
            assertEquals(1, qte.consumeActionPoints(1L));
        }

        @Test
        @DisplayName("击杀应带 FOV 冲击")
        void cameraFov() {
            BattleFxComposer.FxHint fx = BattleFxComposer.compose(-900, true, 3001, false, 99);
            assertTrue(fx.cameraFovImpact() > 0);
            assertTrue(fx.cameraShakeIntensity() > 0);
            assertTrue(fx.kill());
        }
    }

    @Nested
    @DisplayName("4. 社交 Ping / Raid DPS / 语音")
    class Social {
        @Test
        @DisplayName("Ping 广播登记")
        void ping() {
            ScenePingService ping = new ScenePingService(emptyProvider());
            ping.registerMember(42L, 1);
            ping.registerMember(42L, 2);
            var r = ping.ping(42L, 1, ScenePingService.PingType.FOCUS_FIRE, 1f, 2f, 3f, 99);
            assertTrue(r.ok());
            assertEquals(1, ping.listRecent(42L, 0).size());
        }

        @Test
        @DisplayName("Raid 伤害排名 + 语音房")
        void raidDps() {
            VoiceSignalingService voice = new VoiceSignalingService();
            RaidDamageStatisticsService stats = new RaidDamageStatisticsService(
                    emptyProvider(), of(voice), emptyProvider());
            stats.onPlayerJoin("raid-1", 1);
            stats.onPlayerJoin("raid-1", 2);
            stats.recordDamage("raid-1", 1, 500);
            stats.recordDamage("raid-1", 2, 200);
            var ranks = stats.rankings("raid-1");
            assertEquals(1, ranks.get(0).playerId());
            assertEquals(1, ranks.get(0).rank());
            assertTrue(voice.joinOrCreate(1, "guild-raid-raid-1").success());
            assertNotNull(voice.joinOrCreate(1, "guild-raid-raid-1").ticket());
        }
    }

    @Nested
    @DisplayName("5. 分级熔断 + HPA 指标")
    class Ops {
        @Test
        @DisplayName("按活动 ID 熔断")
        void gradedBreaker() {
            ActivityCircuitBreakerService global = new ActivityCircuitBreakerService();
            GradedCircuitBreakerService graded = new GradedCircuitBreakerService(global);
            graded.trip(GradedCircuitBreakerService.Scope.ACTIVITY, "act-5000701", "error_spike");
            assertTrue(graded.isBlocked("act-5000701", "ios", "shard-1"));
            assertFalse(graded.isBlocked("act-other", "ios", "shard-1"));
            assertTrue(graded.shouldMailFallback("act-5000701", "ios", "shard-1"));
        }

        @Test
        @DisplayName("HPA/SLA 指标可写入")
        void hpaSla() {
            BusinessMetrics m = new BusinessMetrics(new SimpleMeterRegistry());
            m.setMatchQueueDepth(40);
            m.setBattlePoolUsage(0.85);
            m.setSlaBattleSuccessRate(0.995);
            m.setSlaActionLatencyP95Ms(42);
            assertEquals(0.85, m.battlePoolUsage(), 0.001);
            assertEquals(0.995, m.slaBattleSuccessRate(), 0.001);
        }
    }

    @Nested
    @DisplayName("6. 隐私合规")
    class Privacy {
        @Test
        @DisplayName("导出/软删/同意")
        void exportAndConsent() {
            UserDataService uds = new UserDataService((org.springframework.jdbc.core.JdbcTemplate) null);
            var bundle = uds.export(1001);
            assertEquals(1001, bundle.playerId());
            assertTrue(uds.softDelete(1001).ok());
            assertTrue(uds.isDeleted(1001));

            PrivacyConsentService consent = new PrivacyConsentService();
            var view = consent.presentOnLogin(2, "zh_CN");
            assertTrue(view.guestOnly());
            assertTrue(view.policyUrl().contains("zh-CN"));
            consent.accept(2);
            assertTrue(consent.canPersist(2));
        }

        @Test
        @DisplayName("UID 日志哈希")
        void uidHash() {
            String h1 = StructuredJsonLogger.hashUserId(12345678L);
            String h2 = StructuredJsonLogger.hashUserId(12345678L);
            assertEquals(h1, h2);
            assertEquals("1234", StructuredJsonLogger.userIdPrefix(12345678L));
            assertFalse(StructuredJsonLogger.toJson("INFO", "e", 12345678L, 1, 0, "password=secret")
                    .contains("12345678"));
        }
    }

    @Nested
    @DisplayName("7. 配置灰度自动回滚")
    class GrayRollback {
        @Test
        @DisplayName("错误率飙升自动回滚")
        void autoRollback() {
            LunarCoreProperties props = new LunarCoreProperties();
            ConfigGrayRelease gray = new ConfigGrayRelease(props);
            gray.updatePolicy(new ConfigGrayRelease.GrayPolicy(true, Set.of(1, 2, 3), Set.of(), "canary"));
            ConfigGrayAutoRollbackService auto = new ConfigGrayAutoRollbackService(gray, emptyProvider());
            auto.snapshotStable();
            gray.updatePolicy(new ConfigGrayRelease.GrayPolicy(true, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Set.of(), "wide"));
            for (int i = 0; i < 60; i++) {
                auto.recordRequest(i % 5 == 0);
            }
            assertTrue(auto.isRolledBack() || auto.evaluate());
        }
    }

    @Nested
    @DisplayName("8. 快速重连")
    class Reconnect {
        @Test
        @DisplayName("断线暂停战斗，重连恢复")
        void fastReconnect() {
            FastReconnectService svc = new FastReconnectService(emptyProvider(), emptyProvider());
            String sid = svc.cacheSnapshot(8, 1L, 20101, 1, 1f, 2f, 3f, 99L, "{}");
            svc.onDisconnect(8);
            assertTrue(svc.isBattlePaused(99L));
            var r = svc.reconnect(sid, 8);
            assertTrue(r.ok());
            assertFalse(svc.isBattlePaused(99L));
            assertTrue(svc.probe(sid));
        }
    }

    @Nested
    @DisplayName("9. 跨端 UI 适配")
    class CrossPlatform {
        @Test
        @DisplayName("分辨率驱动 ui_scale 与热区")
        void uiScale() {
            UiAdaptConfigService ui = new UiAdaptConfigService(emptyProvider());
            var cfg = ui.resolveAndPush(3, 1920, 1080, "pc_km");
            assertEquals("pc", cfg.deviceClass());
            assertTrue(cfg.uiScale() >= 1.0f);
            assertNotNull(ui.getHeatmap(3));
        }
    }

    @Nested
    @DisplayName("10. 版本主题 / 战令主题")
    class Theme {
        @Test
        @DisplayName("主题视图与战令绑定")
        void themeBinding() {
            VersionThemeService themes = new VersionThemeService(
                    new ObjectMapper(), emptyProvider(), emptyProvider(), "data/VersionThemeConfigs.json");
            themes.reload();
            assertNotNull(themes.currentView().current());
            themes.preloadNext();
            BattlePassThemeService bp = new BattlePassThemeService(of(themes), emptyProvider());
            var bind = bp.refresh();
            assertNotNull(bind.themeId());
            assertTrue(bind.storyQuestTag().startsWith("story_theme_"));
        }
    }
}
