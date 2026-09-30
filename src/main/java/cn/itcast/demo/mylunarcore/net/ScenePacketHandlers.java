// 网络命令处理器实现所在包（场景系统相关）
package cn.itcast.demo.mylunarcore.net;

// 场景相关 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 场景系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
// 场景领域 Netty 服务
import cn.itcast.demo.mylunarcore.scene.SceneNettyService;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 场景命令入口：进入场景、当前场景信息、NPC 交互等，委托 {@link cn.itcast.demo.mylunarcore.scene.SceneNettyService}。
 */
@Component // 注册为 Spring Bean
public class ScenePacketHandlers {

    private final SceneNettyService sceneNettyService;
    private final cn.itcast.demo.mylunarcore.social.EmoteNettyService emoteNettyService;

    public ScenePacketHandlers(SceneNettyService sceneNettyService,
                               cn.itcast.demo.mylunarcore.social.EmoteNettyService emoteNettyService) {
        this.sceneNettyService = sceneNettyService;
        this.emoteNettyService = emoteNettyService;
    }

    /**
     * 请求进入指定场景实例。
     */
    @PacketCmd(CmdIds.ENTER_SCENE_CS_REQ)
    public void onEnterScene(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.EnterSceneCsReq req = SceneSystemProto.EnterSceneCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.EnterSceneScRsp rsp = sceneNettyService.handleEnterScene(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ENTER_SCENE_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询玩家当前场景快照。
     */
    @PacketCmd(CmdIds.GET_CUR_SCENE_INFO_CS_REQ)
    public void onGetCurSceneInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.GetCurSceneInfoCsReq req = SceneSystemProto.GetCurSceneInfoCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.GetCurSceneInfoScRsp rsp = sceneNettyService.handleGetCurSceneInfo(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_CUR_SCENE_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 与 NPC 发起交互。
     */
    @PacketCmd(CmdIds.INTERACT_NPC_CS_REQ)
    public void onInteractNpc(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.InteractNpcCsReq req = SceneSystemProto.InteractNpcCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.InteractNpcScRsp rsp = sceneNettyService.handleInteractNpc(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.INTERACT_NPC_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 拾取场景内的掉落物/道具。
     */
    @PacketCmd(CmdIds.PICKUP_PROP_CS_REQ)
    public void onPickupProp(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.PickupPropCsReq req = SceneSystemProto.PickupPropCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.PickupPropScRsp rsp = sceneNettyService.handlePickupProp(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.PICKUP_PROP_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 触发剧情或机制相关的场景事件。
     */
    @PacketCmd(CmdIds.TRIGGER_SCENE_EVENT_CS_REQ)
    public void onTriggerSceneEvent(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.TriggerSceneEventCsReq req = SceneSystemProto.TriggerSceneEventCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.TriggerSceneEventScRsp rsp = sceneNettyService.handleTriggerSceneEvent(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.TRIGGER_SCENE_EVENT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    @PacketCmd(CmdIds.MOVE_CS_REQ)
    public void onMove(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.MoveCsReq req = SceneSystemProto.MoveCsReq.parseFrom(packet.getPayload());
        SceneSystemProto.MoveScRsp rsp = sceneNettyService.handleMove(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.MOVE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.MIGRATE_SCENE_CS_REQ)
    public void onMigrateScene(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.MigrateSceneCsReq req = SceneSystemProto.MigrateSceneCsReq.parseFrom(packet.getPayload());
        SceneSystemProto.MigrateSceneScRsp rsp = sceneNettyService.handleMigrateScene(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.MIGRATE_SCENE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SCENE_LOAD_COMPLETE_CS_REQ)
    public void onSceneLoadComplete(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.SceneLoadCompleteCsReq req =
                SceneSystemProto.SceneLoadCompleteCsReq.parseFrom(packet.getPayload());
        SceneSystemProto.SceneLoadCompleteScRsp rsp =
                sceneNettyService.handleSceneLoadComplete(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SCENE_LOAD_COMPLETE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SCENE_PRELOAD_CS_REQ)
    public void onScenePreload(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.ScenePreloadCsReq req =
                SceneSystemProto.ScenePreloadCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SceneSystemProto.ScenePreloadScRsp rsp = sceneNettyService.handleScenePreload(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SCENE_PRELOAD_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SCENE_EMOTE_CS_REQ)
    public void onSceneEmote(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.SceneEmoteCsReq req = SceneSystemProto.SceneEmoteCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SceneSystemProto.SceneEmoteScRsp rsp = emoteNettyService.handleSceneEmote(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SCENE_EMOTE_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 使用治疗泉等场景恢复点。
     */
    @PacketCmd(CmdIds.USE_HEALING_SPRING_CS_REQ)
    public void onUseHealingSpring(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        SceneSystemProto.UseHealingSpringCsReq req = SceneSystemProto.UseHealingSpringCsReq.parseFrom(payload); // 反序列化
        SceneSystemProto.UseHealingSpringScRsp rsp = sceneNettyService.handleUseHealingSpring(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.USE_HEALING_SPRING_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    @PacketCmd(CmdIds.REPORT_DEVICE_PERF_CS_REQ)
    public void onReportDevicePerf(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.ReportDevicePerfCsReq req = SceneSystemProto.ReportDevicePerfCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SceneSystemProto.ReportDevicePerfScRsp rsp = sceneNettyService.handleReportDevicePerf(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.REPORT_DEVICE_PERF_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_WORLD_TIME_CS_REQ)
    public void onGetWorldTime(ChannelHandlerContext ctx, GamePacket packet) {
        SceneSystemProto.GetWorldTimeScRsp rsp = sceneNettyService.handleGetWorldTime(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_WORLD_TIME_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.COMFORT_DEATH_ECHO_CS_REQ)
    public void onComfortDeathEcho(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SceneSystemProto.ComfortDeathEchoCsReq req = SceneSystemProto.ComfortDeathEchoCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SceneSystemProto.ComfortDeathEchoScRsp rsp = sceneNettyService.handleComfortDeathEcho(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.COMFORT_DEATH_ECHO_SC_RSP, rsp.toByteArray()));
    }
}
