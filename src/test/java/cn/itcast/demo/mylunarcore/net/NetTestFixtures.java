package cn.itcast.demo.mylunarcore.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_FOOTER;
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_HEADER;

/**
 * net 包测试共用构造：业务包、原始帧字节等。
 */
final class NetTestFixtures {

    private NetTestFixtures() {
    }

    static GamePacket packet(int cmdId, byte... payload) {
        return new GamePacket(cmdId, payload == null ? new byte[0] : payload);
    }

    static GamePacket packet(int cmdId, String payloadText) {
        return packet(cmdId, payloadText.getBytes());
    }

    /**
     * 按 Lunar 帧格式写入原始字节（可指定错误魔数用于负例测试）。
     */
    static ByteBuf rawFrame(ByteBufAllocator alloc, int headerMagic, int cmdId, byte[] payload, int footerMagic) {
        byte[] body = payload == null ? new byte[0] : payload;
        ByteBuf buf = alloc.buffer();
        buf.writeIntLE(headerMagic);
        buf.writeShortLE(cmdId);
        buf.writeShortLE(0);
        buf.writeIntLE(body.length);
        if (body.length > 0) {
            buf.writeBytes(body);
        }
        buf.writeIntLE(footerMagic);
        return buf;
    }

    static ByteBuf validFrame(int cmdId, byte[] payload) {
        return rawFrame(Unpooled.buffer().alloc(), MAGIC_HEADER, cmdId, payload, MAGIC_FOOTER);
    }

    static ByteBuf validFrame(int cmdId, String payloadText) {
        return validFrame(cmdId, payloadText.getBytes());
    }
}
