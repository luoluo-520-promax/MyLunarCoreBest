// 网络命令处理器实现所在包（玩家会话相关）
package cn.itcast.demo.mylunarcore.net;

// 出站响应字节缓存（减少重复序列化）
import cn.itcast.demo.mylunarcore.common.GameServerPacketCache;
// 玩家运行时内存模型
import cn.itcast.demo.mylunarcore.model.PlayerData;
// 命令号常量
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 玩家会话相关 Protobuf 定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
// 登录/心跳等业务服务
import cn.itcast.demo.mylunarcore.player.PlayerSessionService;
// 单玩家会话门面
import cn.itcast.demo.mylunarcore.player.GameSession;
// 在线会话管理器
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// Channel 属性键（玩家 uid 等）
import cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes;
// 登录后数据同步协调器
import cn.itcast.demo.mylunarcore.player.PlayerSyncCoordinator;
// 主界面开始游戏 / 返回主界面流程
import cn.itcast.demo.mylunarcore.player.GameFlowNettyService;
// 同步触发原因枚举
import cn.itcast.demo.mylunarcore.player.SyncReason;
// 版本/活动热更推送
import cn.itcast.demo.mylunarcore.common.UpdateNotifyBroadcaster;
// Netty Channel 抽象
import io.netty.channel.Channel;
// 写完成后监听器（如关闭连接）
import io.netty.channel.ChannelFutureListener;
// 处理器上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

// InetSocketAddress：解析对端 IP
import java.net.InetSocketAddress;

/**
 * 玩家会话相关分包处理：登录、心跳、登出、会话信息及登录后首次全量同步，委托 {@link cn.itcast.demo.mylunarcore.player.PlayerSessionService} 与 {@link cn.itcast.demo.mylunarcore.player.PlayerSyncCoordinator}。
 */
@Component // 注册为 Spring Bean，供 PacketCommandRegistry 扫描
public class PlayerSessionPacketHandlers {

    private final PlayerSessionService sessionService; // 会话域服务（不可变依赖）
    private final GameServerPacketCache packetCache; // 预计算响应缓存（不可变依赖）
    private final GameSessionManager sessionManager; // 在线会话注册表（不可变依赖）
    private final PlayerSyncCoordinator playerSyncCoordinator; // 推送协调器（不可变依赖）
    private final UpdateNotifyBroadcaster updateNotifyBroadcaster;
    /**
     * 主界面 ↔ 开始游戏 / 返回主界面
     */
    private final GameFlowNettyService gameFlowNettyService;

    /**
     * 构造器注入会话、缓存、会话管理、同步与主流程组件。
     */
    public PlayerSessionPacketHandlers(PlayerSessionService sessionService,
                                       GameServerPacketCache packetCache,
                                       GameSessionManager sessionManager,
                                       PlayerSyncCoordinator playerSyncCoordinator,
                                       UpdateNotifyBroadcaster updateNotifyBroadcaster,
                                       GameFlowNettyService gameFlowNettyService) {
        this.sessionService = sessionService; // 保存会话服务
        this.packetCache = packetCache; // 保存缓存
        this.sessionManager = sessionManager; // 保存会话管理器
        this.playerSyncCoordinator = playerSyncCoordinator; // 保存同步协调器
        this.updateNotifyBroadcaster = updateNotifyBroadcaster;
        this.gameFlowNettyService = gameFlowNettyService;
    }

    /**
     * 处理登录请求：解析 Protobuf，调用服务并写回响应；成功后再推送全量同步。
     */
    @PacketCmd(CmdIds.PLAYER_LOGIN_CS_REQ) // 绑定登录 cmd
    public void onPlayerLogin(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 读取请求正文
        InetSocketAddress remote = ctx.channel().remoteAddress() instanceof InetSocketAddress
                ? (InetSocketAddress) ctx.channel().remoteAddress() // 强转获得地址
                : null; // 非 IP 类型则置空
        String ip = remote == null ? "" : remote.getAddress().getHostAddress(); // 客户端 IP 字符串（可能为空）

        PlayerSessionProto.PlayerLoginCsReq req = PlayerSessionProto.PlayerLoginCsReq.parseFrom(payload); // 反序列化请求
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(req, ctx.channel(), ip); // 业务处理登录
        Channel ch = ctx.channel(); // 当前连接
        ch.writeAndFlush(new GamePacket(CmdIds.PLAYER_LOGIN_SC_RSP, rsp.toByteArray())) // 写出登录响应
                .addListener(future -> { // 监听写完成
                    if (!future.isSuccess() || rsp.getRetcode() != PlayerSessionService.RET_OK) { // 写失败或非成功返回码
                        return; // 不再做同步推送
                    }
                    Long uid = ch.attr(PlayerChannelAttributes.PLAYER_UID).get(); // 登录成功后 Channel 上应绑定 uid
                    if (uid == null) {
                        return; // 无 uid 无法定位会话
                    }
                    GameSession session = sessionManager.getOrNull(uid); // 查询在线会话（可能尚未注册）
                    PlayerData pd = session != null ? session.getPlayerData() : null; // 取出玩家内存数据
                    if (session != null && pd != null) { // 会话与数据均就绪
                        playerSyncCoordinator.pushToSession(session, pd, SyncReason.LOGIN); // 登录触发全量推送
                        updateNotifyBroadcaster.pushBootstrapNotifies(ch); // 下发版本与活动配置
                    }
                });
    }

    /**
     * 处理心跳请求：刷新会话活性并返回心跳响应。
     */
    @PacketCmd(CmdIds.PLAYER_HEART_BEAT_CS_REQ)
    public void onHeartbeat(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 心跳负载（可为空）
        PlayerSessionProto.PlayerHeartBeatCsReq req = PlayerSessionProto.PlayerHeartBeatCsReq.parseFrom(payload); // 反序列化
        PlayerSessionProto.PlayerHeartBeatScRsp rsp = sessionService.handleHeartbeat(req, ctx.channel()); // 刷新会话时间戳等
        ctx.writeAndFlush(new GamePacket(CmdIds.PLAYER_HEART_BEAT_SC_RSP, rsp.toByteArray())); // 立即写回响应
    }

    /**
     * 处理登出请求：清理会话状态并在成功后关闭连接。
     */
    @PacketCmd(CmdIds.PLAYER_LOGOUT_CS_REQ)
    public void onLogout(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 登出负载
        PlayerSessionProto.PlayerLogoutCsReq req = PlayerSessionProto.PlayerLogoutCsReq.parseFrom(payload); // 反序列化
        PlayerSessionProto.PlayerLogoutScRsp rsp = sessionService.handleLogout(req, ctx.channel()); // 执行登出逻辑
        byte[] body = rsp.getRetcode() == PlayerSessionService.RET_OK
                ? packetCache.playerLogoutOkPayload() // 成功路径可走缓存字节
                : rsp.toByteArray(); // 失败路径保留完整错误信息
        ctx.writeAndFlush(new GamePacket(CmdIds.PLAYER_LOGOUT_SC_RSP, body)) // 写出登出响应
                .addListener(ChannelFutureListener.CLOSE); // 写完关闭 TCP/KCP 通道
    }

    /**
     * 查询当前连接上的会话摘要信息。
     */
    @PacketCmd(CmdIds.GET_SESSION_INFO_CS_REQ)
    public void onGetSessionInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        PlayerSessionProto.GetSessionInfoScRsp rsp = sessionService.handleGetSessionInfo(ctx.channel()); // 从 Channel 属性等组装响应
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SESSION_INFO_SC_RSP, rsp.toByteArray())); // 写回
    }

    /**
     * 主界面「开始游戏」：HALL → 进入场景玩法（START_GAME_CS_REQ=80）。
     */
    @PacketCmd(CmdIds.START_GAME_CS_REQ)
    public void onStartGame(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        PlayerSessionProto.StartGameCsReq req =
                PlayerSessionProto.StartGameCsReq.parseFrom(packet.getPayload());
        PlayerSessionProto.StartGameScRsp rsp = gameFlowNettyService.handleStartGame(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.START_GAME_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 退出当前玩法返回主界面（RETURN_MAIN_MENU_CS_REQ=82），保持登录不断开。
     */
    @PacketCmd(CmdIds.RETURN_MAIN_MENU_CS_REQ)
    public void onReturnMainMenu(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        PlayerSessionProto.ReturnMainMenuCsReq req =
                PlayerSessionProto.ReturnMainMenuCsReq.parseFrom(packet.getPayload());
        PlayerSessionProto.ReturnMainMenuScRsp rsp =
                gameFlowNettyService.handleReturnMainMenu(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.RETURN_MAIN_MENU_SC_RSP, rsp.toByteArray()));
    }
}
