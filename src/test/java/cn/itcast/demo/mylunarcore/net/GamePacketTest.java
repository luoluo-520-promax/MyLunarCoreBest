package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GamePacket 业务包模型测试")
class GamePacketTest {

    private static final Logger log = LoggerFactory.getLogger(GamePacketTest.class);

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
