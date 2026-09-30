// TCP 游戏服 Netty 启动与关闭
package cn.itcast.demo.mylunarcore.net;

// 全局配置（端口、TLS、线程相关）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// TCP 管线末端：把包交给分发器
import cn.itcast.demo.mylunarcore.net.GameServerChannelHandler;
// 根据 keystore 构建 Netty TLS 上下文
import cn.itcast.demo.mylunarcore.net.GameNettyTlsSupport;
// Netty 服务端启动引导
import io.netty.bootstrap.ServerBootstrap;
// 异步 bind/close 操作句柄
import io.netty.channel.ChannelFuture;
// 新连接 Pipeline 初始化模板
import io.netty.channel.ChannelInitializer;
// Socket 子通道可配参数（如 TCP_NODELAY）
import io.netty.channel.ChannelOption;
// Reactor 线程组抽象
import io.netty.channel.EventLoopGroup;
// Java NIO 多路复用事件循环
import io.netty.channel.nio.NioEventLoopGroup;
// 已接受的 TCP 连接通道
import io.netty.channel.socket.SocketChannel;
// NIO 版服务端 ServerSocketChannel 实现
import io.netty.channel.socket.nio.NioServerSocketChannel;
// Netty TLS 加密上下文
import io.netty.handler.ssl.SslContext;
// 独立业务线程池（与 I/O 线程隔离）
import io.netty.util.concurrent.DefaultEventExecutorGroup;
// Bean 初始化完成回调
import jakarta.annotation.PostConstruct;
// Spring BeanFactory 作用域常量
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
// 声明 Bean 作用域
import org.springframework.context.annotation.Scope;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// 仅当配置项满足条件时装配 Bean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// 声明 Spring 组件
import org.springframework.stereotype.Component;

// Duration：用于幂等缓存 TTL

/**
 * TCP 游戏服 Netty 引导：绑定端口、装配帧编解码与 {@link cn.itcast.demo.mylunarcore.net.GameServerChannelHandler}，可选 TLS。
 */
@Component // 注册为 Spring Bean，由容器管理生命周期
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON) // 全进程单例服务
@ConditionalOnProperty(prefix = "lunarcore", name = "game-tcp-enabled", havingValue = "true", matchIfMissing = true) // 默认启用 TCP 游戏服
public class GameNettyServer {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GameNettyServer.class); // 本类日志

    private final LunarCoreProperties properties; // 注入：端口、TLS 开关等
    private final GameServerChannelHandler gameServerChannelHandler; // 注入：业务处理链尾节点
    private final DefaultEventExecutorGroup gameBusinessExecutorGroup; // 注入：业务线程池
    private final ConnectionPacketRateLimiterHandler packetRateLimiterHandler;
    private final NettyBackpressureHandler backpressureHandler;
    private final NetTraceContextHandler traceContextHandler;

    private EventLoopGroup bossGroup; // 接受新连接的线程组
    private EventLoopGroup workerGroup; // 处理已连接 Channel I/O 的线程组
    private ChannelFuture bindFuture; // bind 操作的结果，用于关闭
    /** 非 null 时 TCP 链路首节点为 TLS（与 HTTPS 同族协议） */
    private SslContext sslContext;

    /**
     * 构造器注入：由 Spring 传入配置、末端 Handler 与业务线程池。
     */
    public GameNettyServer(LunarCoreProperties properties,
                           GameServerChannelHandler gameServerChannelHandler,
                           DefaultEventExecutorGroup gameBusinessExecutorGroup,
                           ConnectionPacketRateLimiterHandler packetRateLimiterHandler,
                           NettyBackpressureHandler backpressureHandler,
                           NetTraceContextHandler traceContextHandler) {
        this.properties = properties;
        this.gameServerChannelHandler = gameServerChannelHandler;
        this.gameBusinessExecutorGroup = gameBusinessExecutorGroup;
        this.packetRateLimiterHandler = packetRateLimiterHandler;
        this.backpressureHandler = backpressureHandler;
        this.traceContextHandler = traceContextHandler;
    }

    /**
     * 启动 TCP 监听：装配 SSL（可选）、帧编解码、幂等与业务线程。
     */
    @PostConstruct // Spring 容器启动后自动 bind 端口
    public void start() throws Exception {
        int port = properties.getNettyPort(); // 绑定端口（与配置一致）

        if (properties.getTls().isGameTcpEnabled()) { // 配置允许则为游戏 TCP 启用 TLS
            sslContext = GameNettyTlsSupport.serverSslContext(properties.getTls()); // 构建服务端 SslContext
            log.info("Game TCP TLS enabled: asymmetric handshake + symmetric record encryption (TLS), port={}", port);
        }

        bossGroup = new NioEventLoopGroup(1); // 通常 1 个 boss 线程即可
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap(); // TCP 服务端装配器
        bootstrap.group(bossGroup, workerGroup) // boss accept + worker I/O
                .channel(NioServerSocketChannel.class) // 经典 Java NIO 服务端
                .childOption(ChannelOption.TCP_NODELAY, true); // 禁用 Nagle，降低小包延迟
        NettyChannelOptions.applyChildOptions(bootstrap);
        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) { // 每条新连接初始化 pipeline
                        if (sslContext != null) {
                            ch.pipeline().addFirst("ssl", sslContext.newHandler(ch.alloc()));
                        }
                        GamePipelineConfigurer.configureCommonHandlers(
                                ch.pipeline(), backpressureHandler, packetRateLimiterHandler, traceContextHandler);
                        if (properties.getProtocolHmac().isEnabled()) {
                            ch.pipeline().addFirst("protocol-hmac", new ProtocolHmacCodec(properties));
                        }
                        GamePipelineConfigurer.addBusinessHandler(
                                ch.pipeline(), gameBusinessExecutorGroup, gameServerChannelHandler);
                    }
                });

        bindFuture = bootstrap.bind(port).sync(); // 阻塞直到绑定成功
        log.info("GameNettyServer started (TCP, reference Lunar frame) on port={}, tls={}", port, sslContext != null);
    }

    /**
     * 停止服务：关闭监听 Channel 并优雅退出事件线程组。
     */
    public void stop() {
        try {
            if (bindFuture != null) { // 已 bind 才需要关闭
                bindFuture.channel().close().syncUninterruptibly(); // 同步关闭监听 socket
            }
        } catch (Exception e) {
            log.warn("Error closing Netty channel", e); // 关闭失败仅记录
        }
        try {
            if (bossGroup != null) { // 释放 accept 线程组
                bossGroup.shutdownGracefully(); // 等待任务结束
            }
        } catch (Exception e) {
            log.warn("Error shutting down bossGroup", e);
        }
        try {
            if (workerGroup != null) { // 释放 worker 线程组
                workerGroup.shutdownGracefully();
            }
        } catch (Exception e) {
            log.warn("Error shutting down workerGroup", e);
        }
        log.info("GameNettyServer stopped"); // 完成日志
    }
}
