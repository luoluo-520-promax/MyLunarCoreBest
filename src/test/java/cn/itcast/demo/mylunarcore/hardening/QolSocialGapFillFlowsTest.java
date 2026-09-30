package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityReminderService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.activity.ReturnCheckService;
import cn.itcast.demo.mylunarcore.battle.BattleReplayShareService;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.MonthlyCardService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.exploration.ExplorationService;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerType;
import cn.itcast.demo.mylunarcore.gacha.GachaGuaranteeQueryService;
import cn.itcast.demo.mylunarcore.gacha.TransactionHistoryExportService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.QolSocialPacketHandlers;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.profile.PlayerCardService;
import cn.itcast.demo.mylunarcore.profile.PlayerProfileService;
import cn.itcast.demo.mylunarcore.profile.TitleService;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import cn.itcast.demo.mylunarcore.qol.ClaimAllDailyRewardsService;
import cn.itcast.demo.mylunarcore.qol.DailyReminderService;
import cn.itcast.demo.mylunarcore.qol.QolLoginHookService;
import cn.itcast.demo.mylunarcore.quest.DailyMissionService;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import cn.itcast.demo.mylunarcore.scene.SceneInteractHandler;
import cn.itcast.demo.mylunarcore.social.CustomEmoteService;
import cn.itcast.demo.mylunarcore.social.FriendSearchService;
import cn.itcast.demo.mylunarcore.social.GuildSearchService;
import cn.itcast.demo.mylunarcore.social.PlayerReportBlockService;
import cn.itcast.demo.mylunarcore.worldboss.WorldBossService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P11 QoL/社交缺口：14 项新功能与端到端业务流程总测（H2）。
 */
@DisplayName("P11 体验社交缺口：新功能与业务流程总测")
class QolSocialGapFillFlowsTest {

    private JdbcTemplate jdbc;
    private Path dataDir;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:qol_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        jdbc = new JdbcTemplate(ds);
        mapper = new ObjectMapper();
        dataDir = Files.createTempDirectory("qol-flow");
        createSchema();
        seedPlayersAndGuilds();
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE player (
                    uid BIGINT PRIMARY KEY, account_id INT, nickname VARCHAR(64), level INT DEFAULT 1,
                    exp BIGINT DEFAULT 0, world_level INT DEFAULT 0, stamina INT DEFAULT 0,
                    currency VARCHAR(256), scene_id INT DEFAULT 0,
                    pos_x DECIMAL(12,4), pos_y DECIMAL(12,4), pos_z DECIMAL(12,4),
                    last_login TIMESTAMP, last_logout TIMESTAMP, data_version BIGINT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE guild (
                    guild_id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(64), notice VARCHAR(256),
                    level INT DEFAULT 1, exp INT DEFAULT 0, leader_id INT, member_count INT DEFAULT 1,
                    max_members INT DEFAULT 30
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_exploration (
                    player_id INT, plane_id INT, floor_id INT, collect_id VARCHAR(64),
                    kind VARCHAR(32), collected_at VARCHAR(40),
                    PRIMARY KEY (player_id, plane_id, floor_id, collect_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_social_profile (
                    player_id INT PRIMARY KEY, signature VARCHAR(160) DEFAULT '',
                    status_message VARCHAR(80) DEFAULT '', custom_status VARCHAR(32) DEFAULT 'ONLINE',
                    equipped_title_id VARCHAR(64) DEFAULT '', updated_at VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_report (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, reporter_id INT, target_id INT,
                    scene VARCHAR(32), evidence_type VARCHAR(32), evidence VARCHAR(4000), reason VARCHAR(256),
                    status VARCHAR(16) DEFAULT 'OPEN', resolve_result VARCHAR(32) DEFAULT '',
                    resolve_note VARCHAR(256) DEFAULT '', operator VARCHAR(64) DEFAULT '',
                    created_at VARCHAR(40), resolved_at VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_block (
                    player_id INT, blocked_id INT, created_at VARCHAR(40),
                    PRIMARY KEY (player_id, blocked_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_return_activity (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id INT, activity_id INT,
                    offline_days INT, expire_at TIMESTAMP, active TINYINT, created_at VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_custom_emote (
                    custom_emote_id INT AUTO_INCREMENT PRIMARY KEY, player_id INT, name VARCHAR(64),
                    content_type VARCHAR(64), image_data BLOB, review_status VARCHAR(16),
                    created_at VARCHAR(40), updated_at VARCHAR(40)
                )
                """);
        jdbc.execute("ALTER TABLE player_custom_emote ALTER COLUMN custom_emote_id RESTART WITH 100000");
        jdbc.execute("""
                CREATE TABLE player_title (
                    player_id INT, title_id VARCHAR(64), unlocked_at VARCHAR(40),
                    PRIMARY KEY (player_id, title_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_recent_contact (
                    player_id INT, other_id BIGINT, reason VARCHAR(32), updated_at VARCHAR(40),
                    PRIMARY KEY (player_id, other_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_daily_reminder_pref (
                    player_id INT PRIMARY KEY, enabled TINYINT, disabled_modules VARCHAR(256),
                    updated_at VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE activity_template_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id INT, activity_id INT,
                    action VARCHAR(64), detail VARCHAR(256), created_at VARCHAR(40)
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_gacha_info (
                    player_id INT PRIMARY KEY, ceiling_num INT DEFAULT 0, ceiling_claimed INT DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_gacha_banner_info (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id INT, banner_type INT,
                    pity_5 INT DEFAULT 0, pity_4 INT DEFAULT 0, failed_up_count INT DEFAULT 0,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(player_id, banner_type)
                )
                """);
        jdbc.execute("""
                CREATE TABLE mail (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, player_id INT, title VARCHAR(128),
                    content CLOB, status INT DEFAULT 0, attachments_json CLOB,
                    expire_time TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE player_monthly_card (
                    player_id INT, card_kind VARCHAR(32), active TINYINT, expire_at TIMESTAMP,
                    remaining_days INT, daily_currency_id INT, daily_currency_amt INT,
                    daily_stamina_amt INT, last_grant_day VARCHAR(16) DEFAULT '',
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, card_kind)
                )
                """);
        jdbc.execute("""
                CREATE TABLE gacha_draw_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, uid INT, banner_type INT,
                    item_id INT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void seedPlayersAndGuilds() {
        jdbc.update("INSERT INTO player(uid, account_id, nickname, level) VALUES (1001,1,'开拓者甲',20)");
        jdbc.update("INSERT INTO player(uid, account_id, nickname, level) VALUES (1002,2,'开拓者乙',15)");
        jdbc.update("INSERT INTO player(uid, account_id, nickname, level) VALUES (2001,3,'深渊战神',40)");
        jdbc.update("INSERT INTO guild(guild_id, name, notice, level, leader_id, member_count) VALUES (501,'星穹列车','欢迎',3,1001,2)");
        jdbc.update("INSERT INTO guild(guild_id, name, notice, level, leader_id, member_count) VALUES (502,'巡猎之影','招募',2,2001,1)");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @Nested
    @DisplayName("协议与 CmdId 门禁")
    class ProtocolGate {

        @Test
        @DisplayName("1200–1247 CmdId 唯一且 Handler 覆盖全部 CsReq")
        void cmdIdsUniqueAndHandlersCoverCsReq() throws Exception {
            Set<Integer> values = new HashSet<>();
            Set<Integer> csReqs = new HashSet<>();
            for (Field f : CmdIds.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                    continue;
                }
                int v = f.getInt(null);
                assertTrue(values.add(v), "duplicate CmdId " + f.getName() + "=" + v);
                if (f.getName().startsWith("CLAIM_ALL") || (v >= 1200 && v <= 1247)) {
                    if (f.getName().endsWith("_CS_REQ")) {
                        csReqs.add(v);
                    }
                }
            }
            assertEquals(1200, CmdIds.CLAIM_ALL_DAILY_REWARDS_CS_REQ);
            assertEquals(1247, CmdIds.EXPORT_TRANSACTION_HISTORY_SC_RSP);

            Set<Integer> handled = new HashSet<>();
            for (Method m : QolSocialPacketHandlers.class.getDeclaredMethods()) {
                var ann = m.getAnnotation(cn.itcast.demo.mylunarcore.net.PacketCmd.class);
                if (ann != null) {
                    handled.add(ann.value());
                }
            }
            assertFalse(csReqs.isEmpty());
            assertTrue(handled.containsAll(csReqs), "missing handlers for " + csReqs.stream()
                    .filter(id -> !handled.contains(id)).toList());

            assertNotNull(QolSocialSystemProto.ClaimAllDailyRewardsCsReq.getDefaultInstance());
            assertNotNull(QolSocialSystemProto.GetExplorationInfoScRsp.getDefaultInstance());
            assertNotNull(QolSocialSystemProto.ShareBattleReplayCsReq.getDefaultInstance());
            assertNotNull(QolSocialSystemProto.GetGachaGuaranteeInfoScRsp.getDefaultInstance());
        }
    }

    @Nested
    @DisplayName("1. 一键领取日常奖励")
    class ClaimAllFlow {

        @Test
        @DisplayName("仅开启每日任务时聚合领取成功项")
        void claimDailyOnly() throws Exception {
            Files.writeString(dataDir.resolve("ClaimAllDailyRewardsConfig.json"), """
                    {"dailyMission":true,"battlePass":false,"mail":false,"achievement":false,"signIn":false,"excludeHighValueMail":true}
                    """);
            DailyMissionService daily = mock(DailyMissionService.class);
            when(daily.listToday(1001)).thenReturn(List.of(
                    new DailyMissionService.MissionView(11, "登录", "d", 1, 1, true, false),
                    new DailyMissionService.MissionView(12, "战斗", "d", 0, 1, false, false)));
            when(daily.claim(1001, 11)).thenReturn(new DailyMissionService.ClaimResult(true, 0));

            ClaimAllDailyRewardsService svc = new ClaimAllDailyRewardsService(
                    mapper, dataDir.toString(), provider(daily), provider(null),
                    provider(null), provider(null), provider(null));
            svc.loadConfig();
            ClaimAllDailyRewardsService.ClaimAllResult r = svc.claimAll(1001);
            assertTrue(r.ok());
            assertEquals(1, r.totalClaimed());
            assertEquals("daily_mission", r.summaries().get(0).module());
            assertEquals(1, r.summaries().get(0).successCount());
        }
    }

    @Nested
    @DisplayName("2. 区域探索度")
    class ExplorationFlow {

        @Test
        @DisplayName("拾取→记录→进度百分比上升")
        void pickupRaisesProgress() throws Exception {
            Files.writeString(dataDir.resolve("ExplorationConfigs.json"), """
                    {"regions":[{"planeId":10001,"floorId":1,"collectables":[
                      {"collectId":"chest_1","kind":"chest","name":"宝箱1"},
                      {"collectId":"chest_2","kind":"chest","name":"宝箱2"}
                    ]}]}
                    """);
            ExplorationService svc = new ExplorationService(jdbc, mapper, dataDir.toString(),
                    provider((GameSessionManager) null));
            svc.loadConfig();
            SceneInteractHandler handler = new SceneInteractHandler(provider(svc));

            ExplorationService.RegionProgress before = svc.getInfo(1001, 10001, 1);
            assertEquals(0, before.collected());
            assertEquals(2, before.total());

            handler.onPropPickup(1001, 10001, 1, "chest_1");
            ExplorationService.RegionProgress after = svc.getInfo(1001, 10001, 1);
            assertEquals(1, after.collected());
            assertEquals(50, after.percent());
            assertTrue(after.collectables().stream().anyMatch(c -> c.collectId().equals("chest_1") && c.collected()));
        }
    }

    @Nested
    @DisplayName("3. 个人签名与自定义状态")
    class ProfileFlow {

        @Test
        @DisplayName("设置签名/状态后可读回")
        void setAndGetStatus() {
            PlayerProfileService svc = new PlayerProfileService(jdbc, mapper, dataDir.toString());
            PlayerProfileService.OpResult r = svc.setStatus(1001, "今日宜探索", "打深渊中", "ABYSS");
            assertTrue(r.ok());
            PlayerProfileService.Profile p = svc.getOrDefault(1001);
            assertEquals("今日宜探索", p.signature());
            assertEquals("ABYSS", p.customStatus());
            assertFalse(svc.statusPresets().isEmpty());
        }
    }

    @Nested
    @DisplayName("4. 举报与屏蔽")
    class ReportBlockFlow {

        @Test
        @DisplayName("举报→后台处理→屏蔽双向拦截")
        void reportResolveAndBlock() {
            PlayerReportBlockService svc = new PlayerReportBlockService(jdbc, provider((GameSessionManager) null));
            PlayerReportBlockService.ReportResult report = svc.report(
                    1001, 1002, "chat", "text", "辱骂内容", "骚扰");
            assertTrue(report.ok());
            assertTrue(report.reportId() > 0);
            assertEquals(1, svc.listOpenReports(10).size());

            assertTrue(svc.resolveReport(report.reportId(), "warned", "已警告", "gm1"));
            assertTrue(svc.listOpenReports(10).isEmpty());

            assertTrue(svc.setBlocked(1001, 1002, false).blocked());
            assertTrue(svc.isBlockedEitherWay(1001, 1002));
            assertTrue(svc.isBlockedEitherWay(1002, 1001));
            assertFalse(svc.setBlocked(1001, 1002, true).blocked());
            assertFalse(svc.isBlockedEitherWay(1001, 1002));
        }
    }

    @Nested
    @DisplayName("5. 回流活动激活")
    class ReturnFlow {

        @Test
        @DisplayName("离线超阈值应激活回流实例")
        void activateWhenOfflineLongEnough() throws Exception {
            Files.writeString(dataDir.resolve("ReturnPlayerActivityConfig.json"), """
                    {"activityId":90001,"offlineDaysThreshold":14,"durationDays":7,
                     "title":"回归礼遇","bonusDesc":"双倍","questChain":["login_day1"]}
                    """);
            ReturnCheckService svc = new ReturnCheckService(jdbc, mapper, dataDir.toString(),
                    provider((GameSessionManager) null), provider(null));
            svc.loadConfig();
            long logout = Instant.now().minus(20, ChronoUnit.DAYS).toEpochMilli();
            assertTrue(svc.checkAndActivate(1001, logout));
            assertFalse(svc.checkAndActivate(1001, logout)); // 已激活不重复
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM player_return_activity WHERE player_id=1001 AND active=1",
                    Integer.class);
            assertEquals(1, n);
        }
    }

    @Nested
    @DisplayName("6. 战斗录像分享")
    class ReplayShareFlow {

        @Test
        @DisplayName("生成分享码后可拉取")
        void shareThenFetch() {
            BattleReplayShareService svc = new BattleReplayShareService(
                    provider(null), provider(null), provider((GameSessionManager) null));
            BattleReplayShareService.ShareResult share = svc.share(1001, "42", 0, false, "甲");
            assertTrue(share.ok());
            assertFalse(share.shareCode().isBlank());
            BattleReplayShareService.FetchResult fetch = svc.fetch(share.shareCode());
            assertTrue(fetch.ok());
            assertEquals(1001, fetch.ownerPlayerId());
            assertEquals("42", fetch.battleId());
        }
    }

    @Nested
    @DisplayName("7. 每日挑战次数提醒")
    class DailyReminderFlow {

        @Test
        @DisplayName("关闭偏好后登录不推送；开启时汇总剩余次数")
        void prefAndCollect() throws Exception {
            Files.writeString(dataDir.resolve("DailyReminderConfig.json"), """
                    {"worldBoss":true,"abyss":false,"guildWar":false,"challenge":true}
                    """);
            WorldBossService wb = new WorldBossService(jdbc, mapper, mock(ClusterJobLock.class));
            wb.init();
            DailyReminderService svc = new DailyReminderService(jdbc, mapper, dataDir.toString(),
                    provider(wb), provider(null), provider(null), provider((GameSessionManager) null));
            svc.loadConfig();
            svc.setPlayerPref(1001, false, List.of());
            // 偏好关闭：不抛异常即可
            svc.pushOnLogin(1001);

            svc.setPlayerPref(1001, true, List.of("challenge"));
            Integer enabled = jdbc.queryForObject(
                    "SELECT enabled FROM player_daily_reminder_pref WHERE player_id=1001", Integer.class);
            assertEquals(1, enabled);
            assertEquals(0, wb.usedChallengesToday(1001));
            assertTrue(wb.info().dailyLimit() > 0);
        }
    }

    @Nested
    @DisplayName("13. 活动即将结束提醒")
    class ActivityReminderFlow {

        @Test
        @DisplayName("登录推送与紧急扫描不抛异常（无活动时静默）")
        void pushOnLoginSafe() {
            ActivityReminderService svc = new ActivityReminderService(
                    jdbc, mapper, dataDir.toString(), provider(null), provider((GameSessionManager) null));
            svc.pushOnLogin(1001);
            svc.scheduledUrgentPush();
        }
    }

    @Nested
    @DisplayName("8. 自定义表情上传审核")
    class CustomEmoteFlow {

        @Test
        @DisplayName("上传→pending→审核通过→owns")
        void uploadReviewOwns() {
            CustomEmoteService svc = new CustomEmoteService(jdbc);
            byte[] png = new byte[64];
            CustomEmoteService.UploadResult up = svc.upload(1001, png, "image/png", "比心");
            assertTrue(up.ok());
            assertTrue(up.customEmoteId() >= CustomEmoteService.CUSTOM_ID_FLOOR);
            assertEquals("pending", up.reviewStatus());
            assertEquals(1, svc.listPending(10).size());
            assertTrue(svc.setReviewStatus(up.customEmoteId(), "approved"));
            assertTrue(svc.findApproved(up.customEmoteId()).isPresent());
            assertTrue(svc.owns(1001, up.customEmoteId()));
            assertFalse(svc.owns(1002, up.customEmoteId()));
        }
    }

    @Nested
    @DisplayName("9. 月卡补发与补签")
    class MonthlyAndMakeupFlow {

        @Test
        @DisplayName("月卡激活后当日可补发；免费补签写事件")
        void monthlyGrantAndMakeup() {
            PeriodicResetService reset = mock(PeriodicResetService.class);
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(), "ok"));
            MonthlyCardService monthly = new MonthlyCardService(jdbc, wallet,
                    provider((StaminaService) null), reset);
            MonthlyCardService.CardStatus st = monthly.activate(1001, MonthlyCardService.KIND_MONTHLY,
                    30, 1, 90, 60);
            assertTrue(st.active());
            MonthlyCardService.GrantTodayResult grant = monthly.grantTodayIfNeeded(1001);
            assertTrue(grant.status().active());

            ActivityTemplateService templates = new ActivityTemplateService(
                    jdbc, mock(ActivityScriptEngine.class), new ActivityCircuitBreakerService());
            assertTrue(templates.supportedTemplates().stream()
                    .anyMatch(m -> "RETURN_PLAYER".equals(m.get("type"))));
            assertTrue(templates.supportedTemplates().stream()
                    .anyMatch(m -> "COMPENSATE".equals(m.get("type"))));
            ActivityTemplateService.OpResult makeup = templates.makeupSignIn(1001, 7001, "2026-09-01", 0);
            assertTrue(makeup.success());
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM activity_template_event WHERE player_id=1001 AND action='compensate'",
                    Integer.class);
            assertEquals(1, n);
        }
    }

    @Nested
    @DisplayName("10. 好友/公会模糊搜索与最近联系人")
    class SearchFlow {

        @Test
        @DisplayName("昵称前缀、UID、公会名、最近联系人闭环")
        void searchAndRecent() {
            FriendSearchService friends = new FriendSearchService(jdbc);
            FriendSearchService.SearchPage byName = friends.search("开拓者", 1, 10);
            assertTrue(byName.total() >= 1, "nickname prefix hits=" + byName.total());
            FriendSearchService.SearchPage byUid = friends.search("1001", 1, 10);
            assertTrue(byUid.hits().stream().anyMatch(h -> h.uid() == 1001));
            FriendSearchService.SearchPage bySuffix = friends.search("002", 1, 10);
            assertTrue(bySuffix.hits().stream().anyMatch(h -> h.uid() == 1002));

            friends.recordRecent(1001, 1002, "chat");
            friends.recordRecent(1001, 2001, "party");
            List<FriendSearchService.RecentContact> recent = friends.listRecent(1001, 10);
            assertEquals(2, recent.size());

            GuildSearchService guilds = new GuildSearchService(jdbc);
            GuildSearchService.SearchPage g = guilds.search("星穹", 1, 10);
            assertTrue(g.total() >= 1);
            assertEquals("星穹列车", g.hits().get(0).name());
        }
    }

    @Nested
    @DisplayName("11. 荣誉称号")
    class TitleFlow {

        @Test
        @DisplayName("成就解锁称号→装备→名片展示名")
        void unlockEquip() {
            TitleService titles = new TitleService(jdbc, provider((PlayerCardService) null));
            assertTrue(titles.unlockFromAchievement(1001, "gacha_10"));
            TitleService.EquipResult eq = titles.equip(1001, "beginner");
            assertTrue(eq.ok());
            assertEquals("初心者", eq.displayName());
            assertEquals("初心者", titles.getEquippedDisplayName(1001));
            assertTrue(titles.listForPlayer(1001).stream()
                    .anyMatch(t -> t.titleId().equals("beginner") && t.unlocked() && t.equipped()));
        }
    }

    @Nested
    @DisplayName("12. 组队一键邀请")
    class PartyInviteFlow {

        @Test
        @DisplayName("创建队伍后批量邀请成功；冷却期内计 skipped")
        void inviteManyWithCooldown() {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);
            when(store.saveIfNewer(org.mockito.ArgumentMatchers.any())).thenReturn(true);
            when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
            PartyService party = new PartyService(store);
            assertEquals(PartyService.PartyResultCode.OK, party.create(1001L).code());
            PartyService.InviteManyResult r = party.inviteMany(1001L, List.of(1002L, 2001L));
            assertEquals(2, r.invited());
            PartyService.InviteManyResult cooled = party.inviteMany(1001L, List.of(3001L));
            assertEquals(0, cooled.invited());
            assertEquals(1, cooled.skipped());
        }
    }

    @Nested
    @DisplayName("14. 保底查询与消费导出")
    class GachaExportFlow {

        @Test
        @DisplayName("写入 pity 后查询 remain；导出邮件落库")
        void guaranteeAndExport() {
            GachaRepository gachaRepo = new GachaRepository(jdbc);
            assertNotNull(gachaRepo.loadOrCreateBannerInfo(1001, GachaBannerType.AVATAR_UP));
            assertTrue(gachaRepo.updateBannerPity(1001, GachaBannerType.AVATAR_UP, 70, 5, 1) > 0);
            GachaGuaranteeQueryService guarantee = new GachaGuaranteeQueryService(gachaRepo);
            GachaGuaranteeQueryService.GuaranteeInfo info =
                    guarantee.getGuaranteeInfo(1001, GachaBannerType.AVATAR_UP);
            assertEquals(70, info.pity5());
            assertEquals(20, info.remainToHard5());
            assertTrue(info.softGuarantee5());
            assertEquals(4, guarantee.listGuaranteeInfo(1001, 0).size());

            jdbc.update("INSERT INTO gacha_draw_history(uid, banner_type, item_id) VALUES (1001,11,10001)");
            MailRepository mailRepo = new MailRepository(jdbc);
            TransactionHistoryExportService export = new TransactionHistoryExportService(
                    jdbc, mailRepo, provider(null));
            TransactionHistoryExportService.ExportResult er = export.export(1001, "gacha", 30);
            assertTrue(er.ok());
            assertTrue(er.mailId() > 0);
            Long mails = jdbc.queryForObject("SELECT COUNT(1) FROM mail WHERE player_id=1001", Long.class);
            assertEquals(1L, mails);
        }
    }

    @Nested
    @DisplayName("登录聚合钩子")
    class LoginHookFlow {

        @Test
        @DisplayName("QolLoginHook 依次调用回流/提醒/月卡且吞掉异常")
        void loginHookInvokesModules() throws Exception {
            Files.writeString(dataDir.resolve("ReturnPlayerActivityConfig.json"), """
                    {"activityId":90001,"offlineDaysThreshold":7,"durationDays":3,"title":"t","bonusDesc":"b","questChain":[]}
                    """);
            Files.writeString(dataDir.resolve("DailyReminderConfig.json"), """
                    {"worldBoss":false,"abyss":false,"guildWar":false,"challenge":false}
                    """);
            ReturnCheckService ret = new ReturnCheckService(jdbc, mapper, dataDir.toString(),
                    provider((GameSessionManager) null), provider(null));
            ret.loadConfig();
            DailyReminderService reminder = new DailyReminderService(jdbc, mapper, dataDir.toString(),
                    provider(null), provider(null), provider(null), provider((GameSessionManager) null));
            reminder.loadConfig();

            AtomicBoolean monthlyCalled = new AtomicBoolean(false);
            MonthlyCardService monthly = mock(MonthlyCardService.class);
            when(monthly.grantTodayIfNeeded(anyInt())).thenAnswer(inv -> {
                monthlyCalled.set(true);
                return new MonthlyCardService.GrantTodayResult(
                        new MonthlyCardService.CardStatus("MONTHLY", false, 0, 0, 0, 0), false);
            });
            when(monthly.grantTodayIfNeeded(anyInt(), anyString())).thenReturn(
                    new MonthlyCardService.GrantTodayResult(
                            new MonthlyCardService.CardStatus("MINI_MONTHLY", false, 0, 0, 0, 0), false));

            QolLoginHookService hook = new QolLoginHookService(
                    provider(ret), provider(reminder), provider(null), provider(monthly));
            long logout = Instant.now().minus(10, ChronoUnit.DAYS).toEpochMilli();
            hook.onLoginSuccess(1001, logout);
            assertTrue(monthlyCalled.get());
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM player_return_activity WHERE player_id=1001", Integer.class);
            assertEquals(1, n);
        }
    }

    @Nested
    @DisplayName("跨模块串联：社交名片→称号→搜索→组队")
    class CrossModuleE2E {

        @Test
        @DisplayName("设置状态+解锁称号+搜索到人+组队邀请")
        void profileTitleSearchParty() {
            PlayerProfileService profile = new PlayerProfileService(jdbc, mapper, dataDir.toString());
            assertTrue(profile.setStatus(1001, "来组队", "组队中", "PARTY").ok());

            TitleService titles = new TitleService(jdbc, provider((PlayerCardService) null));
            titles.unlockFromAchievement(1001, "battle_win_20");
            assertTrue(titles.equip(1001, "battle_ace").ok());

            FriendSearchService search = new FriendSearchService(jdbc);
            assertTrue(search.search("开拓者甲", 1, 5).hits().stream()
                    .anyMatch(h -> h.uid() == 1001));

            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.saveIfNewer(org.mockito.ArgumentMatchers.any())).thenReturn(true);
            PartyService party = new PartyService(store);
            party.create(1001L);
            PartyService.InviteManyResult invite = party.inviteMany(1001L, List.of(1002L));
            assertEquals(1, invite.invited());
            search.recordRecent(1001, 1002, "party_invite");
            assertEquals(1, search.listRecent(1001, 5).size());
        }
    }
}
