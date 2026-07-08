// 按 cmdId 把 GamePacket 分发给已注册的处理器
package cn.itcast.demo.mylunarcore.net;

// 游戏流量指标
import cn.itcast.demo.mylunarcore.common.GameTrafficMetrics;
// 单条命令处理接口
import cn.itcast.demo.mylunarcore.net.PacketCommandHandler;
// cmdId → Handler 注册表
import cn.itcast.demo.mylunarcore.net.PacketCommandRegistry;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类常量
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger 接口
import org.slf4j.Logger;
// Spring 组件注解
import org.springframework.stereotype.Component;

/**
 * 网络包总入口：根据启动期扫描的 {@link PacketCommandHandler} 映射表分发 cmdId（注解注册，无 switch）。
 */
@Component // 由 Spring 管理单例
public class GamePacketDispatcher {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GamePacketDispatcher.class); // 本类日志

    // 命令号到处理方法的映射
    private final PacketCommandRegistry packetCommandRegistry;
    // 统计已处理包数量
    private final GameTrafficMetrics gameTrafficMetrics;

    /**
     * 构造器注入依赖。
     */
    public GamePacketDispatcher(PacketCommandRegistry packetCommandRegistry,
                                GameTrafficMetrics gameTrafficMetrics) {
        this.packetCommandRegistry = packetCommandRegistry; // 保存命令注册表
        this.gameTrafficMetrics = gameTrafficMetrics; // 保存流量指标 Bean
    }

    /**
     * 按 cmdId 分发网络包到具体处理器。
     *
     * @param ctx 当前连接上下文
     * @param packet 已解码的业务包
     */
    public void dispatch(ChannelHandlerContext ctx, GamePacket packet) {
        // 指标在入口先记账：即使后续分发失败，也能反映真实入口流量
        gameTrafficMetrics.recordPacketHandled();
        try {
            int cmdId = packet.getCmdId(); // 路由键
            PacketCommandHandler handler = packetCommandRegistry.getHandler(cmdId); // O(1) 查找处理器
            if (handler != null) {
                handler.handle(ctx, packet); // 调用绑定好的 MethodHandle
                return;
            }
            // 未注册命令按 debug 记录，避免高频探测包污染 warn/error 日志
            log.debug("Unknown cmdId={}, remote={}", cmdId, ctx.channel().remoteAddress());
        } catch (Exception e) {
            // 分发层兜底，确保单个请求异常不会打断 channel 上后续包处理
            log.warn("dispatch failed, remote={}", ctx.channel().remoteAddress(), e);
        }
    }
}
