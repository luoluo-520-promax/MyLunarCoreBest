// 网络命令处理器实现所在包（抽卡相关）
package cn.itcast.demo.mylunarcore.net;

// 抽卡领域 Netty 服务
import cn.itcast.demo.mylunarcore.gacha.GachaNettyService;
// 抽卡 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 抽卡系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 抽卡命令入口：拉取卡池信息、抽卡与兑换保底等，委托 {@link cn.itcast.demo.mylunarcore.gacha.GachaNettyService}。
 */
@Component // 注册为 Spring Bean
public class GachaPacketHandlers {

    private final GachaNettyService gachaNettyService; // 抽卡域服务（不可变依赖）

    /**
     * 构造器注入抽卡服务。
     */
    public GachaPacketHandlers(GachaNettyService gachaNettyService) {
        this.gachaNettyService = gachaNettyService; // 保存引用
    }

    /**
     * 查询当前开放的卡池与概率摘要。
     */
    @PacketCmd(CmdIds.GET_GACHA_INFO_CS_REQ)
    public void onGetGachaInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GachaSystemProto.GetGachaInfoScRsp rsp = gachaNettyService.handleGetGachaInfo(ctx.channel()); // 无负载请求：直接从 Channel 上下文组装
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GACHA_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 执行一次或多次抽卡。
     */
    @PacketCmd(CmdIds.DO_GACHA_CS_REQ)
    public void onDoGacha(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文（卡池 id、次数等）
        GachaSystemProto.DoGachaCsReq req = GachaSystemProto.DoGachaCsReq.parseFrom(payload); // 反序列化
        GachaSystemProto.DoGachaScRsp rsp = gachaNettyService.handleDoGacha(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.DO_GACHA_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 兑换天井/保底计数奖励。
     */
    @PacketCmd(CmdIds.EXCHANGE_GACHA_CEILING_CS_REQ)
    public void onExchangeCeiling(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        GachaSystemProto.ExchangeGachaCeilingCsReq req = GachaSystemProto.ExchangeGachaCeilingCsReq.parseFrom(payload); // 反序列化
        GachaSystemProto.ExchangeGachaCeilingScRsp rsp = gachaNettyService.handleExchangeCeiling(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.EXCHANGE_GACHA_CEILING_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询玩家最近抽卡记录。
     */
    @PacketCmd(CmdIds.GET_GACHA_HISTORY_CS_REQ)
    public void onGetHistory(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文（分页参数等）
        GachaSystemProto.GetGachaHistoryCsReq req = GachaSystemProto.GetGachaHistoryCsReq.parseFrom(payload); // 反序列化
        GachaSystemProto.GetGachaHistoryScRsp rsp = gachaNettyService.handleGetHistory(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GACHA_HISTORY_SC_RSP, rsp.toByteArray())); // 写回响应
    }
}
