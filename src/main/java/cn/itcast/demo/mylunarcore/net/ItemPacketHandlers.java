// 网络命令处理器实现所在包（道具与背包相关）
package cn.itcast.demo.mylunarcore.net;

// 道具领域 Netty 服务
import cn.itcast.demo.mylunarcore.item.ItemNettyService;
// 背包相关 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 道具系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 道具与背包命令入口：背包、使用、装备等操作，委托 {@link cn.itcast.demo.mylunarcore.item.ItemNettyService}。
 */
@Component // 注册为 Spring Bean
public class ItemPacketHandlers {

    private final ItemNettyService itemNettyService; // 道具域服务（不可变依赖）

    /**
     * 构造器注入道具服务。
     */
    public ItemPacketHandlers(ItemNettyService itemNettyService) {
        this.itemNettyService = itemNettyService; // 保存引用
    }

    /**
     * 查询背包快照。
     */
    @PacketCmd(CmdIds.GET_BAG_CS_REQ)
    public void onGetBag(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.GetBagCsReq req = ItemSystemProto.GetBagCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.GetBagScRsp rsp = itemNettyService.handleGetBag(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_BAG_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 使用指定道具。
     */
    @PacketCmd(CmdIds.USE_ITEM_CS_REQ)
    public void onUseItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.UseItemCsReq req = ItemSystemProto.UseItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.UseItemScRsp rsp = itemNettyService.handleUseItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.USE_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 穿戴装备。
     */
    @PacketCmd(CmdIds.EQUIP_ITEM_CS_REQ)
    public void onEquipItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.EquipItemCsReq req = ItemSystemProto.EquipItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.EquipItemScRsp rsp = itemNettyService.handleEquipItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.EQUIP_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 卸下装备。
     */
    @PacketCmd(CmdIds.UNEQUIP_ITEM_CS_REQ)
    public void onUnequipItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.UnequipItemCsReq req = ItemSystemProto.UnequipItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.UnequipItemScRsp rsp = itemNettyService.handleUnequipItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.UNEQUIP_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 强化道具。
     */
    @PacketCmd(CmdIds.ENHANCE_ITEM_CS_REQ)
    public void onEnhanceItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.EnhanceItemCsReq req = ItemSystemProto.EnhanceItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.EnhanceItemScRsp rsp = itemNettyService.handleEnhanceItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ENHANCE_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 晋升道具（养成维度）。
     */
    @PacketCmd(CmdIds.PROMOTE_ITEM_CS_REQ)
    public void onPromoteItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.PromoteItemCsReq req = ItemSystemProto.PromoteItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.PromoteItemScRsp rsp = itemNettyService.handlePromoteItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.PROMOTE_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 道具升阶。
     */
    @PacketCmd(CmdIds.RANK_UP_ITEM_CS_REQ)
    public void onRankUpItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.RankUpItemCsReq req = ItemSystemProto.RankUpItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.RankUpItemScRsp rsp = itemNettyService.handleRankUpItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.RANK_UP_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 锁定或解锁背包条目。
     */
    @PacketCmd(CmdIds.LOCK_ITEM_CS_REQ)
    public void onLockItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.LockItemCsReq req = ItemSystemProto.LockItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.LockItemScRsp rsp = itemNettyService.handleLockItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.LOCK_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 丢弃道具。
     */
    @PacketCmd(CmdIds.DISCARD_ITEM_CS_REQ)
    public void onDiscardItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ItemSystemProto.DiscardItemCsReq req = ItemSystemProto.DiscardItemCsReq.parseFrom(payload); // 反序列化
        ItemSystemProto.DiscardItemScRsp rsp = itemNettyService.handleDiscardItem(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.DISCARD_ITEM_SC_RSP, rsp.toByteArray())); // 写回响应
    }
}
