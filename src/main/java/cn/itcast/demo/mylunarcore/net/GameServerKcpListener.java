package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.net.kcp.KcpBandwidthProbe;
import cn.itcast.demo.mylunarcore.player.ConnectionLifecycleService;
import io.jpower.kcp.netty.Ukcp;
import io.jpower.kcp.netty.UkcpChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 把 KCP 生命周期事件接到命令分发：收包仍在 EventLoop，业务在 {@link DefaultEventExecutorGroup} 执行，与 TCP 路径一致。
 */
@Component
public class GameServerKcpListener implements KcpListener {

    static final AttributeKey<Ukcp> UKCP_KEY = AttributeKey.valueOf("kcpUkcp");

    private final GamePacketDispatcher packetDispatcher;
    private final DefaultEventExecutorGroup gameBusinessExecutorGroup;
    private final ConnectionLifecycleService connectionLifecycleService;
    private final ObjectProvider<KcpBandwidthProbe> bandwidthProbeProvider;

    public GameServerKcpListener(GamePacketDispatcher packetDispatcher,
                                 DefaultEventExecutorGroup gameBusinessExecutorGroup,
                                 ConnectionLifecycleService connectionLifecycleService,
                                 ObjectProvider<KcpBandwidthProbe> bandwidthProbeProvider) {
        this.packetDispatcher = packetDispatcher;
        this.gameBusinessExecutorGroup = gameBusinessExecutorGroup;
        this.connectionLifecycleService = connectionLifecycleService;
        this.bandwidthProbeProvider = bandwidthProbeProvider;
    }

    /** 单测便捷：无带宽探测。 */
    public GameServerKcpListener(GamePacketDispatcher packetDispatcher,
                                 DefaultEventExecutorGroup gameBusinessExecutorGroup,
                                 ConnectionLifecycleService connectionLifecycleService) {
        this(packetDispatcher, gameBusinessExecutorGroup, connectionLifecycleService, null);
    }

    @Override
    public void onConnected(UkcpChannel channel, Ukcp ukcp) {
        channel.attr(UKCP_KEY).set(ukcp);
    }

    @Override
    public void onReceive(ChannelHandlerContext ctx, Ukcp ukcp, GamePacket packet) {
        if (ctx != null && packet != null && bandwidthProbeProvider != null) {
            KcpBandwidthProbe probe = bandwidthProbeProvider.getIfAvailable();
            if (probe != null) {
                byte[] body = packet.getPayload();
                probe.recordIngress(ctx.channel(), body == null ? 0 : body.length + 8);
            }
        }
        gameBusinessExecutorGroup.execute(() -> packetDispatcher.dispatch(ctx, packet));
    }

    @Override
    public void onDisconnected(UkcpChannel channel, Ukcp ukcp) {
        connectionLifecycleService.onDisconnect(channel);
        channel.attr(UKCP_KEY).remove();
    }
}
