// 将业务包编码为 Lunar 二进制帧
package cn.itcast.demo.mylunarcore.net;

// 解码后的业务包（命令号 + 负载）
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Netty 可写字节流
import io.netty.buffer.ByteBuf;
// 通道上下文（本编码器未直接使用，由框架传入）
import io.netty.channel.ChannelHandlerContext;
// 将 Java 对象编码为 ByteBuf 的基类
import io.netty.handler.codec.MessageToByteEncoder;

// 静态导入帧尾魔数
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_FOOTER;
// 静态导入帧头魔数
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_HEADER;

/**
 * 出站编码器：把 {@link GamePacket} 写成参考 LunarCore 二进制帧（供 TCP/KCP 发送）。
 */
public class LunarFrameEncoder extends MessageToByteEncoder<GamePacket> {

    /**
     * 将 {@link GamePacket} 写成完整一帧（含魔数与长度）。
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, GamePacket msg, ByteBuf out) {
        // 取出 Protobuf 字节数组；允许空负载
        byte[] payload = msg.getPayload();
        int dataLen = payload == null ? 0 : payload.length;
        int cmdId = msg.getCmdId();
        // 线上 opcode 为 16 位无符号，超出则拒绝编码
        if (cmdId != (cmdId & 0xFFFF)) {
            throw new IllegalArgumentException("cmdId must fit in 16 bits: " + cmdId);
        }
        out.writeIntLE(MAGIC_HEADER); // 写入帧头魔数（小端）
        out.writeShortLE(cmdId); // 命令号 opcode
        out.writeShortLE(0); // 扩展头长度，当前实现为 0
        out.writeIntLE(dataLen); // 负载长度
        if (dataLen > 0) {
            out.writeBytes(payload); // 写入 Protobuf 正文
        }
        out.writeIntLE(MAGIC_FOOTER); // 写入帧尾魔数
    }
}
