package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.player.DataChangeScope;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService;
import java.util.OptionalLong;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GachaNettyService 抽卡协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaNettyService 抽卡协议服务测试")
class GachaNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GachaNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = 77L;
    private static final int PLAYER_ID = 77;

    private GachaConfigService configService;
    private GachaRepository gachaRepository;
    private ItemRepository itemRepository;
    private GachaApplicationService gachaApplicationService;
    private PlayerContextResolver contextResolver;
    private PlayerDataSyncService playerDataSyncService;
    private GachaNettyService service;

    @BeforeEach
    void setUp() {
        configService = mock(GachaConfigService.class);
        gachaRepository = mock(GachaRepository.class);
        itemRepository = mock(ItemRepository.class);
        gachaApplicationService = mock(GachaApplicationService.class);
        contextResolver = mock(PlayerContextResolver.class);
        playerDataSyncService = mock(PlayerDataSyncService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<GachaPresentationService> presentationProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(presentationProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<GachaRebateService> rebateProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(rebateProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook> memoryProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.character.ConstellationService> constellationProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(constellationProvider.getIfAvailable()).thenReturn(null);
        service = new GachaNettyService(configService, gachaRepository, itemRepository,
                gachaApplicationService, mock(cn.itcast.demo.mylunarcore.repo.GachaHistoryRepository.class),
                new GachaDrawEngine(), contextResolver, playerDataSyncService,
                presentationProvider, rebateProvider, memoryProvider, constellationProvider);
        log.info("抽卡协议服务初始化: playerId={}, defaultCostItemId=101", PLAYER_ID);
    }

    /**
     * 验证点：GetGachaInfo 成功应返回开放卡池与 ceiling 信息。
     * <p>测试方法 {@code getGachaInfoSuccessShouldReturnBannersAndCeiling}：
     * <ul>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NEWBIE), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.AVATAR_UP), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.WEAPON_UP), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))}</li>
     *   <li>{@code when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetGachaInfo 成功应返回开放卡池与 ceiling 信息")
    void getGachaInfoSuccessShouldReturnBannersAndCeiling() {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        when(configService.pickActiveBanner(eq(GachaBannerType.NEWBIE), anyLong())).thenReturn(null);
        when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);
        when(configService.pickActiveBanner(eq(GachaBannerType.AVATAR_UP), anyLong())).thenReturn(null);
        when(configService.pickActiveBanner(eq(GachaBannerType.WEAPON_UP), anyLong())).thenReturn(null);
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 120, false));
        when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))
                .thenReturn(GachaTestFixtures.bannerInfo(PLAYER_ID, GachaBannerType.NORMAL, 10, 3, 0));

        GachaSystemProto.GetGachaInfoScRsp rsp = service.handleGetGachaInfo(loggedInChannel(PLAYER_UID));

        log.info("抽卡信息查询校验: retcode={}, bannerCount={}, normalBannerId={}, ceilingNum={}, ceilingClaimed={}",
                rsp.getRetcode(), rsp.getBannersCount(), rsp.getBanners(0).getBannerId(),
                rsp.getCeilingInfo().getCeilingNum(), rsp.getCeilingInfo().getCeilingClaimed());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getBannersCount());
        assertEquals(1001, rsp.getBanners(0).getBannerId());
        assertEquals(120, rsp.getCeilingInfo().getCeilingNum());
        assertFalse(rsp.getCeilingInfo().getCeilingClaimed());
    }

    /**
     * 验证点：未登录查询抽卡信息应返回 retcode=1。
     * <p>测试方法 {@code getGachaInfoWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录查询抽卡信息应返回 retcode=1")
    void getGachaInfoWithoutLoginShouldFail() {
        GachaSystemProto.GetGachaInfoScRsp rsp = service.handleGetGachaInfo(loggedOutChannel());
        log.info("未登录查询校验: retcode={}, bannerCount={}", rsp.getRetcode(), rsp.getBannersCount());
        assertEquals(1, rsp.getRetcode());
    }

    /**
     * 验证点：DoGacha 非法次数应返回 retcode=2。
     * <p>测试方法 {@code doGachaInvalidTimesShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     *   <li>{@code verify(gachaApplicationService, never()).persistDraw(anyInt(), anyInt(), anyInt(), anyList(), anyInt(), anyInt(), any...}</li>
     * </ul>
     */
    @Test
    @DisplayName("DoGacha 非法次数应返回 retcode=2")
    void doGachaInvalidTimesShouldFail() {
        GachaSystemProto.DoGachaScRsp rsp = service.handleDoGacha(
                GachaSystemProto.DoGachaCsReq.newBuilder()
                        .setBannerType(GachaBannerType.NORMAL)
                        .setTimes(5)
                        .build(),
                loggedInChannel(PLAYER_UID));
        log.info("非法抽卡次数校验: bannerType={}, times=5, retcode={}", GachaBannerType.NORMAL, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
        verify(gachaApplicationService, never()).persistDraw(anyInt(), anyInt(), anyInt(), anyList(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：DoGacha 卡池未开放应返回 retcode=3。
     * <p>测试方法 {@code doGachaMissingBannerShouldFail}：
     * <ul>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(null);}</li>
     *   <li>{@code assertEquals(3, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("DoGacha 卡池未开放应返回 retcode=3")
    void doGachaMissingBannerShouldFail() {
        when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(null);

        GachaSystemProto.DoGachaScRsp rsp = service.handleDoGacha(
                GachaSystemProto.DoGachaCsReq.newBuilder()
                        .setBannerType(GachaBannerType.NORMAL)
                        .setTimes(1)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("卡池未开放校验: bannerType={}, times=1, retcode={}", GachaBannerType.NORMAL, rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
    }

    /**
     * 验证点：DoGacha 单抽成功应发放道具并更新 pity。
     * <p>测试方法 {@code doGachaSingleDrawShouldGrantItemAndUpdatePity}：
     * <ul>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);}</li>
     *   <li>{@code when(configService.pickNormalBannerForFallback(anyLong())).thenReturn(normal);}</li>
     *   <li>{@code when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))}</li>
     *   <li>{@code when(itemRepository.existsActiveItemByItemId(eq(PLAYER_ID), anyInt())).thenReturn(false);}</li>
     *   <li>{@code when(gachaApplicationService.persistDraw(eq(PLAYER_ID), eq(GachaBannerType.NORMAL), eq(1), anyList(), eq(1), anyInt()...}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("DoGacha 单抽成功应发放道具并更新 pity")
    void doGachaSingleDrawShouldGrantItemAndUpdatePity() {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);
        when(configService.pickNormalBannerForFallback(anyLong())).thenReturn(normal);
        when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))
                .thenReturn(GachaTestFixtures.bannerInfo(PLAYER_ID, GachaBannerType.NORMAL, 0, 0, 0));
        when(itemRepository.existsActiveItemByItemId(eq(PLAYER_ID), anyInt())).thenReturn(false);
        when(gachaApplicationService.persistDraw(eq(PLAYER_ID), eq(GachaBannerType.NORMAL), eq(1), anyList(), eq(1), anyInt(), eq(0)))
                .thenReturn(new GachaApplicationService.DrawPersistResult(
                        true, 0,
                        GachaSystemProto.CeilingInfo.newBuilder().setCeilingNum(6).setCeilingClaimed(false).build()));

        GachaSystemProto.DoGachaScRsp rsp = service.handleDoGacha(
                GachaSystemProto.DoGachaCsReq.newBuilder()
                        .setBannerType(GachaBannerType.NORMAL)
                        .setTimes(1)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("单抽成功校验: retcode={}, bannerType={}, itemCount={}, firstItemId={}, isNew={}, pity5={}, pity4={}, ceilingNum={}",
                rsp.getRetcode(), rsp.getBannerType(), rsp.getItemsCount(),
                rsp.getItems(0).getItemId(), rsp.getItems(0).getIsNew(),
                rsp.getUpdatedPity().getPity5(), rsp.getUpdatedPity().getPity4(),
                rsp.getUpdatedCeiling().getCeilingNum());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getItemsCount());
        assertTrue(rsp.getItems(0).getItemId() > 0);
        assertEquals(1, rsp.getUpdatedPity().getPity5());
        assertEquals(6, rsp.getUpdatedCeiling().getCeilingNum());
        verify(gachaApplicationService).persistDraw(eq(PLAYER_ID), eq(GachaBannerType.NORMAL), eq(1), anyList(), eq(1), anyInt(), eq(0));
        verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.GACHA);
    }

    /**
     * 验证点：ExchangeCeiling 未达 300 抽应返回 retcode=4。
     * <p>测试方法 {@code exchangeCeilingNotReachedShouldFail}：
     * <ul>
     *   <li>{@code when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))}</li>
     *   <li>{@code assertEquals(4, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("ExchangeCeiling 未达 300 抽应返回 retcode=4")
    void exchangeCeilingNotReachedShouldFail() {
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 299, false));

        GachaSystemProto.ExchangeGachaCeilingScRsp rsp = service.handleExchangeCeiling(
                GachaSystemProto.ExchangeGachaCeilingCsReq.newBuilder()
                        .setTargetAvatarId(1102)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("天井未达标校验: ceilingNum=299, targetAvatarId=1102, retcode={}", rsp.getRetcode());
        assertEquals(4, rsp.getRetcode());
    }

    /**
     * 验证点：ExchangeCeiling 成功应发放自选角色并标记已领取。
     * <p>测试方法 {@code exchangeCeilingSuccessShouldGrantAvatar}：
     * <ul>
     *   <li>{@code when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))}</li>
     *   <li>{@code when(gachaApplicationService.persistCeilingExchange(PLAYER_ID, 1102))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1102, rsp.getRewardItem().getItemId());}</li>
     *   <li>{@code assertFalse(rsp.getRewardItem().getIsNew());}</li>
     *   <li>{@code assertTrue(rsp.getUpdatedCeiling().getCeilingClaimed());}</li>
     * </ul>
     */
    @Test
    @DisplayName("ExchangeCeiling 成功应发放自选角色并标记已领取")
    void exchangeCeilingSuccessShouldGrantAvatar() {
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 300, false));
        GachaSystemProto.GachaItem reward = GachaSystemProto.GachaItem.newBuilder()
                .setItemId(1102)
                .setCount(1)
                .setIsNew(false)
                .build();
        GachaSystemProto.CeilingInfo ceiling = GachaSystemProto.CeilingInfo.newBuilder()
                .setCeilingNum(300)
                .setCeilingClaimed(true)
                .build();
        when(gachaApplicationService.persistCeilingExchange(PLAYER_ID, 1102))
                .thenReturn(new GachaApplicationService.ExchangePersistResult(reward, ceiling));

        GachaSystemProto.ExchangeGachaCeilingScRsp rsp = service.handleExchangeCeiling(
                GachaSystemProto.ExchangeGachaCeilingCsReq.newBuilder()
                        .setTargetAvatarId(1102)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("天井兑换校验: retcode={}, targetAvatarId={}, rewardItemId={}, isNew={}, ceilingNum={}, ceilingClaimed={}",
                rsp.getRetcode(), 1102, rsp.getRewardItem().getItemId(), rsp.getRewardItem().getIsNew(),
                rsp.getUpdatedCeiling().getCeilingNum(), rsp.getUpdatedCeiling().getCeilingClaimed());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1102, rsp.getRewardItem().getItemId());
        assertFalse(rsp.getRewardItem().getIsNew());
        assertTrue(rsp.getUpdatedCeiling().getCeilingClaimed());
        verify(gachaApplicationService).persistCeilingExchange(PLAYER_ID, 1102);
        verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.GACHA);
    }

    /**
     * 验证点：GetGachaHistory 应返回空历史占位。
     * <p>测试方法 {@code getHistoryShouldReturnEmptyPlaceholder}：
     * <ul>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0, rsp.getTotalCount());}</li>
     *   <li>{@code assertEquals(0, rsp.getRecordsCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetGachaHistory 应返回空历史占位")
    void getHistoryShouldReturnEmptyPlaceholder() {
        GachaSystemProto.GetGachaHistoryScRsp rsp = service.handleGetHistory(
                GachaSystemProto.GetGachaHistoryCsReq.newBuilder().build(),
                loggedInChannel(PLAYER_UID));

        log.info("抽卡历史查询校验: retcode={}, totalCount={}, recordCount={}",
                rsp.getRetcode(), rsp.getTotalCount(), rsp.getRecordsCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(0, rsp.getTotalCount());
        assertEquals(0, rsp.getRecordsCount());
    }

    /**
     * 验证点：pushBannerUpdateNotify 应向活跃 Channel 推送 508 通知。
     * <p>测试方法 {@code pushBannerUpdateNotifyShouldWritePacket}：
     * <ul>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NEWBIE), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.AVATAR_UP), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(configService.pickActiveBanner(eq(GachaBannerType.WEAPON_UP), anyLong())).thenReturn(null);}</li>
     *   <li>{@code when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))}</li>
     *   <li>{@code when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))}</li>
     * </ul>
     */
    @Test
    @DisplayName("pushBannerUpdateNotify 应向活跃 Channel 推送 508 通知")
    void pushBannerUpdateNotifyShouldWritePacket() throws Exception {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        when(configService.pickActiveBanner(eq(GachaBannerType.NEWBIE), anyLong())).thenReturn(null);
        when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);
        when(configService.pickActiveBanner(eq(GachaBannerType.AVATAR_UP), anyLong())).thenReturn(null);
        when(configService.pickActiveBanner(eq(GachaBannerType.WEAPON_UP), anyLong())).thenReturn(null);
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 0, false));
        when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))
                .thenReturn(GachaTestFixtures.bannerInfo(PLAYER_ID, GachaBannerType.NORMAL, 0, 0, 0));

        Channel channel = loggedInActiveChannel(PLAYER_UID);
        service.pushBannerUpdateNotify(channel);

        ArgumentCaptor<GamePacket> packetCaptor = ArgumentCaptor.forClass(GamePacket.class);
        verify(channel).writeAndFlush(packetCaptor.capture());
        GamePacket packet = packetCaptor.getValue();
        log.info("卡池热更推送校验: cmdId={}, expectedCmdId={}, payloadSize={}",
                packet.getCmdId(), CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY, packet.getPayload().length);
        assertEquals(CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY, packet.getCmdId());
        assertTrue(packet.getPayload().length > 0);
    }

    @Test
    @DisplayName("GachaStart→Ack 表现层握手应返回 session 与 client_ui")
    void gachaPresentationHandshakeShouldReturnSessionAndUi() {
        GachaPresentationService presentation = new GachaPresentationService(mock(org.springframework.jdbc.core.JdbcTemplate.class));
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<GachaPresentationService> presentationProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(presentationProvider.getIfAvailable()).thenReturn(presentation);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<GachaRebateService> rebateProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(rebateProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook> memoryProvider2 =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(memoryProvider2.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.character.ConstellationService> constellationProvider2 =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(constellationProvider2.getIfAvailable()).thenReturn(null);
        GachaNettyService withPresentation = new GachaNettyService(configService, gachaRepository, itemRepository,
                gachaApplicationService, mock(cn.itcast.demo.mylunarcore.repo.GachaHistoryRepository.class),
                new GachaDrawEngine(), contextResolver, playerDataSyncService,
                presentationProvider, rebateProvider, memoryProvider2, constellationProvider2);

        GachaSystemProto.GachaStartScRsp start = withPresentation.handleGachaStart(
                GachaSystemProto.GachaStartCsReq.newBuilder()
                        .setBannerType(11).setTimes(10).setClientNonce("n1").build(),
                loggedInChannel(PLAYER_UID));
        assertEquals(0, start.getRetcode());
        assertFalse(start.getPresentationSessionId().isBlank());
        assertEquals("gacha_ten_pull", start.getClientUi().getFxId());

        GachaSystemProto.GachaResultAckScRsp ack = withPresentation.handleGachaResultAck(
                GachaSystemProto.GachaResultAckCsReq.newBuilder()
                        .setPresentationSessionId(start.getPresentationSessionId())
                        .setSkipped(false).build(),
                loggedInChannel(PLAYER_UID));
        assertEquals(0, ack.getRetcode());
    }

    private Channel loggedInChannel(long uid) {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn((int) (uid & 0xffffffffL));
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.of(uid));
        log.info("模拟登录 Channel: uid={}, playerId={}", uid, (int) (uid & 0xffffffffL));
        return channel;
    }

    private Channel loggedInActiveChannel(long uid) {
        Channel channel = loggedInChannel(uid);
        when(channel.isActive()).thenReturn(true);
        return channel;
    }

    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());
        log.info("模拟未登录 Channel: uid=null");
        return channel;
    }
}
