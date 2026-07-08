package cn.itcast.demo.mylunarcore.item;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ItemNettyService 道具协议服务测试")
class ItemNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ItemNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = 77L;
    private static final int PLAYER_ID = 77;

    private ItemRepository itemRepository;
    private ItemNettyService service;

    @BeforeEach
    void setUp() {
        itemRepository = mock(ItemRepository.class);
        service = new ItemNettyService(itemRepository);
        log.info("道具协议服务初始化: playerId={}, uidKey={}", PLAYER_ID, UID_KEY.name());
    }

    @Test
    @DisplayName("GetBag 成功应返回分页背包列表")
    void getBagSuccessShouldReturnPagedItems() {
        when(itemRepository.countBagItems(PLAYER_ID, 1)).thenReturn(2L);
        when(itemRepository.listBagItems(PLAYER_ID, 1, 1, 50)).thenReturn(List.of(
                ItemTestFixtures.bagItem(1001L, 23001, 1, 1),
                ItemTestFixtures.bagItem(1002L, 21001, 2, 1)
        ));

        ItemSystemProto.GetBagScRsp rsp = service.handleGetBag(
                ItemSystemProto.GetBagCsReq.newBuilder()
                        .setTypeFilter(1)
                        .setPage(1)
                        .setPageSize(50)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("背包查询校验: retcode={}, typeFilter=1, page=1, pageSize=50, totalCount={}, itemCount={}",
                rsp.getRetcode(), rsp.getTotalCount(), rsp.getItemsCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getTotalCount());
        assertEquals(2, rsp.getItemsCount());
        assertEquals(1001L, rsp.getItems(0).getUid());
    }

    @Test
    @DisplayName("未登录查询背包应返回 retcode=1")
    void getBagWithoutLoginShouldFail() {
        ItemSystemProto.GetBagScRsp rsp = service.handleGetBag(
                ItemSystemProto.GetBagCsReq.newBuilder().build(),
                loggedOutChannel());

        log.info("未登录背包查询校验: retcode={}, totalCount={}, itemCount={}",
                rsp.getRetcode(), rsp.getTotalCount(), rsp.getItemsCount());
        assertEquals(1, rsp.getRetcode());
        assertEquals(0, rsp.getTotalCount());
        verify(itemRepository, never()).countBagItems(anyInt(), anyInt());
    }

    @Test
    @DisplayName("非法 typeFilter 应降级为 0 并查询全部")
    void getBagInvalidTypeFilterShouldFallbackToAll() {
        when(itemRepository.countBagItems(PLAYER_ID, 0)).thenReturn(5L);
        when(itemRepository.listBagItems(PLAYER_ID, 0, 1, 50)).thenReturn(List.of());

        ItemSystemProto.GetBagScRsp rsp = service.handleGetBag(
                ItemSystemProto.GetBagCsReq.newBuilder()
                        .setTypeFilter(99)
                        .setPage(0)
                        .setPageSize(0)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("非法过滤降级校验: reqTypeFilter=99, reqPage=0, reqPageSize=0, retcode={}, totalCount={}",
                rsp.getRetcode(), rsp.getTotalCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(5, rsp.getTotalCount());
        verify(itemRepository).countBagItems(PLAYER_ID, 0);
        verify(itemRepository).listBagItems(PLAYER_ID, 0, 1, 50);
    }

    @Test
    @DisplayName("UseItem 材料类应批量扣减并推送变更通知")
    void useMaterialItemShouldConsumeAndNotify() {
        GameItemEntity material = ItemTestFixtures.item(2001L, 30001, 3, 10, 50, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 2001L)).thenReturn(material);
        when(itemRepository.toBagItem(material)).thenReturn(ItemTestFixtures.bagItem(2001L, 30001, 3, 10));

        Channel channel = loggedInActiveChannel(PLAYER_UID);
        ItemSystemProto.UseItemScRsp rsp = service.handleUseItem(
                ItemSystemProto.UseItemCsReq.newBuilder()
                        .setUid(2001L)
                        .setCount(3)
                        .setTargetAvatarId(1101)
                        .build(),
                channel);

        log.info("材料使用校验: uid=2001, reqCount=3, retcode={}, usedCount={}, playerExp={}, avatarExp={}",
                rsp.getRetcode(), rsp.getUsedCount(),
                rsp.getEffects().getPlayerExp(), rsp.getEffects().getAvatarExp());
        assertEquals(0, rsp.getRetcode());
        assertEquals(3, rsp.getUsedCount());
        assertEquals(150, rsp.getEffects().getPlayerExp());
        assertEquals(150, rsp.getEffects().getAvatarExp());
        verify(itemRepository).updateItemCountAndDiscard(PLAYER_ID, 2001L, 7, false);

        ArgumentCaptor<GamePacket> packetCaptor = ArgumentCaptor.forClass(GamePacket.class);
        verify(channel).writeAndFlush(packetCaptor.capture());
        GamePacket packet = packetCaptor.getValue();
        log.info("材料变更推送校验: cmdId={}, expectedCmdId={}, payloadSize={}",
                packet.getCmdId(), CmdIds.ITEM_CHANGE_SC_NOTIFY, packet.getPayload().length);
        assertEquals(CmdIds.ITEM_CHANGE_SC_NOTIFY, packet.getCmdId());
    }

    @Test
    @DisplayName("UseItem 非材料类应单次消耗并标记丢弃")
    void useNonMaterialItemShouldDiscardAfterUse() {
        GameItemEntity lightCone = ItemTestFixtures.item(3001L, 23001, 1, 1, 100, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 3001L)).thenReturn(lightCone);
        when(itemRepository.toBagItem(lightCone)).thenReturn(ItemTestFixtures.bagItem(3001L, 23001, 1, 1));
        when(itemRepository.findItemByUid(PLAYER_ID, 3001L)).thenReturn(lightCone).thenReturn(null);

        ItemSystemProto.UseItemScRsp rsp = service.handleUseItem(
                ItemSystemProto.UseItemCsReq.newBuilder()
                        .setUid(3001L)
                        .setCount(5)
                        .build(),
                loggedInActiveChannel(PLAYER_UID));

        log.info("非材料使用校验: uid=3001, reqCount=5, type=1, retcode={}, usedCount={}, playerExp={}",
                rsp.getRetcode(), rsp.getUsedCount(), rsp.getEffects().getPlayerExp());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getUsedCount());
        assertEquals(100, rsp.getEffects().getPlayerExp());
        verify(itemRepository).updateItemCountAndDiscard(PLAYER_ID, 3001L, 0, true);
    }

    @Test
    @DisplayName("UseItem 锁定道具应返回 retcode=3")
    void useLockedItemShouldFail() {
        GameItemEntity locked = ItemTestFixtures.item(4001L, 30001, 3, 5, 10, true, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 4001L)).thenReturn(locked);

        ItemSystemProto.UseItemScRsp rsp = service.handleUseItem(
                ItemSystemProto.UseItemCsReq.newBuilder().setUid(4001L).setCount(1).build(),
                loggedInChannel(PLAYER_UID));

        log.info("锁定道具使用校验: uid=4001, locked=true, retcode={}, usedCount={}",
                rsp.getRetcode(), rsp.getUsedCount());
        assertEquals(3, rsp.getRetcode());
        assertEquals(0, rsp.getUsedCount());
        verify(itemRepository, never()).updateItemCountAndDiscard(anyInt(), anyLong(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("EquipItem 光锥应装备到指定角色")
    void equipLightConeShouldBindAvatar() {
        GameItemEntity lightCone = ItemTestFixtures.item(5001L, 23001, 1, 1, 0, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 5001L)).thenReturn(lightCone);

        ItemSystemProto.EquipItemScRsp rsp = service.handleEquipItem(
                ItemSystemProto.EquipItemCsReq.newBuilder()
                        .setUid(5001L)
                        .setAvatarId(1102)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("装备光锥校验: uid=5001, avatarId=1102, type=1, retcode={}, rspAvatarId={}",
                rsp.getRetcode(), rsp.getAvatarId());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1102, rsp.getAvatarId());
        verify(itemRepository).setEquipAvatarId(PLAYER_ID, 5001L, 1102);
    }

    @Test
    @DisplayName("EquipItem 材料类不可装备应返回 retcode=5")
    void equipMaterialShouldFail() {
        GameItemEntity material = ItemTestFixtures.item(5002L, 30001, 3, 10, 0, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 5002L)).thenReturn(material);

        ItemSystemProto.EquipItemScRsp rsp = service.handleEquipItem(
                ItemSystemProto.EquipItemCsReq.newBuilder()
                        .setUid(5002L)
                        .setAvatarId(1102)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("不可装备类型校验: uid=5002, type=3, avatarId=1102, retcode={}", rsp.getRetcode());
        assertEquals(5, rsp.getRetcode());
        verify(itemRepository, never()).setEquipAvatarId(anyInt(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("UnequipItem 成功应清空 equipAvatarId")
    void unequipItemShouldClearAvatarBinding() {
        GameItemEntity relic = ItemTestFixtures.item(6001L, 21001, 2, 1, 0, false, false);
        relic.setEquipAvatarId(1103);
        when(itemRepository.findItemByUid(PLAYER_ID, 6001L)).thenReturn(relic);

        ItemSystemProto.UnequipItemScRsp rsp = service.handleUnequipItem(
                ItemSystemProto.UnequipItemCsReq.newBuilder().setUid(6001L).build(),
                loggedInChannel(PLAYER_UID));

        log.info("卸下装备校验: uid=6001, prevAvatarId=1103, retcode={}, rspAvatarId={}",
                rsp.getRetcode(), rsp.getAvatarId());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1103, rsp.getAvatarId());
        verify(itemRepository).setEquipAvatarId(PLAYER_ID, 6001L, null);
    }

    @Test
    @DisplayName("EnhanceItem 应消耗材料并提升等级经验")
    void enhanceItemShouldConsumeMaterialsAndUpgrade() {
        GameItemEntity target = ItemTestFixtures.item(7001L, 23001, 1, 1, 200, false, false);
        target.setLevel(5);
        GameItemEntity mat1 = ItemTestFixtures.item(7002L, 30002, 3, 2, 500, false, false);
        GameItemEntity mat2 = ItemTestFixtures.item(7003L, 30003, 3, 1, 800, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 7001L)).thenReturn(target);
        when(itemRepository.findItemByUid(PLAYER_ID, 7002L)).thenReturn(mat1);
        when(itemRepository.findItemByUid(PLAYER_ID, 7003L)).thenReturn(mat2);

        ItemSystemProto.EnhanceItemScRsp rsp = service.handleEnhanceItem(
                ItemSystemProto.EnhanceItemCsReq.newBuilder()
                        .setTargetUid(7001L)
                        .addMaterialUids(7002L)
                        .addMaterialUids(7003L)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("强化校验: targetUid=7001, materialCount=2, retcode={}, newLevel={}, newExp={}, consumedCount={}",
                rsp.getRetcode(), rsp.getNewLevel(), rsp.getNewExp(), rsp.getConsumedUidsCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(6, rsp.getNewLevel());
        assertEquals(2000, rsp.getNewExp());
        assertEquals(2, rsp.getConsumedUidsCount());
        verify(itemRepository).applyEnhance(PLAYER_ID, 7001L, 6, 2000L);
        verify(itemRepository).markDiscarded(PLAYER_ID, 7002L);
        verify(itemRepository).markDiscarded(PLAYER_ID, 7003L);
    }

    @Test
    @DisplayName("EnhanceItem 缺少材料参数应返回 retcode=5")
    void enhanceItemMissingMaterialsShouldFail() {
        ItemSystemProto.EnhanceItemScRsp rsp = service.handleEnhanceItem(
                ItemSystemProto.EnhanceItemCsReq.newBuilder()
                        .setTargetUid(7001L)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("强化参数非法校验: targetUid=7001, materialCount=0, retcode={}, newLevel={}",
                rsp.getRetcode(), rsp.getNewLevel());
        assertEquals(5, rsp.getRetcode());
        verify(itemRepository, never()).applyEnhance(anyInt(), anyLong(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("PromoteItem 成功应 promotion +1")
    void promoteItemShouldIncrementPromotion() {
        GameItemEntity item = ItemTestFixtures.item(8001L, 23001, 1, 1, 0, false, false);
        item.setPromotion(2);
        when(itemRepository.findItemByUid(PLAYER_ID, 8001L)).thenReturn(item);

        ItemSystemProto.PromoteItemScRsp rsp = service.handlePromoteItem(
                ItemSystemProto.PromoteItemCsReq.newBuilder().setUid(8001L).build(),
                loggedInChannel(PLAYER_UID));

        log.info("突破校验: uid=8001, prevPromotion=2, retcode={}, newPromotion={}",
                rsp.getRetcode(), rsp.getNewPromotion());
        assertEquals(0, rsp.getRetcode());
        assertEquals(3, rsp.getNewPromotion());
        verify(itemRepository).updatePromotion(PLAYER_ID, 8001L, 3);
    }

    @Test
    @DisplayName("RankUpItem 成功应提升 rank 并消耗材料")
    void rankUpItemShouldUpgradeBaseAndDiscardMaterial() {
        GameItemEntity base = ItemTestFixtures.item(9001L, 23001, 1, 1, 0, false, false);
        base.setRank(1);
        GameItemEntity material = ItemTestFixtures.item(9002L, 23001, 1, 1, 0, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 9001L)).thenReturn(base);
        when(itemRepository.findItemByUid(PLAYER_ID, 9002L)).thenReturn(material);

        ItemSystemProto.RankUpItemScRsp rsp = service.handleRankUpItem(
                ItemSystemProto.RankUpItemCsReq.newBuilder()
                        .setBaseUid(9001L)
                        .setMaterialUid(9002L)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("叠影校验: baseUid=9001, materialUid=9002, prevRank=1, retcode={}, newRank={}, consumedUid={}",
                rsp.getRetcode(), rsp.getNewRank(), rsp.getConsumedUid());
        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getNewRank());
        assertEquals(9002L, rsp.getConsumedUid());
        verify(itemRepository).updateRank(PLAYER_ID, 9001L, 2);
        verify(itemRepository).markDiscarded(PLAYER_ID, 9002L);
    }

    @Test
    @DisplayName("LockItem 成功应更新锁定状态")
    void lockItemShouldUpdateLockedFlag() {
        when(itemRepository.setLocked(PLAYER_ID, 10001L, true)).thenReturn(1);

        ItemSystemProto.LockItemScRsp rsp = service.handleLockItem(
                ItemSystemProto.LockItemCsReq.newBuilder()
                        .setUid(10001L)
                        .setLock(true)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("锁定道具校验: uid=10001, lock=true, retcode={}, locked={}",
                rsp.getRetcode(), rsp.getLocked());
        assertEquals(0, rsp.getRetcode());
        assertTrue(rsp.getLocked());
        verify(itemRepository).setLocked(PLAYER_ID, 10001L, true);
    }

    @Test
    @DisplayName("LockItem 道具不存在应返回 retcode=2")
    void lockMissingItemShouldFail() {
        when(itemRepository.setLocked(PLAYER_ID, 10002L, false)).thenReturn(0);

        ItemSystemProto.LockItemScRsp rsp = service.handleLockItem(
                ItemSystemProto.LockItemCsReq.newBuilder()
                        .setUid(10002L)
                        .setLock(false)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("锁定不存在道具校验: uid=10002, lock=false, retcode={}, locked={}",
                rsp.getRetcode(), rsp.getLocked());
        assertEquals(2, rsp.getRetcode());
        assertFalse(rsp.getLocked());
    }

    @Test
    @DisplayName("DiscardItem 成功应扣减数量")
    void discardItemShouldReduceCount() {
        GameItemEntity item = ItemTestFixtures.item(11001L, 30001, 3, 8, 0, false, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 11001L)).thenReturn(item);

        ItemSystemProto.DiscardItemScRsp rsp = service.handleDiscardItem(
                ItemSystemProto.DiscardItemCsReq.newBuilder()
                        .setUid(11001L)
                        .setCount(3)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("丢弃道具校验: uid=11001, reqCount=3, prevCount=8, retcode={}, remainingCount={}",
                rsp.getRetcode(), rsp.getRemainingCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(5, rsp.getRemainingCount());
        verify(itemRepository).updateItemCountAndDiscard(PLAYER_ID, 11001L, 5, false);
    }

    @Test
    @DisplayName("DiscardItem 锁定道具应返回 retcode=3")
    void discardLockedItemShouldFail() {
        GameItemEntity locked = ItemTestFixtures.item(11002L, 30001, 3, 4, 0, true, false);
        when(itemRepository.findItemByUid(PLAYER_ID, 11002L)).thenReturn(locked);

        ItemSystemProto.DiscardItemScRsp rsp = service.handleDiscardItem(
                ItemSystemProto.DiscardItemCsReq.newBuilder()
                        .setUid(11002L)
                        .setCount(1)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("锁定道具丢弃校验: uid=11002, locked=true, prevCount=4, retcode={}, remainingCount={}",
                rsp.getRetcode(), rsp.getRemainingCount());
        assertEquals(3, rsp.getRetcode());
        assertEquals(4, rsp.getRemainingCount());
        verify(itemRepository, never()).updateItemCountAndDiscard(anyInt(), anyLong(), anyLong(), eq(true));
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
