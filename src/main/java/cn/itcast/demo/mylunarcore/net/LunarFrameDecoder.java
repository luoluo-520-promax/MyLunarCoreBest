// 将字节流解码为 GamePacket
package cn.itcast.demo.mylunarcore.net;

// 解码结果对象
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 可读字节缓冲区
import io.netty.buffer.ByteBuf;
// 通道上下文（关闭非法连接、打日志）
import io.netty.channel.ChannelHandlerContext;
// 字节流 → 消息列表 的解码基类
import io.netty.handler.codec.ByteToMessageDecoder;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类（系统）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;

// 下游输出列表（每解出一帧 add 一个 GamePacket）
import java.util.List;

// 静态导入帧格式常量
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.*;

/**
 * 入站解码器：从 TCP 粘包流或 KCP 负载中解析出一帧参考 LunarCore 协议，产出 {@link GamePacket}。
 */
public class LunarFrameDecoder extends ByteToMessageDecoder {

    // 本类专用日志
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, LunarFrameDecoder.class); // Logger 实例

    /**
     * 尝试从累积缓冲区解析一帧；成功则 {@code out.add(GamePacket)}。
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 可读字节不足一帧最小长度 → 等待更多数据
        if (in.readableBytes() < MIN_FRAME_BYTES) {
            return;
        }
        in.markReaderIndex(); // 标记读指针，不完整帧时可回滚
        int magic = in.readIntLE(); // 读取帧头魔数
        if (magic != MAGIC_HEADER) {
            in.resetReaderIndex();
            log.warn("Invalid frame magic=0x{}, closing remote={}",
                    Integer.toHexString(magic), ctx.channel().remoteAddress());
            ctx.close(); // 魔数错误视为协议攻击或错乱，直接关连接
            return;
        }
        int opcode = in.readUnsignedShortLE(); // 命令号（16 位）
        int headerExtLen = in.readUnsignedShortLE(); // 扩展头长度
        int dataLen = in.readIntLE(); // Protobuf 负载长度
        if (dataLen < 0 || dataLen > MAX_PAYLOAD_LEN) {
            log.warn("Invalid dataLen={}, closing remote={}", dataLen, ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        if (headerExtLen < 0 || headerExtLen > MAX_HEADER_EXT_LEN) {
            log.warn("Invalid headerExtLen={}, closing remote={}", headerExtLen, ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        // 剩余需要读：扩展头 + 负载 + 尾魔数(4)
        int rest = headerExtLen + dataLen + 4;
        if (in.readableBytes() < rest) {
            in.resetReaderIndex(); // 半包，回滚等待下次 decode
            return;
        }
        if (headerExtLen > 0) {
            in.skipBytes(headerExtLen); // 跳过保留扩展区
        }
        byte[] payload;
        if (dataLen == 0) {
            payload = new byte[0]; // 无正文请求
        } else {
            payload = new byte[dataLen];
            in.readBytes(payload); // 读出 Protobuf 字节
        }
        int footer = in.readIntLE(); // 帧尾魔数
        if (footer != MAGIC_FOOTER) {
            log.warn("Invalid frame footer=0x{}, closing remote={}",
                    Integer.toHexString(footer), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        out.add(new GamePacket(opcode, payload)); // 交给后续 Handler
    }
}
