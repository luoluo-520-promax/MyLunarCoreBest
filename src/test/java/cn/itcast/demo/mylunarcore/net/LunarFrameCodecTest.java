package cn.itcast.demo.mylunarcore.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_FOOTER;
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MAGIC_HEADER;
import static cn.itcast.demo.mylunarcore.net.LunarFrameConstants.MIN_FRAME_BYTES;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LunarFrame 编解码测试")
class LunarFrameCodecTest {

    private static final Logger log = LoggerFactory.getLogger(LunarFrameCodecTest.class);

    @Test
    @DisplayName("编码后再解码应还原 cmdId 与 payload")
    void encodeDecodeRoundTripShouldPreservePacket() {
        int cmdId = CmdIds.FIGHT_ACTION_CS_REQ;
        byte[] payload = "skill-1001".getBytes();
        GamePacket original = NetTestFixtures.packet(cmdId, payload);

        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameDecoder(), new LunarFrameEncoder());
        channel.writeOutbound(original);
        ByteBuf encoded = channel.readOutbound();
        channel.writeInbound(encoded);

        GamePacket decoded = channel.readInbound();
        assertFalse(channel.finish());

        log.info("编解码往返: cmdId={}, payloadLen={}, decodedCmdId={}, decodedPayloadLen={}, channelOpen={}",
                cmdId, payload.length,
                decoded.getCmdId(), decoded.getPayload().length, channel.isOpen());
        assertEquals(cmdId, decoded.getCmdId());
        assertArrayEquals(payload, decoded.getPayload());
    }

    @Test
    @DisplayName("半包应等待更多字节且不产出 GamePacket")
    void incompleteFrameShouldWaitForMoreBytes() {
        ByteBuf full = NetTestFixtures.validFrame(CmdIds.PLAYER_LOGIN_CS_REQ, new byte[]{0x0a});
        int totalLen = full.readableBytes();
        int halfLen = MIN_FRAME_BYTES - 1;
        ByteBuf half = full.readRetainedSlice(halfLen);

        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameDecoder());
        channel.writeInbound(half);

        GamePacket decoded = channel.readInbound();
        log.info("半包等待: minFrameBytes={}, totalFrameBytes={}, writtenBytes={}, decodedNull={}, channelOpen={}",
                MIN_FRAME_BYTES, totalLen, halfLen, decoded == null, channel.isOpen());
        assertNull(decoded);
        assertTrue(channel.isOpen());
        full.release();
    }

    @Test
    @DisplayName("错误帧头魔数应关闭连接")
    void invalidHeaderMagicShouldCloseChannel() {
        int badMagic = 0xdeadbeef;
        ByteBuf frame = NetTestFixtures.rawFrame(Unpooled.buffer().alloc(), badMagic,
                CmdIds.PLAYER_HEART_BEAT_CS_REQ, new byte[0], MAGIC_FOOTER);

        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameDecoder());
        channel.writeInbound(frame);

        log.info("非法帧头: expectedMagic=0x{}, actualMagic=0x{}, channelOpen={}",
                Integer.toHexString(MAGIC_HEADER), Integer.toHexString(badMagic), channel.isOpen());
        assertNull(channel.readInbound());
        assertFalse(channel.isOpen());
    }

    @Test
    @DisplayName("错误帧尾魔数应关闭连接")
    void invalidFooterMagicShouldCloseChannel() {
        int badFooter = 0xcafebabe;
        ByteBuf frame = NetTestFixtures.rawFrame(Unpooled.buffer().alloc(), MAGIC_HEADER,
                CmdIds.PLAYER_LOGOUT_CS_REQ, new byte[]{1}, badFooter);

        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameDecoder());
        channel.writeInbound(frame);

        log.info("非法帧尾: expectedFooter=0x{}, actualFooter=0x{}, channelOpen={}",
                Integer.toHexString(MAGIC_FOOTER), Integer.toHexString(badFooter), channel.isOpen());
        assertNull(channel.readInbound());
        assertFalse(channel.isOpen());
    }

    @Test
    @DisplayName("负载长度超过上限应关闭连接")
    void oversizedPayloadLengthShouldCloseChannel() {
        int dataLen = LunarFrameConstants.MAX_PAYLOAD_LEN + 1;
        ByteBuf frame = Unpooled.buffer();
        frame.writeIntLE(MAGIC_HEADER);
        frame.writeShortLE(CmdIds.ENTER_SCENE_CS_REQ);
        frame.writeShortLE(0);
        frame.writeIntLE(dataLen);
        frame.writeIntLE(0);

        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameDecoder());
        channel.writeInbound(frame);

        log.info("超长负载: cmdId={}, declaredDataLen={}, maxAllowed={}, frameBytes={}, channelOpen={}",
                CmdIds.ENTER_SCENE_CS_REQ, dataLen, LunarFrameConstants.MAX_PAYLOAD_LEN,
                MIN_FRAME_BYTES, channel.isOpen());
        assertNull(channel.readInbound());
        assertFalse(channel.isOpen());
    }

    @Test
    @DisplayName("cmdId 超出 16 位应拒绝编码")
    void cmdIdBeyond16BitsShouldRejectEncode() {
        GamePacket packet = NetTestFixtures.packet(0x1_0000, new byte[0]);
        EmbeddedChannel channel = new EmbeddedChannel(new LunarFrameEncoder());

        EncoderException ex = assertThrows(EncoderException.class, () -> channel.writeOutbound(packet));
        log.info("16 位 cmdId 校验: cmdId={}, errorType={}, rootMessage={}",
                packet.getCmdId(), ex.getClass().getSimpleName(), ex.getCause().getMessage());
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
        assertTrue(ex.getCause().getMessage().contains("16 bits"));
    }
}
