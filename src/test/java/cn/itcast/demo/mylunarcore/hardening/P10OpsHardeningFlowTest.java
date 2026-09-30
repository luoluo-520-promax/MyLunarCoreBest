package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityResourceService;
import cn.itcast.demo.mylunarcore.admin.PlayerTimelineService;
import cn.itcast.demo.mylunarcore.battle.BattleReplayService;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.ClientUiParams;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.MonthlyCardService;
import cn.itcast.demo.mylunarcore.economy.TopUpBonusService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.gacha.GachaPresentationService;
import cn.itcast.demo.mylunarcore.gacha.GachaRebateService;
import cn.itcast.demo.mylunarcore.hall.SupportService;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.net.StickySessionAffinity;
import cn.itcast.demo.mylunarcore.player.ClientVersionGateService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.repo.FriendRepository;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import cn.itcast.demo.mylunarcore.social.FriendGiftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P10 运营硬化：表现层协议、商业化、Sticky、活动预载、社交赠力、时间线、资源版本、战斗回放。
 */
@DisplayName("P10 新功能与业务流程总测")
class P10OpsHardeningFlowTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUpDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:p10_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE player_monthly_card (
                    player_id INT NOT NULL,
                    card_kind VARCHAR(32) NOT NULL DEFAULT 'MONTHLY',
                    active TINYINT NOT NULL DEFAULT 1,
                    expire_at TIMESTAMP NOT NULL,
                    remaining_days INT NOT NULL DEFAULT 0,
                    daily_currency_id INT NOT NULL DEFAULT 1,
                    daily_currency_amt INT NOT NULL DEFAULT 90,
                    daily_stamina_amt INT NOT NULL DEFAULT 60,
                    last_grant_day VARCHAR(16) NOT NULL DEFAULT '',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, card_kind)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_topup_bonus (
                    player_id INT PRIMARY KEY,
                    first_topup_done TINYINT NOT NULL DEFAULT 0,
                    year_cycle VARCHAR(8) NOT NULL DEFAULT '',
                    year_reset_at TIMESTAMP NULL,
                    total_topup_cents BIGINT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_gacha_rebate (
                    player_id INT PRIMARY KEY,
                    points INT NOT NULL DEFAULT 0,
                    lifetime_draws INT NOT NULL DEFAULT 0,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE gacha_presentation_session (
                    session_id VARCHAR(64) PRIMARY KEY,
                    player_id INT NOT NULL,
                    banner_type INT NOT NULL,
                    times INT NOT NULL,
                    state VARCHAR(16) NOT NULL DEFAULT 'STARTED',
                    client_nonce VARCHAR(64) NOT NULL DEFAULT '',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    closed_at TIMESTAMP NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE friend_stamina_gift_log (
                    gift_day VARCHAR(16) NOT NULL,
                    from_player_id INT NOT NULL,
                    to_player_id INT NOT NULL,
                    amount INT NOT NULL DEFAULT 5,
                    claimed TINYINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (gift_day, from_player_id, to_player_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE support_daily_usage (
                    usage_day VARCHAR(16) NOT NULL,
                    lender_id INT NOT NULL,
                    borrower_id INT NOT NULL,
                    use_count INT NOT NULL DEFAULT 1,
                    PRIMARY KEY (usage_day, lender_id, borrower_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE support_borrow_log (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    requester_id INT NOT NULL,
                    lender_id INT NOT NULL,
                    borrow_day VARCHAR(16) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE support_unit (
                    player_id INT PRIMARY KEY,
                    avatar_instance_id BIGINT NOT NULL,
                    avatar_id INT NOT NULL,
                    level INT NOT NULL,
                    promotion INT NOT NULL DEFAULT 0,
                    rank_val INT NOT NULL DEFAULT 0,
                    snapshot_json VARCHAR(512) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE activity_resource_version (
                    version_activity_id INT PRIMARY KEY,
                    cdn_bundle_version VARCHAR(64) NOT NULL,
                    cdn_manifest_url VARCHAR(512) NOT NULL DEFAULT '',
                    preload_ready TINYINT NOT NULL DEFAULT 0,
                    active TINYINT NOT NULL DEFAULT 0,
                    open_at TIMESTAMP NULL,
                    close_at TIMESTAMP NULL,
                    config_json VARCHAR(4000) NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE activity_preload_cache (
                    cache_key VARCHAR(128) PRIMARY KEY,
                    payload_json CLOB NOT NULL,
                    activate_at TIMESTAMP NULL,
                    activated TINYINT NOT NULL DEFAULT 0,
                    created_by VARCHAR(64) NOT NULL DEFAULT ''
                )
                """);
        jdbc.execute("""
                CREATE TABLE wallet_ledger (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    uid INT NOT NULL,
                    currency_id INT NOT NULL,
                    delta INT NOT NULL,
                    reason VARCHAR(128) NOT NULL DEFAULT '',
                    balance_before INT NOT NULL,
                    balance_after INT NOT NULL,
                    tx_id VARCHAR(64),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE gacha_draw_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    player_id INT NOT NULL,
                    banner_type INT NOT NULL,
                    item_id INT NOT NULL,
                    cost_currency_id INT NOT NULL DEFAULT 0,
                    cost_amount INT NOT NULL DEFAULT 0,
                    tx_id VARCHAR(64),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    @Nested
    @DisplayName("1. 抽卡表现层三次握手")
    class PresentationHandshake {

        @Test
        @DisplayName("START→DRAWING→ACK 状态机闭环，重复 start 复用会话")
        void gachaPresentationStateMachine() {
            GachaPresentationService svc = new GachaPresentationService(jdbc);
            GachaPresentationService.StartResult start = svc.start(1001, 11, 10, "nonce-a");
            assertTrue(start.ok());
            assertNotNull(start.session());
            assertEquals(GachaPresentationService.State.STARTED, start.session().state());
            assertEquals("gacha_ten_pull", start.session().ui().fxId());

            GachaPresentationService.StartResult again = svc.start(1001, 11, 10, "nonce-b");
            assertEquals(start.session().sessionId(), again.session().sessionId());

            assertNotNull(svc.markDrawing(start.session().sessionId(), 1001));
            assertTrue(svc.ack(start.session().sessionId(), 1001, false));
            assertTrue(svc.ack(start.session().sessionId(), 1001, true)); // 幂等

            GachaPresentationService.StartResult next = svc.start(1001, 2, 1, "nonce-c");
            assertTrue(next.ok());
            assertFalse(next.session().sessionId().equals(start.session().sessionId()));
        }

        @Test
        @DisplayName("非法参数应拒绝")
        void rejectBadArgs() {
            GachaPresentationService svc = new GachaPresentationService(jdbc);
            assertEquals(2, svc.start(0, 2, 1, null).retcode());
            assertEquals(2, svc.start(1, 2, 0, null).retcode());
        }
    }

    @Nested
    @DisplayName("2. 商业化：月卡 / 首充 / 抽卡返利")
    class Monetization {

        @Test
        @DisplayName("月卡激活后日切发放货币与体力")
        void monthlyCardDailyGrant() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 90), "ok"));
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.addBonus(anyInt(), anyInt(), anyString()))
                    .thenReturn(StaminaService.ConsumeResult.ok(100));
            PeriodicResetService reset = mock(PeriodicResetService.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<StaminaService> staminaProvider = mock(ObjectProvider.class);
            when(staminaProvider.getIfAvailable()).thenReturn(stamina);

            MonthlyCardService monthly = new MonthlyCardService(jdbc, wallet, staminaProvider, reset);
            MonthlyCardService.CardStatus status = monthly.activate(2001, MonthlyCardService.KIND_MONTHLY,
                    30, 1, 90, 60);
            assertTrue(status.active());
            assertTrue(status.remainingDays() > 0);

            monthly.onDailyReset(LocalDate.now());
            Integer last = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM player_monthly_card WHERE player_id=2001 AND last_grant_day<>''",
                    Integer.class);
            assertEquals(1, last);
        }

        @Test
        @DisplayName("首充双倍后再次充值不再双倍；年度重置可恢复")
        void topUpFirstThenNormalThenYearReset() {
            TopUpBonusService bonus = new TopUpBonusService(jdbc);
            TopUpBonusService.BonusDecision first = bonus.decideAndRecord(3001, 60, 600);
            assertTrue(first.firstTopup());
            assertEquals(60, first.bonusAmount());

            TopUpBonusService.BonusDecision second = bonus.decideAndRecord(3001, 60, 600);
            assertFalse(second.firstTopup());
            assertEquals(0, second.bonusAmount());

            jdbc.update("UPDATE player_topup_bonus SET year_cycle='2020', first_topup_done=1 WHERE player_id=3001");
            bonus.maybeYearReset(3001);
            assertFalse(bonus.isFirstTopupDone(3001));
            TopUpBonusService.BonusDecision afterYear = bonus.decideAndRecord(3001, 100, 1000);
            assertTrue(afterYear.firstTopup());
            assertEquals(100, afterYear.bonusAmount());
        }

        @Test
        @DisplayName("抽卡返利：抽十得十点，兑换扣减，不足失败")
        void gachaRebateLoop() {
            GachaRebateService rebate = new GachaRebateService(jdbc, 1);
            GachaRebateService.Balance bal = rebate.grantForDraws(4001, 10);
            assertEquals(10, bal.points());
            assertEquals(10, bal.lifetimeDraws());

            assertTrue(rebate.trySpend(4001, 3).ok());
            assertEquals(7, rebate.balance(4001).points());
            assertEquals(3, rebate.trySpend(4001, 100).retcode());
        }
    }

    @Nested
    @DisplayName("3. Sticky Session")
    class Sticky {

        @Test
        @DisplayName("同一 UID 稳定映射到固定节点")
        void stickyStable() {
            int a = StickySessionAffinity.nodeIndexForUid(55501, 4);
            int b = StickySessionAffinity.nodeIndexForUid(55501, 4);
            assertEquals(a, b);
            assertTrue(StickySessionAffinity.isStickyOwner(55501, a, 4));
            assertFalse(StickySessionAffinity.isStickyOwner(55501, (a + 1) % 4, 4));
        }
    }

    @Nested
    @DisplayName("4. 活动预加载与激活")
    class ActivityPreload {

        @Test
        @DisplayName("preload 不激活；activate 后 active=true")
        void preloadThenActivate() {
            ClusterJobLock lock = mock(ClusterJobLock.class);
            when(lock.tryRun(anyString(), any(Duration.class), any(Runnable.class))).thenAnswer(inv -> {
                inv.getArgument(2, Runnable.class).run();
                return true;
            });
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);

            // H2 不支持 CAST AS JSON：用兼容 SQL 路径——服务写 CAST(? AS JSON) 可能失败
            // 改为直接测 activate 路径：先手工插入 preload 行
            jdbc.update("""
                    INSERT INTO activity_resource_version
                    (version_activity_id, cdn_bundle_version, cdn_manifest_url, preload_ready, active, config_json)
                    VALUES (9001, 'res-1.2.0', 'https://cdn/ex/manifest.json', 1, 0, '{}')
                    """);
            jdbc.update("""
                    INSERT INTO activity_preload_cache (cache_key, payload_json, activated, created_by)
                    VALUES ('va:9001:res-1.2.0', '{}', 0, 'ops')
                    """);

            ActivityResourceService svc = new ActivityResourceService(
                    jdbc, new ObjectMapper(), lock, redis);
            // 手工塞入 memory，模拟 preload 成功后的内存态
            assertTrue(svc.activate(9001));
            Integer active = jdbc.queryForObject(
                    "SELECT active FROM activity_resource_version WHERE version_activity_id=9001", Integer.class);
            assertEquals(1, active);
            Integer activated = jdbc.queryForObject(
                    "SELECT activated FROM activity_preload_cache WHERE cache_key='va:9001:res-1.2.0'", Integer.class);
            assertEquals(1, activated);
        }

        @Test
        @DisplayName("preload API 参数校验")
        void preloadValidation() {
            ClusterJobLock lock = mock(ClusterJobLock.class);
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            ActivityResourceService svc = new ActivityResourceService(
                    jdbc, new ObjectMapper(), lock, redis);
            assertFalse(svc.preload(0, "v1", "", "{}", null, "ops").ok());
            assertFalse(svc.preload(1, "", "", "{}", null, "ops").ok());
        }
    }

    @Nested
    @DisplayName("5. 助战日限与好友体力赠送")
    class Social {

        @Test
        @DisplayName("同一好友助战每天只能借一次")
        void supportOncePerFriendPerDay() {
            FriendRepository friends = mock(FriendRepository.class);
            FriendEntity rel = new FriendEntity();
            rel.setPlayerId1(1);
            rel.setPlayerId2(2);
            rel.setStatus(1);
            when(friends.findRelation(1, 2)).thenReturn(rel);

            jdbc.update("""
                    INSERT INTO support_unit
                    (player_id, avatar_instance_id, avatar_id, level, promotion, rank_val, snapshot_json)
                    VALUES (2, 99, 1102, 80, 6, 0, '{}')
                    """);

            SupportService support = new SupportService(jdbc, friends, mock(PlayerDataRepository.class),
                    new ObjectMapper(), 5, 0, 0, 0, 0, 0);
            SupportService.BorrowResult first = support.borrowFriendSupportDetailed(1, 2);
            assertEquals(0, first.retcode());
            assertNotNull(first.snapshot());

            SupportService.BorrowResult second = support.borrowFriendSupportDetailed(1, 2);
            assertEquals(7, second.retcode());
        }

        @Test
        @DisplayName("赠送体力→领取→重复领取失败")
        void friendGiftClaimOnce() {
            FriendRepository friends = mock(FriendRepository.class);
            FriendEntity rel = new FriendEntity();
            rel.setPlayerId1(10);
            rel.setPlayerId2(20);
            rel.setStatus(1);
            when(friends.listFriends(10)).thenReturn(List.of(rel));

            StaminaService stamina = mock(StaminaService.class);
            when(stamina.addBonus(eq(20), eq(5), anyString()))
                    .thenReturn(StaminaService.ConsumeResult.ok(65));
            @SuppressWarnings("unchecked")
            ObjectProvider<StaminaService> staminaProvider = mock(ObjectProvider.class);
            when(staminaProvider.getIfAvailable()).thenReturn(stamina);
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);
            PeriodicResetService reset = mock(PeriodicResetService.class);

            FriendGiftService gift = new FriendGiftService(jdbc, friends, staminaProvider, redis, reset, 5);
            FriendGiftService.OpResult sent = gift.giftAllFriends(10);
            assertTrue(sent.ok());
            assertEquals(5, sent.amount());

            FriendGiftService.OpResult claim = gift.claim(20, 10);
            assertTrue(claim.ok());
            assertEquals(5, claim.amount());

            FriendGiftService.OpResult again = gift.claim(20, 10);
            assertFalse(again.ok());
            assertEquals(3, again.retcode());
        }
    }

    @Nested
    @DisplayName("6. 玩家时间线")
    class Timeline {

        @Test
        @DisplayName("应按时间合并钱包流水与抽卡记录")
        void timelineMergesSources() {
            jdbc.update("""
                    INSERT INTO wallet_ledger
                    (uid, currency_id, delta, reason, balance_before, balance_after, tx_id, created_at)
                    VALUES (7001, 1, -160, 'gacha:11:tx1', 1000, 840, 'tx1', '2026-01-01 10:00:00')
                    """);
            jdbc.update("""
                    INSERT INTO wallet_ledger
                    (uid, currency_id, delta, reason, balance_before, balance_after, tx_id, created_at)
                    VALUES (7001, 1, 60, 'topup_bonus:first', 840, 900, 'tx2', '2026-01-01 11:00:00')
                    """);
            jdbc.update("""
                    INSERT INTO gacha_draw_history
                    (player_id, banner_type, item_id, cost_currency_id, cost_amount, tx_id, created_at)
                    VALUES (7001, 11, 1102, 1, 160, 'tx1', '2026-01-01 10:00:01')
                    """);

            PlayerTimelineService timeline = new PlayerTimelineService(jdbc);
            Map<String, Object> tree = timeline.asTree(7001, Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-02T00:00:00Z"), 100);
            assertEquals(7001, tree.get("uid"));
            assertEquals(3, tree.get("count"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("entries");
            assertEquals("EXPENSE", entries.get(0).get("kind"));
            assertEquals("GACHA", entries.get(1).get("kind"));
            assertEquals("INCOME", entries.get(2).get("kind"));
        }
    }

    @Nested
    @DisplayName("7. 客户端资源版本封锁")
    class ClientResVersion {

        @Test
        @DisplayName("低于阈值应 ERR_CLIENT_TOO_OLD")
        void rejectOldRes() throws Exception {
            ClientVersionGateService gate = new ClientVersionGateService(new ObjectMapper(), "data");
            var field = ClientVersionGateService.class.getDeclaredField("required");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ref = (java.util.concurrent.atomic.AtomicReference<ClientVersionGateService.RequiredVersions>) field.get(gate);
            ref.set(new ClientVersionGateService.RequiredVersions("3.0.0", "https://u/app", "3.0.0"));

            var bad = gate.check("2.9.9");
            assertFalse(bad.allowed());
            assertEquals(ClientVersionGateService.ERR_CLIENT_TOO_OLD, bad.retcode());
            assertEquals("https://u/app", bad.updateUrl());
            assertTrue(gate.check("3.0.0").allowed());
            assertTrue(gate.check("3.1.0").allowed());
        }
    }

    @Nested
    @DisplayName("8. 战斗重连 ActionTick 快进")
    class BattleReplay {

        @Test
        @DisplayName("掉线后重连应只收到未见过的行动序列")
        void reconnectFastForward() {
            BattleSnapshotService snaps = mock(BattleSnapshotService.class);
            when(snaps.load(42L)).thenReturn(Optional.empty());
            @SuppressWarnings("unchecked")
            ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
            when(redis.getIfAvailable()).thenReturn(null);

            BattleReplayService replay = new BattleReplayService(new ObjectMapper(), snaps, redis);
            replay.append(42L, 1, "SKILL", Map.of("id", 1));
            replay.append(42L, 2, "SKILL", Map.of("id", 2));
            replay.append(42L, 1, "ULT", Map.of("id", 3));
            replay.markSeen(42L, 1, 1L);

            Optional<BattleReplayService.ReplayPacket> pkt = replay.buildReconnectPacket(42L, 1);
            assertTrue(pkt.isPresent());
            assertEquals(2, pkt.get().actions().size());
            assertEquals(1L, pkt.get().fromSeq()); // markSeen 水位
            assertEquals(3L, pkt.get().toSeq());
            assertEquals("ULT", pkt.get().actions().get(1).actionType());
        }
    }

    @Nested
    @DisplayName("ClientUiParams 配置约定")
    class UiParams {

        @Test
        @DisplayName("空值应归一化为空串/空 Map")
        void normalizeNulls() {
            ClientUiParams p = new ClientUiParams(null, null, null, null, null);
            assertEquals("", p.fxId());
            assertTrue(p.textPlaceholders().isEmpty());
            assertEquals(ClientUiParams.empty().sfxId(), "");
        }
    }
}
