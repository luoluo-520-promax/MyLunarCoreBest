package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GachaNettyService 抽卡协议服务测试")
class GachaNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GachaNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = 77L;
    private static final int PLAYER_ID = 77;

    private GachaConfigService configService;
    private GachaRepository gachaRepository;
    private ItemRepository itemRepository;
    private GachaNettyService service;

    @BeforeEach
    void setUp() {
        configService = mock(GachaConfigService.class);
        gachaRepository = mock(GachaRepository.class);
        itemRepository = mock(ItemRepository.class);
        service = new GachaNettyService(configService, gachaRepository, itemRepository);
        log.info("抽卡协议服务初始化: playerId={}, defaultCostItemId=101", PLAYER_ID);
    }

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

    @Test
    @DisplayName("未登录查询抽卡信息应返回 retcode=1")
    void getGachaInfoWithoutLoginShouldFail() {
        GachaSystemProto.GetGachaInfoScRsp rsp = service.handleGetGachaInfo(loggedOutChannel());
        log.info("未登录查询校验: retcode={}, bannerCount={}", rsp.getRetcode(), rsp.getBannersCount());
        assertEquals(1, rsp.getRetcode());
    }

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
        verify(gachaRepository, never()).updateBannerPity(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

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

    @Test
    @DisplayName("DoGacha 单抽成功应发放道具并更新 pity")
    void doGachaSingleDrawShouldGrantItemAndUpdatePity() {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        when(configService.pickActiveBanner(eq(GachaBannerType.NORMAL), anyLong())).thenReturn(normal);
        when(configService.pickNormalBannerForFallback(anyLong())).thenReturn(normal);
        when(gachaRepository.loadOrCreateBannerInfo(PLAYER_ID, GachaBannerType.NORMAL))
                .thenReturn(GachaTestFixtures.bannerInfo(PLAYER_ID, GachaBannerType.NORMAL, 0, 0, 0));
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 6, false));
        when(itemRepository.existsActiveItemByItemId(eq(PLAYER_ID), anyInt())).thenReturn(false);

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
        verify(gachaRepository).updateBannerPity(eq(PLAYER_ID), eq(GachaBannerType.NORMAL), eq(1), anyInt(), eq(0));
        verify(gachaRepository).incrementCeilingNum(PLAYER_ID, 1);
        verify(itemRepository).addSimpleItem(eq(PLAYER_ID), anyInt(), eq(3), eq(1L));
    }

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

    @Test
    @DisplayName("ExchangeCeiling 成功应发放自选角色并标记已领取")
    void exchangeCeilingSuccessShouldGrantAvatar() {
        when(gachaRepository.loadOrCreateGachaInfo(PLAYER_ID))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 300, false))
                .thenReturn(GachaTestFixtures.gachaInfo(PLAYER_ID, 300, true));
        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 1102)).thenReturn(true);

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
        verify(gachaRepository).setCeilingClaimed(PLAYER_ID, true);
        verify(itemRepository).addSimpleItem(PLAYER_ID, 1102, 3, 1);
    }

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

    private Channel loggedInChannel(long uid) {
        Channel channel = mock(Channel.class);
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(uid);
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
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(null);
        log.info("模拟未登录 Channel: uid=null");
        return channel;
    }
}
