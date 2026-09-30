package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.common.ActivityNettyService;
import cn.itcast.demo.mylunarcore.common.VersionNettyService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import cn.itcast.demo.mylunarcore.protocol.VersionUpdateProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 版本与活动配置查询协议处理。
 */
@Component
public class UpdatePacketHandlers {

    private final VersionNettyService versionNettyService;
    private final ActivityNettyService activityNettyService;
    private final GameSessionManager sessionManager;

    public UpdatePacketHandlers(VersionNettyService versionNettyService,
                                ActivityNettyService activityNettyService,
                                GameSessionManager sessionManager) {
        this.versionNettyService = versionNettyService;
        this.activityNettyService = activityNettyService;
        this.sessionManager = sessionManager;
    }

    @PacketCmd(CmdIds.GET_VERSION_INFO_CS_REQ)
    public void onGetVersionInfo(ChannelHandlerContext ctx, GamePacket packet) {
        VersionUpdateProto.GetVersionInfoScRsp rsp = versionNettyService.handleGetVersionInfo();
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_VERSION_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_ACTIVITY_INFO_CS_REQ)
    public void onGetActivityInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        ActivitySystemProto.GetActivityInfoCsReq req = ActivitySystemProto.GetActivityInfoCsReq.parseFrom(packet.getPayload());
        boolean qa = false;
        Long uid = ctx.channel().attr(cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes.PLAYER_UID).get();
        if (uid != null) {
            GameSession session = sessionManager.getOrNull(uid);
            qa = session != null && session.isQaTester();
        }
        ActivitySystemProto.GetActivityInfoScRsp rsp = activityNettyService.handleGetActivityInfo(req.getActivityId(), qa);
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_ACTIVITY_INFO_SC_RSP, rsp.toByteArray()));
    }
}
