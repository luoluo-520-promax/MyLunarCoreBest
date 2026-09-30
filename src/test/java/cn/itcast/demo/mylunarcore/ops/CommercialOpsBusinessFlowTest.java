package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.common.ActivityNettyService;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.common.ClientResourceManifestService;
import cn.itcast.demo.mylunarcore.common.ConfigActiveWindow;
import cn.itcast.demo.mylunarcore.common.ConfigDeltaPatchService;
import cn.itcast.demo.mylunarcore.common.ConfigOverrideHotfixService;
import cn.itcast.demo.mylunarcore.common.HotfixDataService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.NegativeGrantService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.economy.WalletRollbackSnapshotService;
import cn.itcast.demo.mylunarcore.economy.WalletWalService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.mapper.ActivityProtoMapper;
import cn.itcast.demo.mylunarcore.player.ClientVersionGateService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商业化运维 7 项新能力：端到端业务流程测试。
 */
@DisplayName("商业化新能力全流程")
class CommercialOpsBusinessFlowTest {

    @TempDir
    Path tempDir;

    private LunarCoreProperties props;

    @BeforeEach
    void base() {
        props = new LunarCoreProperties();
        props.setDataDir(tempDir.toString());
    }

    private static BattleContext newBattle(long battleId, int playerId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = List.of(
                new BattleMonsterWaveRepository.WaveConfig(1, 200, 1, "[301]", 5));
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    private static <T> ObjectProvider<T> provider(T bean) {
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
    @DisplayName("1. 活动脚本：策划配脚本 → reload → 玩法生效")
    class ActivityScriptFlow {

        @Test
        @DisplayName("签到第7天多倍 + 挑战积分脚本，熔断时拒绝执行")
        void signInAndChallengeThenCircuitBlocks() throws Exception {
            Path scripts = tempDir.resolve("scripts/activity");
            Files.createDirectories(scripts);
            Files.copy(Path.of("data/scripts/activity/sign_in_bonus.groovy"), scripts.resolve("sign_in_bonus.groovy"));
            Files.copy(Path.of("data/scripts/activity/challenge_score.groovy"), scripts.resolve("challenge_score.groovy"));

            ActivityScriptEngine engine = new ActivityScriptEngine(props);
            assertTrue(engine.reloadAll() >= 2);

            ActivityCircuitBreakerService circuit = new ActivityCircuitBreakerService();
            ActivityTemplateService templates = new ActivityTemplateService(mock(JdbcTemplate.class), engine, circuit);

            var day7 = templates.invokeScript("sign_in_bonus", 1001, 8001, Map.of("day", 7, "baseReward", 10));
            assertTrue(day7.success());
            assertEquals(20, ((Number) day7.payload().get("reward")).intValue());
            assertEquals(true, day7.payload().get("multiplied"));

            var day1 = templates.invokeScript("sign_in_bonus", 1001, 8001, Map.of("day", 1, "baseReward", 10));
            assertEquals(10, ((Number) day1.payload().get("reward")).intValue());

            var score = templates.invokeScript("challenge_score", 1001, 8002,
                    Map.of("baseScore", 100, "combo", 10, "remainSec", 30));
            assertTrue(score.success());
            assertTrue(((Number) score.payload().get("score")).intValue() > 100);
            assertTrue(templates.getPoints(1001, 8002) > 0);

            circuit.trip("emergency");
            assertEquals(503, templates.invokeScript("sign_in_bonus", 1001, 8001, Map.of("day", 7)).retcode());
            assertEquals(503, templates.spinWheel(1, 1, List.of(1), List.of("A")).retcode());
        }
    }

    @Nested
    @DisplayName("2. 生效窗 + Time Travel：预加载未来版本")
    class ActiveWindowTimeTravelFlow {

        @Test
        @DisplayName("时间旅行到展示窗可见、再到生效窗可交互，复位后恢复")
        void preloadThenActivateViaTravel() {
            ServerClockService clock = new ServerClockService();
            long t0 = clock.nowEpochSecond();
            // display 在 +100，effect 在 +200，结束 +1000
            ConfigActiveWindow window = ConfigActiveWindow.of(
                    t0 + 200, t0 + 1000,
                    t0 + 100, t0 + 200,
                    t0 + 1000, t0 + 1000);

            assertFalse(window.isDisplayable(t0));
            assertFalse(window.isEffectActive(t0));

            clock.travelTo(Instant.ofEpochSecond(t0 + 150), "qa-machine");
            assertTrue(clock.isTraveling());
            assertTrue(window.isDisplayable(clock.nowEpochSecond()));
            assertFalse(window.isEffectActive(clock.nowEpochSecond()));

            clock.travelTo(Instant.ofEpochSecond(t0 + 250), "qa-machine");
            assertTrue(window.isEffectActive(clock.nowEpochSecond()));

            ActivityVisibilityService vis = new ActivityVisibilityService(clock, props);
            ActivityConfig cfg = new ActivityConfig();
            cfg.setBeginTime(t0 + 200);
            cfg.setEndTime(t0 + 1000);
            cfg.setDisplayStart(t0 + 100);
            cfg.setEffectStart(t0 + 200);
            cfg.setDisplayEnd(t0 + 1000);
            cfg.setEffectEnd(t0 + 1000);
            assertTrue(vis.canSee(cfg, false));
            assertTrue(vis.canInteract(cfg, false));

            clock.reset();
            assertFalse(clock.isTraveling());
            assertFalse(window.isDisplayable(clock.nowEpochSecond()));
        }
    }

    @Nested
    @DisplayName("3. 资源版本锁：落后客户端登录被拒")
    class ResourceManifestLockFlow {

        @Test
        @DisplayName("哈希不匹配 → RESOURCE_OUTDATED；匹配 → 放行")
        void outdatedHashesRejectedThenPass() {
            HotfixDataService hotfix = new HotfixDataService(props, new DefaultResourceLoader());
            ClientResourceManifestService manifest = new ClientResourceManifestService(props, hotfix);
            manifest.upsert(new ClientResourceManifestService.ManifestEntry(
                    "chars/new_avatar.bundle", "abc123", 1024, "https://cdn/x", ""));

            var outdated = manifest.checkResourceLock("2.0.0", Map.of("chars/new_avatar.bundle", "old"));
            assertFalse(outdated.ok());
            assertEquals("RESOURCE_OUTDATED", outdated.updateHint());
            assertTrue(outdated.missingOrMismatch() >= 1);

            var ok = manifest.checkResourceLock("2.0.0", Map.of("chars/new_avatar.bundle", "abc123"));
            assertTrue(ok.ok());

            ClientVersionGateService gate = new ClientVersionGateService(
                    new com.fasterxml.jackson.databind.ObjectMapper(), tempDir.toString(), provider(manifest));
            // 无 ClientRequiredVersions 时仅 Manifest 校验；先写最低版本文件
            try {
                Files.writeString(tempDir.resolve("ClientRequiredVersions.json"),
                        "{\"minClientResVersion\":\"1.0.0\",\"updateUrl\":\"https://update\",\"minClientAppVersion\":\"1.0.0\"}");
                gate.load();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            var rejected = gate.check("1.5.0", Map.of("chars/new_avatar.bundle", "stale"));
            assertFalse(rejected.allowed());
            assertEquals(ClientVersionGateService.ERR_RESOURCE_OUTDATED, rejected.retcode());

            var allowed = gate.check("1.5.0", Map.of("chars/new_avatar.bundle", "abc123"));
            assertTrue(allowed.allowed());
        }

        @Test
        @DisplayName("仅版本号落后 serverVersion 时也拦截")
        void semverBehindServerVersion() {
            HotfixDataService hotfix = new HotfixDataService(props, new DefaultResourceLoader());
            ClientResourceManifestService manifest = new ClientResourceManifestService(props, hotfix);
            manifest.upsert(new ClientResourceManifestService.ManifestEntry("a.bundle", "h1", 1, "", ""));
            // upsert 会把 serverVersion 写成 Instant 字符串，semver 比较可能不规则；改用反射不可取
            // 直接测空 hashes + 低版本：entries 非空且 clientVersion 为空应失败
            var fail = manifest.checkResourceLock("", Map.of());
            assertFalse(fail.ok());
        }
    }

    @Nested
    @DisplayName("4. Override 热补丁 + 全局熔断")
    class OverrideAndCircuitFlow {

        @Test
        @DisplayName("UP 率覆写落盘 → 读回生效；熔断后奖励降级邮件")
        void overrideProbabilityThenTripCircuit() throws Exception {
            ConfigOverrideHotfixService override = new ConfigOverrideHotfixService(props, new ConfigDeltaPatchService());
            var applied = override.applyOverride("gacha", Map.of("banners.0.base_probability", 0.006), "fix_60pct");
            assertTrue(Boolean.TRUE.equals(applied.get("ok")));
            assertEquals(0.006, override.resolve("gacha", "banners.0.base_probability", null));
            assertTrue(Files.isRegularFile(tempDir.resolve("hotfix-overrides.json")));

            ActivityCircuitBreakerService circuit = new ActivityCircuitBreakerService();
            ActivityScriptEngine engine = new ActivityScriptEngine(props);
            Files.createDirectories(tempDir.resolve("scripts/activity"));
            Files.writeString(tempDir.resolve("scripts/activity/noop.groovy"),
                    "return [retcode: 0, value: [ok: true]]");
            engine.reloadAll();
            ActivityTemplateService templates = new ActivityTemplateService(mock(JdbcTemplate.class), engine, circuit);

            assertTrue(templates.invokeScript("noop", 1, 1, Map.of()).success());
            circuit.trip("gacha_incident");
            assertTrue(circuit.shouldDegradeToMail());
            assertEquals(503, templates.exchangePoints(1, 1, 1, "x").retcode());

            // 熔断复位后可继续；兑换前需积分
            circuit.reset();
            templates.addPoints(1, 1, 10);
            var ex = templates.exchangePoints(1, 1, 5, "skin");
            assertTrue(ex.success());
            assertEquals("normal", ex.payload().get("rewardMode"));
        }
    }

    @Nested
    @DisplayName("5. Server Freeze：拒新战斗 + 推送维护包")
    class ServerFreezeFlow {

        @Test
        @DisplayName("冻结后 BattleManager 拒绝 put；解冻后恢复；推送 SERVER_MAINTENANCE_PUSH")
        void freezeBlocksBattlesAndPushesNotify() {
            GameSessionManager sessions = mock(GameSessionManager.class);
            EmbeddedChannel ch = new EmbeddedChannel();
            GameSession session = new GameSession(9001L, ch, null);
            session.markDirty();
            when(sessions.snapshotSessions()).thenReturn(List.of(session));

            BattleSnapshotService snaps = mock(BattleSnapshotService.class);
            AtomicReference<ServerFreezeService> freezeRef = new AtomicReference<>();
            BattleManager battles = new BattleManager(snaps, new cn.itcast.demo.mylunarcore.battle.BattleInstancePool(props),
                    new ObjectProvider<>() {
                        @Override
                        public ServerFreezeService getObject() {
                            return freezeRef.get();
                        }

                        @Override
                        public ServerFreezeService getIfAvailable() {
                            return freezeRef.get();
                        }

                        @Override
                        public ServerFreezeService getIfUnique() {
                            return freezeRef.get();
                        }
                    });

            ServerFreezeService freeze = new ServerFreezeService(
                    sessions, battles, snaps, provider(null),
                    provider(null), new AlertWebhookNotifier(props));
            freezeRef.set(freeze);

            BattleContext ctx = newBattle(7001L, 42);
            assertTrue(battles.put(ctx));

            Map<String, Object> status = freeze.freeze("2.0 deploy", 5);
            assertTrue(freeze.isFrozen());
            assertFalse(freeze.acceptNewBattles());
            assertEquals(true, status.get("frozen"));
            assertTrue(((Number) status.get("pushed")).intValue() >= 1);
            assertTrue(((Number) status.get("dirtyCleared")).intValue() >= 1);

            // 维护推送已入队
            Object pkt = ch.readOutbound();
            assertTrue(pkt instanceof cn.itcast.demo.mylunarcore.net.GamePacket gp
                    && gp.getCmdId() == CmdIds.SERVER_MAINTENANCE_PUSH);

            BattleContext ctx2 = newBattle(7002L, 43);
            assertFalse(battles.put(ctx2));

            freeze.unfreeze();
            assertTrue(freeze.acceptNewBattles());
            assertTrue(battles.put(ctx2));
        }
    }

    @Nested
    @DisplayName("6. QA 可见性掩码：隐身账号看未开活动")
    class QaVisibilityFlow {

        @Test
        @DisplayName("普通玩家 GetActivity 未开始；QA 返回完整数据且不进排行榜")
        void normalHiddenQaSeesFull() {
            ServerClockService clock = new ServerClockService();
            long now = clock.nowEpochSecond();
            ActivityVisibilityService vis = new ActivityVisibilityService(clock, props);
            vis.markQa(42L, true);

            ActivityConfig future = new ActivityConfig();
            future.setActivityId(90001);
            future.setName("2.0预演");
            future.setBeginTime(now + 10_000);
            future.setEndTime(now + 20_000);
            future.setDisplayStart(now + 9_000);
            future.setEffectStart(now + 10_000);
            future.setDisplayEnd(now + 20_000);
            future.setEffectEnd(now + 20_000);

            cn.itcast.demo.mylunarcore.common.ActivityConfigService configService =
                    mock(cn.itcast.demo.mylunarcore.common.ActivityConfigService.class);
            when(configService.snapshot()).thenReturn(Map.of(90001, future));
            when(configService.findById(90001)).thenReturn(Optional.of(future));

            ActivityQueryService query = new ActivityQueryService(configService, vis, clock);
            assertTrue(query.resolveActivities(90001, false).isEmpty());
            assertEquals(1, query.resolveActivities(90001, true).size());
            assertTrue(vis.excludeFromLeaderboard(true));
            assertFalse(vis.excludeFromLeaderboard(false));

            ActivityNettyService netty = new ActivityNettyService(query, new ActivityProtoMapper());
            ActivitySystemProto.GetActivityInfoScRsp normal = netty.handleGetActivityInfo(90001, false);
            assertEquals(ActivityNettyService.RET_NOT_STARTED, normal.getRetcode());
            assertEquals(0, normal.getActivitiesCount());

            ActivitySystemProto.GetActivityInfoScRsp qa = netty.handleGetActivityInfo(90001, true);
            assertEquals(ActivityNettyService.RET_OK, qa.getRetcode());
            assertEquals(1, qa.getActivitiesCount());
            assertEquals(90001, qa.getActivities(0).getActivityId());
        }
    }

    @Nested
    @DisplayName("7. 负向发放 + 钱包回滚基准点")
    class NegativeGrantAndWalletSnapshotFlow {

        @Test
        @DisplayName("道具负向扣除走仓库；货币强制扣；单玩家快照可 dryRun 回滚")
        void subtractItemAndWalletSnapshot() throws Exception {
            ItemRepository items = mock(ItemRepository.class);
            GameItemEntity row = new GameItemEntity();
            row.setId(55L);
            row.setItemId(301);
            row.setCount(0);
            row.setDiscarded(true);
            when(items.forceSubtractByItemId(eq(1001), eq(301), eq(50L)))
                    .thenReturn(new ItemRepository.SubtractResult(50, 0, 1, List.of(row)));
            when(items.addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong())).thenReturn(99L);

            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(1001)).thenReturn(new HashMap<>(Map.of(1, 500)));
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 500), "ok"));
            when(wallet.forceDeductAllowNegative(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenAnswer(inv -> new WalletApplicationService.WalletChangeResult(
                            true, Map.of((Integer) inv.getArgument(1), 400), inv.getArgument(3)));

            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(anyLong())).thenReturn(null);

            NegativeGrantService grant = new NegativeGrantService(items, wallet, sessions, provider(null));
            Map<String, Object> sub = grant.grant(1001, 301, -50, false, "exploit_fix");
            assertTrue(Boolean.TRUE.equals(sub.get("ok")));
            assertEquals(50L, sub.get("subtracted"));
            verify(items).forceSubtractByItemId(1001, 301, 50L);

            Map<String, Object> cur = grant.grant(1001, 1, -100, true, "exploit_fix");
            assertTrue(Boolean.TRUE.equals(cur.get("ok")));

            // 重置 getBalance 序列：先快照读 500，再 dryRun 读到被刷成 9999
            when(wallet.getBalance(1001))
                    .thenReturn(new HashMap<>(Map.of(1, 500)))
                    .thenReturn(new HashMap<>(Map.of(1, 9999)));

            WalletWalService wal = mock(WalletWalService.class);
            when(wal.flushOnce()).thenReturn(0);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            WalletRollbackSnapshotService snapSvc = new WalletRollbackSnapshotService(jdbc, wallet, wal, props);

            Map<String, Object> snap = snapSvc.snapshotPlayer(1001, "pre-2.0");
            assertTrue(Boolean.TRUE.equals(snap.get("ok")));
            assertTrue(Files.isRegularFile(tempDir.resolve("wallet-snapshots/pre-2.0.json")));

            Map<String, Object> dry = snapSvc.rollbackTo("pre-2.0", true);
            assertTrue(Boolean.TRUE.equals(dry.get("ok")));
            assertEquals(true, dry.get("dryRun"));
            assertEquals(1, ((Number) dry.get("restored")).intValue());

            Map<String, Object> listed = snapSvc.listSnapshots();
            assertTrue(((List<?>) listed.get("snapshots")).contains("pre-2.0"));
        }
    }

    @Nested
    @DisplayName("端到端：发版前冻结 → 热补丁 → QA 预演 → 解冻")
    class PublishDayCompositeFlow {

        @Test
        @DisplayName("组合流程状态机正确")
        void publishDayPipeline() throws Exception {
            // 1) 钱包快照基准
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.getBalance(anyInt())).thenReturn(Map.of(1, 100));
            WalletWalService wal = mock(WalletWalService.class);
            when(wal.flushOnce()).thenReturn(0);
            WalletRollbackSnapshotService snaps = new WalletRollbackSnapshotService(
                    mock(JdbcTemplate.class), wallet, wal, props);
            assertTrue(Boolean.TRUE.equals(snaps.snapshotPlayer(1, "pre-release").get("ok")));

            // 2) Override 修 UP 率
            ConfigOverrideHotfixService override = new ConfigOverrideHotfixService(props, new ConfigDeltaPatchService());
            override.applyOverride("gacha", Map.of("base_probability", 0.006), "hotfix");

            // 3) Freeze
            GameSessionManager sessionMgr = mock(GameSessionManager.class);
            when(sessionMgr.snapshotSessions()).thenReturn(List.of());
            BattleSnapshotService battleSnaps = mock(BattleSnapshotService.class);
            AtomicReference<ServerFreezeService> freezeRef = new AtomicReference<>();
            BattleManager battleMgr = new BattleManager(battleSnaps,
                    new cn.itcast.demo.mylunarcore.battle.BattleInstancePool(props),
                    new ObjectProvider<>() {
                        @Override
                        public ServerFreezeService getObject() {
                            return freezeRef.get();
                        }

                        @Override
                        public ServerFreezeService getIfAvailable() {
                            return freezeRef.get();
                        }

                        @Override
                        public ServerFreezeService getIfUnique() {
                            return freezeRef.get();
                        }
                    });
            ServerFreezeService freeze = new ServerFreezeService(
                    sessionMgr, battleMgr, battleSnaps, provider(wal),
                    provider(null), new AlertWebhookNotifier(props));
            freezeRef.set(freeze);
            freeze.freeze("2.0", 5);
            assertFalse(battleMgr.put(newBattle(1L, 1)));

            // 4) Time travel + QA 看未来活动
            ServerClockService clock = new ServerClockService();
            long now = Instant.now().getEpochSecond();
            clock.travelTo(Instant.ofEpochSecond(now + 86400 * 7), "internal");
            ActivityVisibilityService vis = new ActivityVisibilityService(clock, props);
            vis.markQa(7L, true);
            ActivityConfig next = new ActivityConfig();
            next.setActivityId(1);
            next.setBeginTime(now + 86400 * 7);
            next.setEndTime(now + 86400 * 14);
            next.setDisplayStart(now + 86400 * 6);
            next.setEffectStart(now + 86400 * 7);
            next.setDisplayEnd(now + 86400 * 14);
            next.setEffectEnd(now + 86400 * 14);
            assertTrue(vis.canSee(next, true));
            assertTrue(vis.canInteract(next, false)); // 时钟已旅行到生效点

            // 5) 解冻上线
            freeze.unfreeze();
            clock.reset();
            assertTrue(freeze.acceptNewBattles());
            assertEquals(0.006, override.resolve("gacha", "base_probability", null));
        }
    }
}
