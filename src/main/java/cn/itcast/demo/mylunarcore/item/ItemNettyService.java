// 道具 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.item;

// 玩家背包道具持久化实体
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
// 背包道具 CRUD 与分页查询仓储
import cn.itcast.demo.mylunarcore.net.CmdIds;
// Netty 下行封包（cmdId + Protobuf 载荷）
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 道具系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
// Netty 客户端连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// 数据变更后触发 Session 刷新
import cn.itcast.demo.mylunarcore.net.mapper.ItemProtoMapper;
import cn.itcast.demo.mylunarcore.player.DataChangeScope;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring @Service 业务 Bean
import org.springframework.stereotype.Service;

// Protobuf 二进制字节串
import com.google.protobuf.ByteString;

// 可变数组列表
import java.util.ArrayList;
// 不可变空集合工厂
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * 道具与背包 Netty 业务：拉取背包、使用/装备/卸下/强化/突破/叠影/锁定/丢弃等，依赖 {@link ItemRepository} 持久化并与 uid 通道属性绑定。
 */
@Service // Spring Bean：道具玩法 Netty 消息分发入口
public class ItemNettyService {

    // 本类日志记录器（道具业务分类）
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ITEM, ItemNettyService.class); // 绑定道具业务分类 SLF4J 日志

    // 道具应用服务（事务写库）
    private final ItemApplicationService itemApplicationService;
    // Entity → Protobuf
    private final ItemProtoMapper itemProtoMapper;
    // 玩家上下文解析
    private final PlayerContextResolver contextResolver;
    // 数据变更同步
    private final PlayerDataSyncService playerDataSyncService;

    /**
     * 构造器注入道具服务依赖。
     */
    public ItemNettyService(ItemApplicationService itemApplicationService,
                            ItemProtoMapper itemProtoMapper,
                            PlayerContextResolver contextResolver,
                            PlayerDataSyncService playerDataSyncService) {
        this.itemApplicationService = itemApplicationService;
        this.itemProtoMapper = itemProtoMapper;
        this.contextResolver = contextResolver;
        this.playerDataSyncService = playerDataSyncService;
    }

    private void notifyDataChangedIfOnline(Channel channel) {
        contextResolver.resolveUid(channel).ifPresent(uid ->
                playerDataSyncService.notifyDataChanged(uid, DataChangeScope.ITEMS));
    }

    /**
     * 分页拉取玩家背包道具列表，支持按道具类型过滤。
     */
    public ItemSystemProto.GetBagScRsp handleGetBag(ItemSystemProto.GetBagCsReq req, Channel channel) { // 处理查询背包请求
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.GetBagScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setTotalCount(0) // 总条数为 0
                    .build(); // 完成响应构建
        }

        int typeFilter = (int) req.getTypeFilter(); // 读取道具类型过滤（0=全部）
        if (typeFilter < 0 || typeFilter > 3) { // 类型过滤超出合法范围
            typeFilter = 0; // 非法时降级为不过滤
        }
        int page = req.getPage() <= 0 ? 1 : (int) req.getPage(); // 分页页码，默认第 1 页
        int pageSize = req.getPageSize() <= 0 ? 50 : (int) req.getPageSize(); // 每页条数，默认 50
        pageSize = Math.min(200, pageSize); // 限制单页最多 200 条防滥用

        long total = itemApplicationService.countBagItems(playerId, typeFilter);
        List<GameItemEntity> entities = itemApplicationService.listBagItems(playerId, typeFilter, page, pageSize);
        List<ItemSystemProto.BagItem> items = itemProtoMapper.toBagItems(entities);

        return ItemSystemProto.GetBagScRsp.newBuilder() // 组装查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setTotalCount((int) Math.max(0L, total)) // 写入总条数（保证非负）
                .addAllItems(items) // 写入当前页道具列表
                .build(); // 完成响应构建
    }

    /**
     * 使用道具：扣减数量/标记丢弃，计算经验效果并推送背包变更通知。
     */
    public ItemSystemProto.UseItemScRsp handleUseItem(ItemSystemProto.UseItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.UseItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .setUsedCount(0)
                    .build();
        }

        long uid = req.getUid();
        if (uid <= 0) {
            return ItemSystemProto.UseItemScRsp.newBuilder()
                    .setRetcode(5)
                    .setUid(0)
                    .setUsedCount(0)
                    .build();
        }

        GameItemEntity item = itemApplicationService.findItem(playerId, uid);
        if (item == null || item.isDiscarded()) {
            return ItemSystemProto.UseItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setUsedCount(0)
                    .build();
        }
        if (item.isLocked()) {
            return ItemSystemProto.UseItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setUid(uid)
                    .setUsedCount(0)
                    .build();
        }

        long reqCount = req.getCount() <= 0 ? 1 : req.getCount();
        ItemApplicationService.UseItemResult result =
                itemApplicationService.useItem(playerId, uid, reqCount, (int) req.getTargetAvatarId());
        if (result == null) {
            return ItemSystemProto.UseItemScRsp.newBuilder()
                    .setRetcode(4)
                    .setUid(uid)
                    .setUsedCount(0)
                    .build();
        }

        ItemSystemProto.UseItemEffects effects = ItemSystemProto.UseItemEffects.newBuilder()
                .setPlayerExp(result.playerExp())
                .setAvatarExp(result.avatarExp())
                .build();

        try {
            ItemSystemProto.BagItem oldItem = itemProtoMapper.toBagItem(item);
            ItemSystemProto.BagItem updatedItem;
            if (result.discarded()) {
                GameItemEntity after = itemApplicationService.findItem(playerId, uid);
                updatedItem = after == null ? oldItem : itemProtoMapper.toBagItem(after);
            } else {
                updatedItem = oldItem.toBuilder().setCount((int) result.newCount()).build();
            }
            ItemSystemProto.ItemChangeScNotify notify = ItemSystemProto.ItemChangeScNotify.newBuilder()
                    .setChangeType(result.discarded() ? 3 : 2)
                    .addItems(result.discarded() ? oldItem : updatedItem)
                    .build();
            channel.writeAndFlush(new GamePacket(CmdIds.ITEM_CHANGE_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            log.debug("send ItemChangeScNotify failed", e);
        }

        notifyDataChangedIfOnline(channel);

        return ItemSystemProto.UseItemScRsp.newBuilder()
                .setRetcode(0)
                .setUid(uid)
                .setUsedCount((int) Math.min(Integer.MAX_VALUE, Math.max(0L, result.usedCount())))
                .setEffects(effects)
                .build();
    }

    /**
     * 将光锥/遗器类道具装备到指定角色。
     */
    public ItemSystemProto.EquipItemScRsp handleEquipItem(ItemSystemProto.EquipItemCsReq req, Channel channel) { // 处理装备道具请求
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.EquipItemScRsp.newBuilder().setRetcode(1).setUid(req.getUid()).build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 待装备道具实例 uid
        int avatarId = (int) req.getAvatarId(); // 目标角色 ID
        GameItemEntity item = itemApplicationService.findItem(playerId, uid); // 按 uid 查询道具
        if (item == null || item.isDiscarded()) { // 道具不存在或已丢弃
            return ItemSystemProto.EquipItemScRsp.newBuilder().
                    setRetcode(2)
                    .setUid(uid)
                    .setAvatarId(avatarId)
                    .build(); // retcode=2：道具不存在
        }
        if (item.getType() != 1 && item.getType() != 2) { // 仅光锥(type=1)/遗器(type=2)可装备
            return ItemSystemProto.EquipItemScRsp.newBuilder()
                    .setRetcode(5)
                    .setUid(uid)
                    .setAvatarId(avatarId)
                    .build(); // retcode=5：类型不可装备
        }
        if (item.isLocked()) { // 道具已锁定，禁止变更
            return ItemSystemProto.EquipItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setUid(uid)
                    .setAvatarId(avatarId)
                    .build(); // retcode=3：道具已锁定
        }

        if (!itemApplicationService.equipItem(playerId, uid, avatarId)) {
            return ItemSystemProto.EquipItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setAvatarId(avatarId)
                    .build();
        }
        notifyDataChangedIfOnline(channel);
        // avatar_attributes 依赖未提供的额外表；演示返回空 JSON 字节
        return ItemSystemProto.EquipItemScRsp.newBuilder() // 组装装备成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setAvatarId(avatarId) // 回写装备角色 ID
                .setAvatarAttributes(ByteString.copyFrom(toEmptyJsonBytes())) // 写入空角色属性 JSON
                .build(); // 完成响应构建
    }

    /**
     * 卸下已装备道具，清空 equipAvatarId。
     */
    public ItemSystemProto.UnequipItemScRsp handleUnequipItem(ItemSystemProto.UnequipItemCsReq req, Channel channel) { // 处理卸下装备请求
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.UnequipItemScRsp.newBuilder().setRetcode(1).setUid(req.getUid()).build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 待卸下道具实例 uid
        GameItemEntity item = itemApplicationService.findItem(playerId, uid); // 按 uid 查询道具
        if (item == null || item.isDiscarded()) { // 道具不存在或已丢弃
            return ItemSystemProto.UnequipItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setAvatarId(0)
                    .build(); // retcode=2：道具不存在
        }
        if (item.isLocked()) { // 道具已锁定，禁止变更
            return ItemSystemProto.UnequipItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setUid(uid)
                    .setAvatarId(0)
                    .build(); // retcode=3：道具已锁定
        }

        Integer avatarId = itemApplicationService.unequipItem(playerId, uid);
        if (avatarId == null) {
            return ItemSystemProto.UnequipItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setAvatarId(0)
                    .build();
        }
        notifyDataChangedIfOnline(channel);

        return ItemSystemProto.UnequipItemScRsp.newBuilder()
                .setRetcode(0)
                .setUid(uid)
                .setAvatarId(avatarId)
                .setAvatarAttributes(ByteString.copyFrom(toEmptyJsonBytes()))
                .build();
    }

    public ItemSystemProto.EnhanceItemScRsp handleEnhanceItem(ItemSystemProto.EnhanceItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.EnhanceItemScRsp.newBuilder().setRetcode(1).setTargetUid(req.getTargetUid()).build();
        }

        long targetUid = req.getTargetUid();
        List<Long> materialUids = req.getMaterialUidsList();
        if (targetUid <= 0 || materialUids == null || materialUids.isEmpty()) {
            return ItemSystemProto.EnhanceItemScRsp.newBuilder()
                    .setRetcode(5)
                    .setTargetUid(targetUid)
                    .setNewLevel(0)
                    .setNewExp(0)
                    .addAllConsumedUids(Collections.emptyList())
                    .build();
        }

        GameItemEntity target = itemApplicationService.findItem(playerId, targetUid);
        if (target == null || target.isDiscarded()) {
            return ItemSystemProto.EnhanceItemScRsp.newBuilder().setRetcode(2).setTargetUid(targetUid).build();
        }
        if (target.isLocked()) {
            return ItemSystemProto.EnhanceItemScRsp.newBuilder().setRetcode(3).setTargetUid(targetUid).build();
        }

        ItemApplicationService.EnhanceItemResult result =
                itemApplicationService.enhanceItem(playerId, targetUid, materialUids);
        if (result == null) {
            return ItemSystemProto.EnhanceItemScRsp.newBuilder().setRetcode(2).setTargetUid(targetUid).build();
        }
        notifyDataChangedIfOnline(channel);

        return ItemSystemProto.EnhanceItemScRsp.newBuilder()
                .setRetcode(0)
                .setTargetUid(targetUid)
                .setNewLevel(result.newLevel())
                .setNewExp((int) Math.max(0L, Math.min(Integer.MAX_VALUE, result.newExp())))
                .addAllConsumedUids(result.consumedUids())
                .build();
    }

    public ItemSystemProto.PromoteItemScRsp handlePromoteItem(ItemSystemProto.PromoteItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.PromoteItemScRsp.newBuilder().setRetcode(1).setUid(req.getUid()).build();
        }
        long uid = req.getUid();
        GameItemEntity item = itemApplicationService.findItem(playerId, uid);
        if (item == null || item.isDiscarded()) {
            return ItemSystemProto.PromoteItemScRsp.newBuilder().setRetcode(2).setUid(uid).build();
        }
        if (item.isLocked()) {
            return ItemSystemProto.PromoteItemScRsp.newBuilder().setRetcode(3).setUid(uid).build();
        }
        Integer newPromotion = itemApplicationService.promoteItem(playerId, uid);
        if (newPromotion == null) {
            return ItemSystemProto.PromoteItemScRsp.newBuilder().setRetcode(2).setUid(uid).build();
        }
        notifyDataChangedIfOnline(channel);
        return ItemSystemProto.PromoteItemScRsp.newBuilder()
                .setRetcode(0)
                .setUid(uid)
                .setNewPromotion(newPromotion)
                .build();
    }

    public ItemSystemProto.RankUpItemScRsp handleRankUpItem(ItemSystemProto.RankUpItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.RankUpItemScRsp.newBuilder().setRetcode(1).setBaseUid(req.getBaseUid()).build();
        }

        long baseUid = req.getBaseUid();
        long materialUid = req.getMaterialUid();
        ItemApplicationService.RankUpItemResult result =
                itemApplicationService.rankUpItem(playerId, baseUid, materialUid);
        if (result == null) {
            GameItemEntity base = itemApplicationService.findItem(playerId, baseUid);
            if (base == null || base.isDiscarded()) {
                return ItemSystemProto.RankUpItemScRsp.newBuilder().setRetcode(2).setBaseUid(baseUid).build();
            }
            return ItemSystemProto.RankUpItemScRsp.newBuilder().setRetcode(3).setBaseUid(baseUid).build();
        }
        notifyDataChangedIfOnline(channel);
        return ItemSystemProto.RankUpItemScRsp.newBuilder()
                .setRetcode(0)
                .setBaseUid(baseUid)
                .setNewRank(result.newRank())
                .setConsumedUid(result.consumedUid())
                .build();
    }

    public ItemSystemProto.LockItemScRsp handleLockItem(ItemSystemProto.LockItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.LockItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .setLocked(false)
                    .build();
        }

        long uid = req.getUid();
        boolean lock = req.getLock();
        if (!itemApplicationService.setLocked(playerId, uid, lock)) {
            return ItemSystemProto.LockItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setLocked(lock)
                    .build();
        }
        notifyDataChangedIfOnline(channel);
        return ItemSystemProto.LockItemScRsp.newBuilder()
                .setRetcode(0)
                .setUid(uid)
                .setLocked(lock)
                .build();
    }

    public ItemSystemProto.DiscardItemScRsp handleDiscardItem(ItemSystemProto.DiscardItemCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .setRemainingCount(0)
                    .build();
        }

        long uid = req.getUid();
        long reqCount = req.getCount() <= 0 ? 1 : req.getCount();
        GameItemEntity item = itemApplicationService.findItem(playerId, uid);
        if (item == null || item.isDiscarded()) {
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setRemainingCount(0)
                    .build();
        }
        if (item.isLocked()) {
            long remaining = Math.max(0L, item.getCount());
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setUid(uid)
                    .setRemainingCount((int) Math.min(Integer.MAX_VALUE, remaining))
                    .build();
        }

        Long newCount = itemApplicationService.discardItem(playerId, uid, reqCount);
        if (newCount == null) {
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setRemainingCount(0)
                    .build();
        }
        notifyDataChangedIfOnline(channel);
        return ItemSystemProto.DiscardItemScRsp.newBuilder()
                .setRetcode(0)
                .setUid(uid)
                .setRemainingCount((int) Math.max(0L, Math.min(Integer.MAX_VALUE, newCount)))
                .build();
    }

    public ItemSystemProto.QueryItemSourceScRsp handleQueryItemSource(
            ItemSystemProto.QueryItemSourceCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return ItemSystemProto.QueryItemSourceScRsp.newBuilder().setRetcode(1).build();
        }
        int itemId = req.getItemId();
        ItemSystemProto.QueryItemSourceScRsp.Builder b = ItemSystemProto.QueryItemSourceScRsp.newBuilder()
                .setRetcode(0)
                .setItemId(itemId)
                .setEnterCmd("FightStartCsReq")
                .setSweepCmd("SweepStageCsReq");
        for (ItemApplicationService.ItemSource src : itemApplicationService.queryItemSources(itemId)) {
            b.addStageIds(src.stageId());
            b.addStages(ItemSystemProto.ItemSourceStage.newBuilder()
                    .setStageId(src.stageId())
                    .setStageName(src.stageName() == null ? "" : src.stageName())
                    .setSweepUnlocked(src.sweepUnlocked())
                    .setChallengeUnlocked(src.challengeUnlocked())
                    .setIsBonusToday(src.bonusToday())
                    .build());
        }
        b.setCraftCmd("CraftItemCsReq");
        for (ItemApplicationService.CraftPath path : itemApplicationService.queryCraftPaths(itemId)) {
            ItemSystemProto.CraftPath.Builder pb = ItemSystemProto.CraftPath.newBuilder()
                    .setItemId(path.itemId())
                    .setCraftCmd("CraftItemCsReq")
                    .setCanCraft(path.canCraft());
            for (ItemApplicationService.CraftStep step : path.steps()) {
                pb.addSteps(ItemSystemProto.CraftPathStep.newBuilder()
                        .setFromItemId(step.fromItemId())
                        .setFromCount(step.fromCount())
                        .setToItemId(step.toItemId())
                        .setToCount(step.toCount())
                        .build());
            }
            b.addCraftPaths(pb.build());
        }
        return b.build();
    }

    private byte[] toEmptyJsonBytes() {
        return new byte[0];
    }
}
