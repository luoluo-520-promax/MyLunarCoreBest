// TCP/KCP 共用的 Netty Channel 写缓冲水位配置
package cn.itcast.demo.mylunarcore.net;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;

/**
 * Netty Channel 公共选项常量。
 * <p>
 * 统一管理写缓冲低/高水位，供 {@link GameNettyServer}、{@link GameKcpServer} 与
 * {@link NettyBackpressureHandler} 配合实现背压控制。
 */
public final class NettyChannelOptions {

    /**
     * 出站写缓冲水位标记。
     * 低水位 32KB：缓冲低于此值时 Channel.isWritable() 变为 true，恢复 autoRead。
     * 高水位 64KB：缓冲超过此值时 isWritable() 为 false，BackpressureHandler 暂停读入。
     */
    public static final WriteBufferWaterMark WRITE_BUFFER_WATER_MARK =
            new WriteBufferWaterMark(32 * 1024, 64 * 1024);

    private NettyChannelOptions() {
    }

    /**
     * 为 ServerBootstrap 的子 Channel 应用写缓冲水位选项。
     *
     * @param bootstrap TCP 或 KCP 服务端引导对象
     */
    public static void applyChildOptions(ServerBootstrap bootstrap) {
        bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, WRITE_BUFFER_WATER_MARK);
    }
}
