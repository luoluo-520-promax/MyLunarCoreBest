// 道具 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.item;

// 玩家背包道具持久化实体
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
// 背包道具 CRUD 与分页查询仓储
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
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
// Channel 自定义属性键
import io.netty.util.AttributeKey;
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

    // Channel 上绑定玩家 uid 的属性键
    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid"); // 定义 Channel 属性键名 playerUid

    // 背包道具持久化仓储
    private final ItemRepository itemRepository; // 道具 CRUD 与分页查询仓储

    /**
     * 构造器注入道具仓储依赖。
     */
    public ItemNettyService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository; // 保存道具仓储引用
    }

    /**
     * 从 Channel 读取当前玩家 ID；未登录返回 0。
     */
    private int getPlayerId(Channel channel) { // 解析 Channel 绑定的玩家 ID
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return 0; // 未登录时返回 0（与 Challenge 侧 null 语义不同，此处用 0 表示非法）
        }
        return (int) (uid.longValue() & 0xffffffffL); // uid 低 32 位映射为 playerId
    }

    /**
     * 分页拉取玩家背包道具列表，支持按道具类型过滤。
     */
    public ItemSystemProto.GetBagScRsp handleGetBag(ItemSystemProto.GetBagCsReq req, Channel channel) { // 处理查询背包请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
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

        long total = itemRepository.countBagItems(playerId, typeFilter); // 统计符合条件的背包总条数
        List<ItemSystemProto.BagItem> items = itemRepository.listBagItems(playerId, typeFilter, page, pageSize); // 分页查询背包道具

        return ItemSystemProto.GetBagScRsp.newBuilder() // 组装查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setTotalCount((int) Math.max(0L, total)) // 写入总条数（保证非负）
                .addAllItems(items) // 写入当前页道具列表
                .build(); // 完成响应构建
    }

    /**
     * 使用道具：扣减数量/标记丢弃，计算经验效果并推送背包变更通知。
     */
    public ItemSystemProto.UseItemScRsp handleUseItem(ItemSystemProto.UseItemCsReq req, Channel channel) { // 处理使用道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.UseItemScRsp.newBuilder() // 组装使用失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setUid(req.getUid()) // 回写请求的道具 uid
                    .setUsedCount(0) // 实际使用数量为 0
                    .build(); // 完成响应构建
        }

        long uid = req.getUid(); // 请求使用的道具实例 uid
        if (uid <= 0) { // 道具 uid 非法
            return ItemSystemProto.UseItemScRsp.newBuilder() // 组装参数非法响应
                    .setRetcode(5) // retcode=5：参数非法
                    .setUid(0) // uid 置 0
                    .setUsedCount(0) // 使用数量为 0
                    .build(); // 完成响应构建
        }

        GameItemEntity item = itemRepository.findItemByUid(playerId, uid); // 按 uid 查询道具实体
        if (item == null || item.isDiscarded()) { // 道具不存在或已丢弃
            return ItemSystemProto.UseItemScRsp.newBuilder() // 组装道具不存在响应
                    .setRetcode(2) // retcode=2：道具不存在
                    .setUid(uid) // 回写请求的 uid
                    .setUsedCount(0) // 使用数量为 0
                    .build(); // 完成响应构建
        }
        if (item.isLocked()) { // 道具已锁定，禁止变更
            return ItemSystemProto.UseItemScRsp.newBuilder() // 组装锁定禁止响应
                    .setRetcode(3) // retcode=3：道具已锁定
                    .setUid(uid) // 回写请求的 uid
                    .setUsedCount(0) // 使用数量为 0
                    .build(); // 完成响应构建
        }

        int type = item.getType(); // 道具类型（3=材料可堆叠消耗）
        long reqCount = req.getCount(); // 客户端请求使用数量
        if (reqCount <= 0) { // 未指定数量时默认 1
            reqCount = 1; // 默认使用 1 个
        }

        long usedCount; // 实际扣减数量
        if (type == 3) { // 材料类可批量消耗
            usedCount = Math.min(reqCount, item.getCount()); // 不超过当前持有量
        } else { // 非材料类单次使用
            usedCount = 1; // 固定消耗 1 个
        }
        if (usedCount <= 0) { // 无可消耗数量
            return ItemSystemProto.UseItemScRsp.newBuilder() // 组装数量不足响应
                    .setRetcode(4) // retcode=4：数量不足
                    .setUid(uid) // 回写请求的 uid
                    .setUsedCount(0) // 使用数量为 0
                    .build(); // 完成响应构建
        }

        long newCount = item.getCount() - usedCount; // 扣减后剩余数量
        boolean discard = newCount <= 0 || type != 3; // 非材料类或数量归零则标记丢弃

        // 简化效果：item.exp 字段作为每单位授予经验
        long totalExp = item.getExp() * usedCount; // 累计获得经验
        int playerExp = (int) Math.max(0, Math.min(Integer.MAX_VALUE, totalExp)); // 玩家经验（截断至 int 上限）
        int avatarExp = req.getTargetAvatarId() > 0 ? playerExp : 0; // 指定目标角色时同步角色经验

        itemRepository.updateItemCountAndDiscard(playerId, uid, Math.max(0, newCount), discard); // 持久化数量与丢弃状态

        ItemSystemProto.UseItemEffects effects = ItemSystemProto.UseItemEffects.newBuilder() // 组装使用效果
                .setPlayerExp(playerExp) // 写入玩家经验奖励
                .setAvatarExp(avatarExp) // 写入目标角色经验奖励
                .build(); // 完成效果构建

        // 主动推送背包变更通知（尽力而为，跨会话不持久化）
        try { // 推送失败不影响主流程
            ItemSystemProto.BagItem oldItem = itemRepository.toBagItem(item); // 变更前道具快照
            ItemSystemProto.BagItem updatedItem; // 变更后用于通知的道具快照
            if (discard) { // 已丢弃则查库取最新或回退旧快照
                GameItemEntity after = itemRepository.findItemByUid(playerId, uid); // 重新查询丢弃后状态
                updatedItem = after == null ? oldItem : itemRepository.toBagItem(after); // 已删则用旧快照
            } else { // 仅数量变更
                updatedItem = oldItem.toBuilder().setCount((int) newCount).build(); // 内存构造新数量快照
            }
            ItemSystemProto.ItemChangeScNotify notify = ItemSystemProto.ItemChangeScNotify.newBuilder() // 组装背包变更通知
                    .setChangeType(discard ? 3 : 2) // changeType：2=更新，3=删除
                    .addItems(discard ? oldItem : updatedItem) // 删除推送旧项，更新推送新项
                    .build(); // 完成通知构建
            channel.writeAndFlush(new GamePacket(cn.itcast.demo.mylunarcore.net.CmdIds.ITEM_CHANGE_SC_NOTIFY, notify.toByteArray())); // 主动推送道具变更通知给客户端
        } catch (Exception e) { // 推送异常仅记录 debug 日志
            log.debug("send ItemChangeScNotify failed", e); // 记录推送失败便于排查
        }

        return ItemSystemProto.UseItemScRsp.newBuilder() // 组装使用成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setUsedCount((int) Math.min(Integer.MAX_VALUE, Math.max(0L, usedCount))) // 回写实际使用数量
                .setEffects(effects) // 写入使用效果
                .build(); // 完成响应构建
    }

    /**
     * 将光锥/遗器类道具装备到指定角色。
     */
    public ItemSystemProto.EquipItemScRsp handleEquipItem(ItemSystemProto.EquipItemCsReq req, Channel channel) { // 处理装备道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.EquipItemScRsp.newBuilder().setRetcode(1).setUid(req.getUid()).build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 待装备道具实例 uid
        int avatarId = (int) req.getAvatarId(); // 目标角色 ID
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid); // 按 uid 查询道具
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

        itemRepository.setEquipAvatarId(playerId, uid, avatarId); // 持久化装备角色 ID
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
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.UnequipItemScRsp.newBuilder().setRetcode(1).setUid(req.getUid()).build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 待卸下道具实例 uid
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid); // 按 uid 查询道具
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

        int avatarId = item.getEquipAvatarId() == null ? 0 : item.getEquipAvatarId(); // 卸下前所装备角色 ID
        itemRepository.setEquipAvatarId(playerId, uid, null); // 清空装备角色绑定

        return ItemSystemProto.UnequipItemScRsp.newBuilder() // 组装卸下成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setAvatarId(avatarId) // 回写原装备角色 ID
                .setAvatarAttributes(ByteString.copyFrom(toEmptyJsonBytes())) // 写入空角色属性 JSON
                .build(); // 完成响应构建
    }

    /**
     * 消耗材料道具强化目标道具，累加经验并提升等级。
     */
    public ItemSystemProto.EnhanceItemScRsp handleEnhanceItem(ItemSystemProto.EnhanceItemCsReq req, Channel channel) { // 处理强化道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.EnhanceItemScRsp.newBuilder().setRetcode(1).setTargetUid(req.getTargetUid()).build(); // retcode=1：未登录
        }

        long targetUid = req.getTargetUid(); // 强化目标道具 uid
        List<Long> materialUids = req.getMaterialUidsList(); // 材料道具 uid 列表
        if (targetUid <= 0 || materialUids == null || materialUids.isEmpty()) { // 目标或材料参数非法
            return ItemSystemProto.EnhanceItemScRsp.newBuilder() // 组装参数非法响应
                    .setRetcode(5) // retcode=5：参数非法
                    .setTargetUid(targetUid) // 回写目标 uid
                    .setNewLevel(0) // 新等级为 0
                    .setNewExp(0) // 新经验为 0
                    .addAllConsumedUids(Collections.<Long>emptyList()) // 无消耗材料
                    .build(); // 完成响应构建
        }

        GameItemEntity target = itemRepository.findItemByUid(playerId, targetUid); // 查询强化目标
        if (target == null || target.isDiscarded()) { // 目标不存在或已丢弃
            return ItemSystemProto.EnhanceItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setTargetUid(targetUid)
                    .build(); // retcode=2：目标不存在
        }
        if (target.isLocked()) { // 目标已锁定，禁止强化
            return ItemSystemProto.EnhanceItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setTargetUid(targetUid)
                    .build(); // retcode=3：目标已锁定
        }

        long sumMaterialExp = 0; // 材料贡献的总经验
        List<Long> consumed = new ArrayList<>(); // 实际消耗的材料 uid 列表
        for (Long mu : materialUids) { // 逐件处理材料道具
            if (mu == null || mu <= 0) { // 跳过非法 uid
                continue; // 忽略无效材料项
            }
            GameItemEntity m = itemRepository.findItemByUid(playerId, mu); // 查询材料道具
            if (m == null || m.isDiscarded()) { // 材料不存在或已丢弃
                continue; // 跳过无效材料
            }
            if (m.isLocked()) { // 材料已锁定不可消耗
                continue; // 跳过锁定材料
            }
            long c = m.getCount(); // 材料当前堆叠数量
            sumMaterialExp += m.getExp() * c; // 累加材料经验贡献
            consumed.add(mu); // 记录已消耗材料 uid
            // 材料整件消耗并标记丢弃
            itemRepository.markDiscarded(playerId, mu); // 持久化材料丢弃状态
        }

        int newLevel = target.getLevel(); // 强化后等级（默认不变）
        long newExpTotal = target.getExp() + sumMaterialExp; // 累加后的总经验
        if (sumMaterialExp > 0) { // 有材料贡献时才升档
            newLevel = target.getLevel() + (int) Math.max(1L, sumMaterialExp / 1000L); // 每 1000 经验至少升 1 级（演示规则）
        }
        long newExp = newExpTotal; // 强化后经验值

        itemRepository.applyEnhance(playerId, targetUid, newLevel, newExp); // 持久化等级与经验

        return ItemSystemProto.EnhanceItemScRsp.newBuilder() // 组装强化成功响应
                .setRetcode(0) // retcode=0：成功
                .setTargetUid(targetUid) // 回写目标 uid
                .setNewLevel(newLevel) // 回写新等级
                .setNewExp((int) Math.max(0L, Math.min(Integer.MAX_VALUE, newExp))) // 回写新经验（截断至 int）
                .addAllConsumedUids(consumed) // 回写已消耗材料 uid 列表
                .build(); // 完成响应构建
    }

    /**
     * 突破道具：promotion 字段 +1。
     */
    public ItemSystemProto.PromoteItemScRsp handlePromoteItem(ItemSystemProto.PromoteItemCsReq req, Channel channel) { // 处理突破道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.PromoteItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .build(); // retcode=1：未登录
        }
        long uid = req.getUid(); // 待突破道具 uid
        GameItemEntity item = itemRepository.findItemByUid(playerId, uid); // 按 uid 查询道具
        if (item == null || item.isDiscarded()) { // 道具不存在或已丢弃
            return ItemSystemProto.PromoteItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .build(); // retcode=2：道具不存在
        }
        if (item.isLocked()) { // 道具已锁定，禁止变更
            return ItemSystemProto.PromoteItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setUid(uid)
                    .build(); // retcode=3：道具已锁定
        }
        int newPromotion = item.getPromotion() + 1; // 突破阶数 +1
        itemRepository.updatePromotion(playerId, uid, newPromotion); // 持久化新突破阶数

        return ItemSystemProto.PromoteItemScRsp.newBuilder() // 组装突破成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setNewPromotion(newPromotion) // 回写新突破阶数
                .build(); // 完成响应构建
    }

    /**
     * 叠影：基底道具 rank +1，材料道具整件消耗。
     */
    public ItemSystemProto.RankUpItemScRsp handleRankUpItem(ItemSystemProto.RankUpItemCsReq req, Channel channel) { // 处理叠影道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.RankUpItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setBaseUid(req.getBaseUid())
                    .build(); // retcode=1：未登录
        }

        long baseUid = req.getBaseUid(); // 基底道具 uid
        long materialUid = req.getMaterialUid(); // 材料道具 uid

        GameItemEntity base = itemRepository.findItemByUid(playerId, baseUid); // 查询基底道具
        GameItemEntity material = itemRepository.findItemByUid(playerId, materialUid); // 查询材料道具
        if (base == null || base.isDiscarded() || material == null || material.isDiscarded()) { // 基底或材料不存在
            return ItemSystemProto.RankUpItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setBaseUid(baseUid)
                    .build(); // retcode=2：道具不存在
        }
        if (base.isLocked() || material.isLocked()) { // 基底或材料已锁定
            return ItemSystemProto.RankUpItemScRsp.newBuilder()
                    .setRetcode(3)
                    .setBaseUid(baseUid)
                    .build(); // retcode=3：道具已锁定
        }

        int newRank = base.getRank() + 1; // 叠影后新 rank
        itemRepository.updateRank(playerId, baseUid, newRank); // 持久化基底新 rank
        itemRepository.markDiscarded(playerId, materialUid); // 材料整件消耗并丢弃

        return ItemSystemProto.RankUpItemScRsp.newBuilder() // 组装叠影成功响应
                .setRetcode(0) // retcode=0：成功
                .setBaseUid(baseUid) // 回写基底 uid
                .setNewRank(newRank) // 回写新 rank
                .setConsumedUid(materialUid) // 回写已消耗材料 uid
                .build(); // 完成响应构建
    }

    /**
     * 锁定或解锁道具，防止误操作变更。
     */
    public ItemSystemProto.LockItemScRsp handleLockItem(ItemSystemProto.LockItemCsReq req, Channel channel) { // 处理锁定/解锁道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.LockItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .setLocked(false)
                    .build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 目标道具 uid
        boolean lock = req.getLock(); // true=锁定，false=解锁
        int updated = itemRepository.setLocked(playerId, uid, lock); // 持久化锁定状态，返回影响行数
        if (updated <= 0) { // 未更新到任何行（道具不存在）
            return ItemSystemProto.LockItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setLocked(lock)
                    .build(); // retcode=2：道具不存在
        }
        return ItemSystemProto.LockItemScRsp.newBuilder() // 组装锁定成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setLocked(lock) // 回写最新锁定状态
                .build(); // 完成响应构建
    }

    /**
     * 丢弃道具：扣减数量，归零时标记 discarded。
     */
    public ItemSystemProto.DiscardItemScRsp handleDiscardItem(ItemSystemProto.DiscardItemCsReq req, Channel channel) { // 处理丢弃道具请求
        int playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId <= 0) { // 未登录或 playerId 非法
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(1)
                    .setUid(req.getUid())
                    .setRemainingCount(0)
                    .build(); // retcode=1：未登录
        }

        long uid = req.getUid(); // 待丢弃道具 uid
        long reqCount = req.getCount(); // 请求丢弃数量
        if (reqCount <= 0) { // 未指定数量时默认 1
            reqCount = 1; // 默认丢弃 1 个
        }

        GameItemEntity item = itemRepository.findItemByUid(playerId, uid); // 按 uid 查询道具
        if (item == null || item.isDiscarded()) { // 道具不存在或已丢弃
            return ItemSystemProto.DiscardItemScRsp.newBuilder()
                    .setRetcode(2)
                    .setUid(uid)
                    .setRemainingCount(0)
                    .build(); // retcode=2：道具不存在
        }

        if (item.isLocked()) { // 道具已锁定，禁止变更
            long remaining = Math.max(0L, item.getCount()); // 锁定状态下剩余数量不变
            return ItemSystemProto.DiscardItemScRsp.newBuilder() // 组装锁定禁止响应
                    .setRetcode(3) // retcode=3：道具已锁定
                    .setUid(uid) // 回写道具 uid
                    .setRemainingCount((int) Math.min(Integer.MAX_VALUE, remaining)) // 回写剩余数量
                    .build(); // 完成响应构建
        }

        long newCount = item.getCount() - reqCount; // 扣减后剩余数量
        if (newCount < 0) { // 丢弃量超过持有量
            newCount = 0; // 剩余归零
        }
        boolean discard = newCount == 0; // 数量归零则标记 discarded

        itemRepository.updateItemCountAndDiscard(playerId, uid, newCount, discard); // 持久化数量与丢弃状态

        return ItemSystemProto.DiscardItemScRsp.newBuilder() // 组装丢弃成功响应
                .setRetcode(0) // retcode=0：成功
                .setUid(uid) // 回写道具 uid
                .setRemainingCount((int) Math.max(0L, Math.min(Integer.MAX_VALUE, newCount))) // 回写剩余数量
                .build(); // 完成响应构建
    }

    /**
     * 返回空 JSON 字节数组，供 avatarAttributes 占位（演示实现）。
     */
    private byte[] toEmptyJsonBytes() { // 生成空 JSON 载荷
        return new byte[0]; // 返回零长度字节数组
    }
}
