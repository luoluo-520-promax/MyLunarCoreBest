package cn.itcast.demo.mylunarcore.net;

/**
 * 出站包优先级：战斗指令快传，场景移动普通队列。
 * <p>
 * CmdId 约定：战斗 200–299 为 HIGH；场景 300–399 为 NORMAL；其余 DEFAULT。
 */
public enum PacketPriority {
    HIGH,
    NORMAL,
    LOW;

    public static PacketPriority ofCmdId(int cmdId) {
        if (cmdId >= 200 && cmdId < 300) {
            return HIGH;
        }
        if (cmdId >= 300 && cmdId < 400) {
            return NORMAL;
        }
        return LOW;
    }
}
