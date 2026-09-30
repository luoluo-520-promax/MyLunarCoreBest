// 帧解码完成后的内存业务包模型
package cn.itcast.demo.mylunarcore.net;

import lombok.Getter;

/**
 * Lunar 帧解码后的内存对象。
 * <p>
 * 对应 {@link LunarFrameDecoder} 从一帧二进制中解析出的 opcode（cmdId）与 Protobuf payload，
 * 作为 Pipeline 中后续 Handler（幂等、限流、{@link GamePacketDispatcher}）的统一消息类型。
 * 出站时由 {@link LunarFrameEncoder} 重新封装为带魔数的完整帧。
 */
@Getter
public class GamePacket {

    /**
     * 命令号（16 位无符号整数，小端序）。
     * 与 {@link CmdIds} 常量及 {@link PacketCommandRegistry} 路由表键一致。
     */
    private final int cmdId;

    /**
     * Protobuf 序列化后的请求/响应正文。
     * 无正文请求（如部分心跳）时为长度 0 的空数组，非 null。
     */
    private final byte[] payload;

    /**
     * 构造解码结果或待编码的下行包。
     *
     * @param cmdId   协议命令字
     * @param payload Protobuf 字节数组，可为 empty array
     */
    public GamePacket(int cmdId, byte[] payload) {
        this.cmdId = cmdId;
        this.payload = payload;
    }
}
