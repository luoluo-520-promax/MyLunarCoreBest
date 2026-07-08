// 游戏帧编解码所用常量所在包
package cn.itcast.demo.mylunarcore.net;

/**
 * LunarCore 参考实现的二进制帧格式常量。
 * <pre>
 * 帧结构（小端序）：magic(4) | opcode u16 | headerLen u16 | dataLen i32 | 可选扩展头 | payload | magic(4)
 * </pre>
 */
public final class LunarFrameConstants {

    /** 帧头魔数，用于识别合法数据包起始 */
    public static final int MAGIC_HEADER = 0x9d74c714;
    /** 帧尾魔数，用于校验整帧完整性 */
    public static final int MAGIC_FOOTER = 0xd7a152c8;

    /** 帧头魔数之后固定前缀长度：opcode(2) + headerLen(2) + dataLen(4) */
    public static final int PREFIX_AFTER_MAGIC = 8;

    /** 一帧最少字节数：头魔数 + 前缀 + 尾魔数 */
    public static final int MIN_FRAME_BYTES = 4 + PREFIX_AFTER_MAGIC + 4;

    /** 单帧 Protobuf 负载允许的最大长度（4MB） */
    public static final int MAX_PAYLOAD_LEN = 4 * 1024 * 1024;

    /** 固定头与负载之间可预留的扩展头最大长度（参考协议保留字段） */
    public static final int MAX_HEADER_EXT_LEN = 4096;

    /** 工具类禁止实例化 */
    private LunarFrameConstants() {
        // 禁止 new
    }
}
