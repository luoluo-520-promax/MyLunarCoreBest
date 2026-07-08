// 解码后的游戏业务包模型
package cn.itcast.demo.mylunarcore.net;

import lombok.Getter;

/**
 * 帧解码完成后的内存对象：命令号 + Protobuf 二进制负载，与 {@link cn.itcast.demo.mylunarcore.net} 帧格式一一对应。
 */
@Getter
public class GamePacket {
    /** 命令号（线上为 16 位小端无符号整数） */
    private final int cmdId;
    /** Protobuf 序列化后的字节数组，可为空数组 */
    private final byte[] payload;

    public GamePacket(int cmdId, byte[] payload) {
        this.cmdId = cmdId;
        this.payload = payload;
    }

}
