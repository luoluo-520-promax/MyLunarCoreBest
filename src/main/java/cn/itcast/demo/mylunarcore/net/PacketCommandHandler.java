// 单条网络命令的处理函数式接口
package cn.itcast.demo.mylunarcore.net;

// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Netty 连接上下文（写回响应、取 Channel 属性）
import io.netty.channel.ChannelHandlerContext;

/**
 * 命令处理回调：由 {@link PacketCommandRegistry} 在启动期绑定到具体 Bean 方法，运行期按 cmdId 查表调用。
 */
@FunctionalInterface // 仅一个抽象方法，可用 lambda 实现
public interface PacketCommandHandler {

    /**
     * 处理一条客户端请求包。
     *
     * @param ctx 当前连接的处理器上下文
     * @param packet 已解码的 cmdId + payload
     */
    void handle(ChannelHandlerContext ctx, GamePacket packet) throws Exception;
}
