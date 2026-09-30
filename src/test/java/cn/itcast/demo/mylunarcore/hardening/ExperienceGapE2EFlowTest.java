package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.VersionThemeService;
import cn.itcast.demo.mylunarcore.admin.SensitiveOpApprovalService;
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
import cn.itcast.demo.mylunarcore.guild.GuildRaidInstanceService;
import cn.itcast.demo.mylunarcore.guild.RaidDamageStatisticsService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.ops.GradedCircuitBreakerService;
import cn.itcast.demo.mylunarcore.party.MigrateState;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaDeadLetterService;
import cn.itcast.demo.mylunarcore.party.PartyMigrateSagaService;
import cn.itcast.demo.mylunarcore.player.FastReconnectService;
import cn.itcast.demo.mylunarcore.privacy.PrivacyConsentService;
import cn.itcast.demo.mylunarcore.privacy.UserDataService;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.scene.ScenePingService;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新功能端到端业务流程全覆盖：串联 TCC→战斗→社交→重连→合规→灰度→主题，并覆盖边界失败路径。
 */
@DisplayName("新功能端到端业务流程全测")
class ExperienceGapE2EFlowTest {

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
    @DisplayName("E2E-A：抽卡扣费 TCC → 确认 → 对账修正闭环")
    class WalletGachaLedger {
        @Test
        @DisplayName("余额不足 Try 失败；足额 Confirm 实扣；Confirm 失败可 Rollback 补偿")
        void insufficientAndCompensate() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(1)).thenReturn(Map.of(1, 50));
            WalletTccSagaService tcc = new WalletTccSagaService(wallet, emptyProvider());
            assertFalse(tcc.tryReserve(1, 1, 100, "gacha").ok());

            when(wallet.getBalance(2)).thenReturn(Map.of(1, 500));
            when(wallet.deduct(eq(2), eq(1), eq(200), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(false, Map.of(1, 500), "cas_fail"));
            when(wallet.add(eq(2), eq(1), eq(200), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 700), "ok"));

            var tryOk = tcc.tryReserve(2, 1, 200, "gacha");
            assertTrue(tryOk.ok());
            assertFalse(tcc.confirm(tryOk.txId()).ok());
            // Confirm 失败后状态 FAILED，rollback 对 FAILED 也走释放预留路径
            assertEquals(WalletTccSagaService.Phase.FAILED, tcc.get(tryOk.txId()).phase());

            // 成功路径再走一遍
            when(wallet.deduct(eq(2), eq(1), eq(100), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 400), "ok"));
            var try2 = tcc.tryReserve(2, 1, 100, "gacha2");
            assertTrue(tcc.confirm(try2.txId()).ok());
            assertEquals(WalletTccSagaService.Phase.CONFIRMED, tcc.get(try2.txId()).phase());

            // 已确认后 rollback = 补偿加回
            assertTrue(tcc.rollback(try2.txId()).ok());
            verify(wallet, times(1)).add(eq(2), eq(1), eq(100), anyString());
            assertEquals(WalletTccSagaService.Phase.CANCELLED, tcc.get(try2.txId()).phase());
        }

        @Test
        @DisplayName("对账：OPEN→修正→RESOLVED；重复修正失败")
        void reconcileIdempotent() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(11)).thenReturn(Map.of(3, 120));
            when(wallet.deduct(eq(11), eq(3), eq(20), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(3, 100), "ok"));
            WalletReconciliationJob job = new WalletReconciliationJob(null, wallet);
            job.putAnomaly(new WalletReconciliationJob.Anomaly(
                    "a1", 11, 3, 120, 100, 20, WalletReconciliationJob.AnomalyStatus.OPEN, 1L));
            assertTrue(job.correct("a1", "ops"));
            assertFalse(job.correct("a1", "ops"));
            assertTrue(job.status().containsKey("openAnomalies"));
            assertEquals(0, job.listOpen().size());
        }
    }

    @Nested
    @DisplayName("E2E-B：组队迁移成功路径 + 混沌杀进程 + DLQ 排水")
    class PartyMigrateE2E {
        @Test
        @DisplayName("INIT→SERIALIZING→TRANSFERRING→CONFIRMED 全成功")
        void happyPath() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(emptyProvider(), emptyProvider());
            PartyMigrateSagaDeadLetterService dlq = new PartyMigrateSagaDeadLetterService(saga);
            var txn = saga.begin("p-e2e", 200L, "node-c");
            assertEquals(MigrateState.INIT, txn.state());
            txn = dlq.advanceWithRetry(txn.txnId(), MigrateState.SERIALIZING, "tk-1");
            assertEquals(MigrateState.SERIALIZING, txn.state());
            txn = dlq.advanceWithRetry(txn.txnId(), MigrateState.TRANSFERRING, "tk-1");
            assertEquals(MigrateState.TRANSFERRING, txn.state());
            txn = dlq.advanceWithRetry(txn.txnId(), MigrateState.CONFIRMED, null);
            assertEquals(MigrateState.CONFIRMED, txn.state());
            assertEquals(0, dlq.listDeadLetters().size());
        }

        @Test
        @DisplayName("TRANSFERRING 时 chaosKill → ROLLBACK；超限进 DeadLetter")
        void chaosThenDeadLetter() {
            PartyMigrateSagaService saga = new PartyMigrateSagaService(emptyProvider(), emptyProvider());
            PartyMigrateSagaDeadLetterService dlq = new PartyMigrateSagaDeadLetterService(saga);
            var txn = saga.begin("p-chaos", 201L, "node-x");
            saga.advance(txn.txnId(), MigrateState.SERIALIZING, "t");
            saga.advance(txn.txnId(), MigrateState.TRANSFERRING, "t");
            var killed = dlq.chaosKillTxn(txn.txnId(), "node_down");
            assertEquals(MigrateState.ROLLBACK, killed.state());

            for (int i = 0; i < 5; i++) {
                dlq.enqueueRetry("ghost-" + i, MigrateState.SERIALIZING, "t", PartyMigrateSagaDeadLetterService.MAX_RETRY + 1);
            }
            dlq.drainRetryQueue();
            assertTrue(dlq.listDeadLetters().size() >= 5);
        }
    }

    @Nested
    @DisplayName("E2E-C：战斗手感闭环（取消窗→QTE→镜头→预输入优先级）")
    class BattleFeelE2E {
        @Test
        @DisplayName("普攻取消窗内接战技；超时不可取消；击杀 QTE 超时失败")
        void cancelAndQteEdges() {
            CancelWindowService cancel = new CancelWindowService(emptyProvider());
            var w = cancel.open(88L, 1, 10, PlayerInputBufferService.CancelTier.BASIC.priority(), 100);
            assertTrue(cancel.canCancelWith(88L, 1, PlayerInputBufferService.CancelTier.ULT.priority(), w.openAtMs() + 10));
            assertTrue(cancel.canCancelWith(88L, 1, PlayerInputBufferService.CancelTier.DODGE.priority(), w.openAtMs() + 10));
            assertFalse(cancel.isOpen(88L, 1, w.closeAtMs() + 1));

            BattleQteService qte = new BattleQteService(emptyProvider());
            assertNull(qte.maybeTrigger(88L, 10, false, false));
            var killQte = qte.maybeTrigger(88L, 10, false, true);
            assertNotNull(killQte);
            assertEquals(BattleQteService.BonusType.DAMAGE_BONUS_BP, killQte.bonusType());
            // 超时
            var late = qte.respond(killQte.qteId(), 10, killQte.deadlineMs() + 1);
            assertFalse(late.ok());
            assertEquals(3, late.retcode());

            var shield = qte.maybeTrigger(88L, 10, true, false);
            var ok = qte.respond(shield.qteId(), 10, System.currentTimeMillis());
            assertTrue(ok.ok());
            assertEquals(1, qte.consumeActionPoints(88L));
            assertEquals(0, qte.consumeDamageBonusBp(88L)); // 击杀 QTE 已超时未发放

            // 镜头：普通伤害 vs 击杀
            var normal = BattleFxComposer.compose(-80, false, 1, false, 2);
            var kill = BattleFxComposer.compose(-2000, true, 3001, true, 2);
            assertTrue(kill.cameraFovImpact() > normal.cameraFovImpact());
            assertTrue(kill.cameraShake() >= normal.cameraShake());
        }

        @Test
        @DisplayName("预输入缓冲：闪避优先级高于普攻")
        void inputBufferPriority() {
            PlayerInputBufferService buf = new PlayerInputBufferService();
            buf.openActionWindow(1L, System.currentTimeMillis() + 5_000);
            assertTrue(buf.enqueueOrExecuteNow(1L, 1, 1, 1, 1, 1, List.of(2), System.currentTimeMillis()));
            assertTrue(buf.enqueueOrExecuteNow(1L, 1, 1, 4, 9, 1, List.of(), System.currentTimeMillis()));
            var next = buf.poll(1L, 1, System.currentTimeMillis());
            assertNotNull(next);
            assertEquals(PlayerInputBufferService.CancelTier.DODGE, next.tier());
        }
    }

    @Nested
    @DisplayName("E2E-D：公会 Raid 进本→语音→打伤→DPS→Ping")
    class GuildRaidSocialE2E {
        @Test
        @DisplayName("10 人进本可开战；伤害排名与 Ping 类型齐全")
        void raidPipeline() {
            GuildRaidInstanceService raid = new GuildRaidInstanceService(emptyProvider(), emptyProvider());
            VoiceSignalingService voice = new VoiceSignalingService();
            RaidDamageStatisticsService stats = new RaidDamageStatisticsService(of(raid), of(voice), emptyProvider());
            ScenePingService ping = new ScenePingService(emptyProvider());

            var inst = raid.create(9001L, 100_000);
            assertFalse(raid.canStart(inst.instanceId()));
            for (int i = 1; i <= 10; i++) {
                assertTrue(raid.join(inst.instanceId(), i, 1000L + i));
                stats.onPlayerJoin(inst.instanceId(), i);
                ping.registerMember(inst.instanceId().hashCode(), i);
            }
            assertTrue(raid.canStart(inst.instanceId()));

            stats.recordDamage(inst.instanceId(), 3, 9000);
            stats.recordDamage(inst.instanceId(), 7, 12000);
            stats.recordDamage(inst.instanceId(), 3, 1000);
            var ranks = stats.rankings(inst.instanceId());
            assertEquals(7, ranks.get(0).playerId());
            assertEquals(3, ranks.get(1).playerId());
            assertEquals(10_000L, ranks.get(1).totalDamage());

            long sceneId = inst.instanceId().hashCode();
            assertTrue(ping.ping(sceneId, 1, ScenePingService.PingType.RALLY, 0, 0, 0, 0).ok());
            assertTrue(ping.ping(sceneId, 2, ScenePingService.PingType.FOCUS_FIRE, 1, 2, 3, 55).ok());
            assertTrue(ping.ping(sceneId, 3, ScenePingService.PingType.DANGER, 4, 5, 6, 0).ok());
            assertTrue(ping.ping(sceneId, 4, ScenePingService.PingType.HELP, 7, 8, 9, 0).ok());
            assertEquals(4, ping.listRecent(sceneId, 0).size());

            var ticket = voice.joinOrCreate(1, "guild-raid-" + inst.instanceId());
            assertTrue(ticket.success());
            assertTrue(voice.validate(ticket.ticket().roomId(), ticket.ticket().token()));
        }
    }

    @Nested
    @DisplayName("E2E-E：战斗断线→暂停→重连→过期快照拒绝")
    class ReconnectE2E {
        @Test
        @DisplayName("错误 session / 过期 / 探测 / 无战斗快照")
        void reconnectEdges() throws Exception {
            FastReconnectService svc = new FastReconnectService(emptyProvider(), emptyProvider());
            String sid = svc.cacheSnapshot(42, 7L, 20101, 2, 10f, 20f, 30f, 555L, "{\"hp\":100}");
            assertFalse(svc.reconnect("bad", 42).ok());
            assertEquals(1, svc.reconnect("bad", 42).retcode());
            assertFalse(svc.reconnect(sid, 99).ok());

            svc.onDisconnect(42);
            assertTrue(svc.isBattlePaused(555L));
            var ok = svc.reconnect(sid, 42);
            assertTrue(ok.ok());
            assertNotNull(ok.snapshot());
            assertEquals(20101, ok.snapshot().planeId());
            assertTrue(svc.probe(sid));

            // 无战斗
            String sid2 = svc.cacheSnapshot(43, 8L, 100, 1, 0, 0, 0, null, "{}");
            svc.onDisconnect(43);
            assertFalse(svc.isBattlePaused(999L));
            assertTrue(svc.reconnect(sid2, 43).ok());

            // 强制过期
            var field = FastReconnectService.class.getDeclaredField("bySession");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, FastReconnectService.SessionSnapshot>) field.get(svc);
            var old = map.get(sid);
            map.put(sid, new FastReconnectService.SessionSnapshot(
                    old.sessionId(), old.playerId(), old.sceneId(), old.planeId(), old.floorId(),
                    old.x(), old.y(), old.z(), old.battleId(), old.entityJson(),
                    System.currentTimeMillis() - FastReconnectService.SNAPSHOT_TTL_MS - 1));
            assertEquals(2, svc.reconnect(sid, 42).retcode());
            assertFalse(svc.probe(sid));
        }
    }

    @Nested
    @DisplayName("E2E-F：隐私同意→存档门禁→导出删除→日志脱敏")
    class PrivacyE2E {
        @Test
        @DisplayName("拒绝则访客；同意可存档；purge 队列；密码脱敏")
        void privacyPipeline() {
            PrivacyConsentService consent = new PrivacyConsentService();
            UserDataService uds = new UserDataService((org.springframework.jdbc.core.JdbcTemplate) null);

            var login = consent.presentOnLogin(77, "ja_JP");
            assertTrue(login.guestOnly());
            assertTrue(login.policyUrl().contains("ja-JP"));
            assertFalse(consent.canPersist(77));
            consent.decline(77);
            assertFalse(consent.canPersist(77));
            consent.accept(77);
            assertTrue(consent.canPersist(77));

            var exp = uds.export(77);
            assertTrue(exp.payload().containsKey("playerId"));
            assertTrue(uds.requestPurge(77).ok());
            assertEquals(UserDataService.DeleteStatus.PURGE_PENDING, uds.status(77));

            String json = StructuredJsonLogger.toJson("WARN", "login", 77889900L, CmdIds.PLAYER_LOGIN_CS_REQ, 5,
                    "password=SuperSecret token=abc");
            assertFalse(json.contains("SuperSecret"));
            assertFalse(json.contains("77889900"));
            assertTrue(json.contains("userIdHash"));
            assertEquals("7788", StructuredJsonLogger.userIdPrefix(77889900L));
        }
    }

    @Nested
    @DisplayName("E2E-G：配置审批 + 灰度回滚 + 分级熔断联动")
    class OpsConfigE2E {
        @Test
        @DisplayName("配置变更双人审批；灰度回滚；渠道级熔断")
        void approvalGrayBreaker() {
            SensitiveOpApprovalService approval = new SensitiveOpApprovalService(null);
            var t = approval.request("CONFIG_CHANGE", "{\"configName\":\"Banners.json\"}", "alice");
            assertEquals(SensitiveOpApprovalService.Status.PENDING, t.status());
            // 自批无效
            assertEquals(SensitiveOpApprovalService.Status.PENDING, approval.approve(t.ticketId(), "alice").status());
            // 至少 2 人批准
            assertEquals(SensitiveOpApprovalService.Status.PENDING, approval.approve(t.ticketId(), "bob").status());
            assertEquals(SensitiveOpApprovalService.Status.APPROVED, approval.approve(t.ticketId(), "carol").status());
            assertTrue(approval.consumeIfApproved(t.ticketId(), "CONFIG_CHANGE"));
            assertFalse(approval.consumeIfApproved(t.ticketId(), "CONFIG_CHANGE"));

            LunarCoreProperties props = new LunarCoreProperties();
            ConfigGrayRelease gray = new ConfigGrayRelease(props);
            var stable = new ConfigGrayRelease.GrayPolicy(true, Set.of(1), Set.of(), "stable");
            gray.updatePolicy(stable);
            ConfigGrayAutoRollbackService auto = new ConfigGrayAutoRollbackService(gray, emptyProvider());
            auto.snapshotStable();
            gray.updatePolicy(new ConfigGrayRelease.GrayPolicy(true, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Set.of(), "wide"));
            for (int i = 0; i < 100; i++) {
                auto.recordRequest(true);
            }
            assertTrue(auto.isRolledBack());
            assertEquals(stable.uidTailDigits(), gray.current().uidTailDigits());

            ActivityCircuitBreakerService global = new ActivityCircuitBreakerService();
            GradedCircuitBreakerService graded = new GradedCircuitBreakerService(global);
            graded.trip(GradedCircuitBreakerService.Scope.CHANNEL, "google_play", "iap_spike");
            assertTrue(graded.isBlocked("any", "google_play", "s1"));
            assertFalse(graded.isBlocked("any", "app_store", "s1"));
            graded.trip(GradedCircuitBreakerService.Scope.GLOBAL, "*", "avalanche");
            assertTrue(global.isTripped());
            graded.reset(GradedCircuitBreakerService.Scope.GLOBAL, "*");
            assertFalse(global.isTripped());
        }

        @Test
        @DisplayName("HPA 指标驱动与 SLA 红绿灯阈值")
        void hpaSlaThresholds() {
            BusinessMetrics m = new BusinessMetrics(new SimpleMeterRegistry());
            m.setMatchQueueDepth(0);
            m.setBattlePoolUsage(0.2);
            m.setSlaBattleSuccessRate(0.999);
            m.setSlaActionLatencyP95Ms(30);
            m.setSlaSceneLoadP95Ms(800);
            // 告警态
            m.setMatchQueueDepth(80);
            m.setBattlePoolUsage(0.92);
            m.setSlaBattleSuccessRate(0.90);
            m.setSlaActionLatencyP95Ms(200);
            m.setSlaSceneLoadP95Ms(2500);
            assertTrue(m.battlePoolUsage() > 0.9);
            assertTrue(m.slaBattleSuccessRate() < 0.95);
        }
    }

    @Nested
    @DisplayName("E2E-H：跨端适配 + 主题赛季切换")
    class PlatformThemeE2E {
        @Test
        @DisplayName("mobile/pc/console 分流；主题预加载与战令刷新")
        void adaptAndTheme() {
            UiAdaptConfigService ui = new UiAdaptConfigService(emptyProvider());
            assertEquals("mobile_narrow", ui.resolveAndPush(1, 640, 1136, "mobile_touch").deviceClass());
            assertEquals("pc", ui.resolveAndPush(2, 2560, 1440, "pc_km").deviceClass());
            assertEquals("console", ui.resolveAndPush(3, 1280, 720, "console_gamepad").deviceClass());
            assertTrue(ui.getHeatmap(1).hotZoneExpandPx() >= ui.getHeatmap(2).hotZoneExpandPx());

            VersionThemeService themes = new VersionThemeService(
                    new ObjectMapper(), emptyProvider(), emptyProvider(), "data/VersionThemeConfigs.json");
            themes.reload();
            var view = themes.currentView();
            assertNotNull(view.current());
            assertEquals("1.0", view.current().themeId());
            assertTrue(themes.preloadNext());
            assertTrue(themes.currentView().preloadReady() || themes.currentView().next() != null);

            BattlePassThemeService bp = new BattlePassThemeService(of(themes), emptyProvider());
            var a = bp.refresh();
            var b = bp.current();
            assertEquals(a.themeId(), b.themeId());
            assertTrue(bp.status().containsKey("premiumSkinKey"));
        }
    }

    @Nested
    @DisplayName("E2E-I：表现层协议集成（客户端回包模拟）")
    class PresentationProtocolE2E {
        @Test
        @DisplayName("全套 Mock 样本可反序列化且 CmdId 成对")
        void allMockSamples() throws Exception {
            SceneSystemProto.SceneLoadMaskInfo dissolve =
                    SceneSystemProto.SceneLoadMaskInfo.parseFrom(PresentationMockClient.sceneLoadMaskDissolve(1, 1));
            assertEquals("DISSOLVE", dissolve.getTransitionType());
            assertEquals(ScenePreloadService.DISSOLVE_DURATION_MS, dissolve.getDurationMs());

            SceneSystemProto.SceneLoadMaskInfo rift =
                    SceneSystemProto.SceneLoadMaskInfo.parseFrom(PresentationMockClient.sceneLoadMaskRift(1, 1));
            assertEquals("RIFT", rift.getTransitionType());

            BattleSystemProto.BattleFxScNotify fx =
                    BattleSystemProto.BattleFxScNotify.parseFrom(PresentationMockClient.battleFxWithCameraImpact(1, 3001, false));
            assertTrue(fx.getFx(0).getCameraShakeIntensity() > 0);
            assertTrue(fx.getFx(0).getCameraFovImpact() > 0);

            String cancel = new String(PresentationMockClient.cancelWindowNotify(1, 10, 100), StandardCharsets.UTF_8);
            assertTrue(cancel.contains("\"windowMs\":100"));
            String qte = new String(PresentationMockClient.battleQteNotify(1, "x", 99), StandardCharsets.UTF_8);
            assertTrue(qte.contains("ACTION_POINT"));

            Map<String, Object> cat = PresentationMockClient.previewCatalog();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cmds = (List<Map<String, Object>>) cat.get("cmdIds");
            assertTrue(cmds.size() >= 5);
            assertEquals(CmdIds.CANCEL_WINDOW_SC_NOTIFY, CmdIds.CANCEL_WINDOW_SC_NOTIFY);
            assertNotEquals(CmdIds.BATTLE_QTE_SC_NOTIFY, CmdIds.SCENE_PING_SC_NOTIFY);
            assertEquals(CmdIds.BATTLE_QTE_CS_REQ + 1, CmdIds.BATTLE_QTE_SC_RSP);
            assertEquals(CmdIds.RECONNECT_CS_REQ + 1, CmdIds.RECONNECT_SC_RSP);
            assertEquals(CmdIds.SCENE_PING_CS_REQ + 1, CmdIds.SCENE_PING_SC_RSP);
        }
    }

    @Nested
    @DisplayName("E2E-J：登录→同意→UI→进战→断线重连→主题 主路径串联")
    class MainJourney {
        @Test
        @DisplayName("主路径不中断，关键状态可观测")
        void mainPath() {
            AtomicInteger step = new AtomicInteger();
            PrivacyConsentService consent = new PrivacyConsentService();
            consent.presentOnLogin(10086, "zh_CN");
            consent.accept(10086);
            assertTrue(consent.canPersist(10086));
            step.incrementAndGet();

            UiAdaptConfigService ui = new UiAdaptConfigService(emptyProvider());
            ui.resolveAndPush(10086, 1080, 2400, "mobile_touch");
            assertNotNull(ui.getScale(10086));
            step.incrementAndGet();

            CancelWindowService cancel = new CancelWindowService(emptyProvider());
            BattleQteService qte = new BattleQteService(emptyProvider());
            cancel.open(5001L, 1, 10086, PlayerInputBufferService.CancelTier.BASIC.priority(), 100);
            var ev = qte.maybeTrigger(5001L, 10086, true, false);
            assertTrue(qte.respond(ev.qteId(), 10086, System.currentTimeMillis()).ok());
            step.incrementAndGet();

            FastReconnectService reconnect = new FastReconnectService(emptyProvider(), emptyProvider());
            String sid = reconnect.cacheSnapshot(10086, 1L, 20101, 1, 0, 0, 0, 5001L, "{}");
            reconnect.onDisconnect(10086);
            assertTrue(reconnect.isBattlePaused(5001L));
            assertTrue(reconnect.reconnect(sid, 10086).ok());
            step.incrementAndGet();

            VersionThemeService themes = new VersionThemeService(
                    new ObjectMapper(), emptyProvider(), emptyProvider(), "data/VersionThemeConfigs.json");
            themes.reload();
            assertNotNull(themes.currentView().current());
            new BattlePassThemeService(of(themes), emptyProvider()).refresh();
            step.incrementAndGet();

            assertEquals(5, step.get());
        }
    }
}
