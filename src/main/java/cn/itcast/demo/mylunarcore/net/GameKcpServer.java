// UDP/KCP 游戏服启动与关闭
package cn.itcast.demo.mylunarcore.net;

// 全局配置（端口、KCP 参数等）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// KCP 生命周期回调实现（投递业务线程）
import cn.itcast.demo.mylunarcore.net.GameServerKcpListener;
// Pipeline 末尾桥接到 KcpListener
import cn.itcast.demo.mylunarcore.net.KcpListenerBridgeHandler;
// KCP 引导常用 ChannelOption 批量设置
import io.jpower.kcp.netty.ChannelOptionHelper;
// KCP 子通道类型
import io.jpower.kcp.netty.UkcpChannel;
// Netty Channel 选项
import io.netty.channel.ChannelOption;
// UkcpChannel 专有选项（如 MTU）
import io.jpower.kcp.netty.UkcpChannelOption;
// UDP KCP 服务端 Channel 类型
import io.jpower.kcp.netty.UkcpServerChannel;
// KCP 服务端 Bootstrap
import io.netty.bootstrap.UkcpServerBootstrap;
// bind/close 异步结果
import io.netty.channel.ChannelFuture;
// 初始化每条 UkcpChannel 的 pipeline
import io.netty.channel.ChannelInitializer;
// Netty 事件循环线程组
import io.netty.channel.EventLoopGroup;
// Java NIO 版 EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup;
// Bean 就绪回调
import jakarta.annotation.PostConstruct;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger
import org.slf4j.Logger;
// 条件装配：开关 KCP
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// Spring 组件注解
import org.springframework.stereotype.Component;
/**
 * UDP KCP 游戏服：使用与 TCP 相同的 Lunar 帧编解码，KCP 参数参考 LunarCore 风格。
 */
@Component // 注册为 Spring Bean
@ConditionalOnProperty(prefix = "lunarcore", name = "game-kcp-enabled", havingValue = "true", matchIfMissing = true) // 默认启用 KCP
public class GameKcpServer {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GameKcpServer.class); // 本类日志

    private final LunarCoreProperties properties; // 注入：端口与 KCP 配置
    private final GameServerKcpListener kcpListener; // 注入：收包回调实现
    private final ConnectionPacketRateLimiterHandler packetRateLimiterHandler;
    private final NettyBackpressureHandler backpressureHandler;
    private final NetTraceContextHandler traceContextHandler;

    private EventLoopGroup group; // KCP 与 UDP 共用的 EventLoop
    private ChannelFuture bindFuture; // bind 结果句柄

    /**
     * 构造器注入配置与 KCP 监听器。
     */
    public GameKcpServer(LunarCoreProperties properties,
                         GameServerKcpListener kcpListener,
                         ConnectionPacketRateLimiterHandler packetRateLimiterHandler,
                         NettyBackpressureHandler backpressureHandler,
                         NetTraceContextHandler traceContextHandler) {
        this.properties = properties;
        this.kcpListener = kcpListener;
        this.packetRateLimiterHandler = packetRateLimiterHandler;
        this.backpressureHandler = backpressureHandler;
        this.traceContextHandler = traceContextHandler;
    }

    /**
     * 启动 UDP/KCP：装配与 TCP 一致的帧编解码与幂等，末尾桥接 Listener。
     */
    @PostConstruct // 容器启动后绑定端口
    public void start() throws InterruptedException {
        int port = properties.getNettyPort(); // 与 TCP 默认同端口（配置决定）
        int interval = properties.getKcp().getInterval(); // KCP 刷新间隔 ms
        int mtu = properties.getKcp().getMtu(); // 单包最大传输单元

        group = new NioEventLoopGroup(); // UDP/KCP 共用线程组
        UkcpServerBootstrap b = new UkcpServerBootstrap(); // KCP 服务端引导
        b.group(group) // 指定事件循环
                .channel(UkcpServerChannel.class) // 服务端 channel 类型
                .childHandler(new ChannelInitializer<UkcpChannel>() { // 每个 Ukcp 连接初始化 pipeline
                    @Override
                    protected void initChannel(UkcpChannel ch) { // 装配 handler 链
                        GamePipelineConfigurer.configureCommonHandlers(
                                ch.pipeline(), backpressureHandler, packetRateLimiterHandler, traceContextHandler);
                        if (properties.getProtocolHmac().isEnabled()) {
                            ch.pipeline().addFirst("protocol-hmac", new ProtocolHmacCodec(properties));
                        }
                        if (properties.getKcpCrypto().isEnabled()) {
                            ch.pipeline().addFirst("kcp-crypto", new KcpSessionCryptoCodec(properties));
                        }
                        ch.pipeline().addLast(new KcpListenerBridgeHandler(kcpListener));
                    }
                });
        ChannelOptionHelper.nodelay(b, true, interval, 2, true) // 默认 nodelay；运行时由 AdaptiveKcp 按 RTT 调 interval
                .childOption(UkcpChannelOption.UKCP_MTU, mtu)
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, NettyChannelOptions.WRITE_BUFFER_WATER_MARK);

        bindFuture = b.bind(port).sync(); // 同步等待绑定完成
        var retransmit = properties.getKcp().getRetransmit();
        log.info("GameKcpServer started (UDP/KCP) on port={}, interval={}, mtu={}, algo={}, fastAck={}, appCrypto={}",
                port, interval, mtu, retransmit.getAlgo(), retransmit.isFastAck(), properties.getKcpCrypto().isEnabled());
        if (!properties.getKcpCrypto().isEnabled()) {
            log.info("UDP/KCP 未启用应用层加密；生产请开启 lunarcore.kcp-crypto.enabled 或改用 TCP+TLS。");
        }
    }

    /**
     * 关闭 KCP 监听并退出事件循环。
     */
    public void stop() {
        try {
            if (bindFuture != null) { // 已启动才关闭 channel
                bindFuture.channel().close().syncUninterruptibly(); // 关闭监听
            }
        } catch (Exception e) {
            log.warn("Error closing KCP channel", e); // 异常仅记录
        }
        try {
            if (group != null) { // 释放线程组
                group.shutdownGracefully(); // 优雅停机
            }
        } catch (Exception e) {
            log.warn("Error shutting down KCP event loop", e);
        }
        log.info("GameKcpServer stopped"); // 停止完成
    }
}
