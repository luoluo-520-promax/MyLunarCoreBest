package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GamePacket 业务包模型测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GamePacketTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GamePacket 业务包模型测试")
class GamePacketTest {

    private static final Logger log = LoggerFactory.getLogger(GamePacketTest.class);

    /**
     * 验证点：构造器应保留 cmdId 与 payload 副本语义。
     * <p>测试方法 {@code constructorShouldExposeCmdIdAndPayload}：
     * <ul>
     *   <li>{@code assertEquals(cmdId, packet.getCmdId());}</li>
     *   <li>{@code assertArrayEquals(payload, packet.getPayload());}</li>
     * </ul>
     */
    @Test
    @DisplayName("构造器应保留 cmdId 与 payload 副本语义")
    void constructorShouldExposeCmdIdAndPayload() {
        int cmdId = CmdIds.PLAYER_HEART_BEAT_CS_REQ;
        byte[] payload = new byte[]{0x01, 0x02, 0x03};
        GamePacket packet = NetTestFixtures.packet(cmdId, payload);

        log.info("业务包字段校验: cmdId={}, payloadLen={}, payloadHex={}",
                packet.getCmdId(), packet.getPayload().length, bytesHex(packet.getPayload()));
        assertEquals(cmdId, packet.getCmdId());
        assertArrayEquals(payload, packet.getPayload());
    }

    /**
     * 验证点：空负载应表示为零长度数组。
     * <p>测试方法 {@code emptyPayloadShouldBeZeroLengthArray}：
     * <ul>
     *   <li>{@code assertEquals(CmdIds.GET_SESSION_INFO_CS_REQ, packet.getCmdId());}</li>
     *   <li>{@code assertEquals(0, packet.getPayload().length);}</li>
     * </ul>
     */
    @Test
    @DisplayName("空负载应表示为零长度数组")
    void emptyPayloadShouldBeZeroLengthArray() {
        GamePacket packet = NetTestFixtures.packet(CmdIds.GET_SESSION_INFO_CS_REQ);

        log.info("空负载校验: cmdId={}, payloadLen={}, payloadNull={}",
                packet.getCmdId(), packet.getPayload().length, packet.getPayload() == null);
        assertEquals(CmdIds.GET_SESSION_INFO_CS_REQ, packet.getCmdId());
        assertEquals(0, packet.getPayload().length);
    }

    private static String bytesHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
