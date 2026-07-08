// 网络命令处理器实现所在包（挑战玩法相关）
package cn.itcast.demo.mylunarcore.net;

// 挑战领域 Netty 服务
import cn.itcast.demo.mylunarcore.challenge.ChallengeNettyService;
// 挑战相关 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 挑战系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.ChallengeSystemProto;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 挑战玩法命令入口：开始挑战、查询信息、上报结果等，委托 {@link cn.itcast.demo.mylunarcore.challenge.ChallengeNettyService}。
 */
@Component // 注册为 Spring Bean
public class ChallengePacketHandlers {

    private final ChallengeNettyService challengeNettyService; // 挑战域服务（不可变依赖）

    /**
     * 构造器注入挑战服务。
     */
    public ChallengePacketHandlers(ChallengeNettyService challengeNettyService) {
        this.challengeNettyService = challengeNettyService; // 保存引用
    }

    /**
     * 请求开始指定挑战关卡。
     */
    @PacketCmd(CmdIds.START_CHALLENGE_CS_REQ)
    public void onStartChallenge(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.StartChallengeCsReq req = ChallengeSystemProto.StartChallengeCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.StartChallengeScRsp rsp = challengeNettyService.handleStartChallenge(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.START_CHALLENGE_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询挑战进度、次数等聚合信息。
     */
    @PacketCmd(CmdIds.GET_CHALLENGE_INFO_CS_REQ)
    public void onGetChallengeInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.GetChallengeInfoCsReq req = ChallengeSystemProto.GetChallengeInfoCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.GetChallengeInfoScRsp rsp = challengeNettyService.handleGetChallengeInfo(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_CHALLENGE_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 上报一局挑战的战斗结果。
     */
    @PacketCmd(CmdIds.REPORT_CHALLENGE_RESULT_CS_REQ)
    public void onReportResult(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.ReportChallengeResultCsReq req = ChallengeSystemProto.ReportChallengeResultCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.ReportChallengeResultScRsp rsp = challengeNettyService.handleReportResult(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.REPORT_CHALLENGE_RESULT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 领取挑战组的星级/进度奖励。
     */
    @PacketCmd(CmdIds.CLAIM_CHALLENGE_GROUP_REWARD_CS_REQ)
    public void onClaimGroupReward(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.ClaimChallengeGroupRewardCsReq req = ChallengeSystemProto.ClaimChallengeGroupRewardCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.ClaimChallengeGroupRewardScRsp rsp = challengeNettyService.handleClaimGroupReward(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_CHALLENGE_GROUP_REWARD_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 分页或滚动查询挑战历史记录。
     */
    @PacketCmd(CmdIds.GET_CHALLENGE_HISTORY_CS_REQ)
    public void onGetHistory(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.GetChallengeHistoryCsReq req = ChallengeSystemProto.GetChallengeHistoryCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.GetChallengeHistoryScRsp rsp = challengeNettyService.handleGetHistory(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_CHALLENGE_HISTORY_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询挑战组奖励领取状态。
     */
    @PacketCmd(CmdIds.GET_CHALLENGE_GROUP_REWARD_CS_REQ)
    public void onGetGroupRewardState(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        ChallengeSystemProto.GetChallengeGroupRewardCsReq req = ChallengeSystemProto.GetChallengeGroupRewardCsReq.parseFrom(payload); // 反序列化
        ChallengeSystemProto.GetChallengeGroupRewardScRsp rsp = challengeNettyService.handleGetGroupRewardState(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_CHALLENGE_GROUP_REWARD_SC_RSP, rsp.toByteArray())); // 写回响应
    }
}
