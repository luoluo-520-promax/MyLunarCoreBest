package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("LunarFrameConstants 帧格式常量测试")
class LunarFrameConstantsTest {

    private static final Logger log = LoggerFactory.getLogger(LunarFrameConstantsTest.class);

    @Test
    @DisplayName("最小帧长应等于头尾魔数与固定前缀之和")
    void minFrameBytesShouldMatchLayout() {
        int expected = 4 + LunarFrameConstants.PREFIX_AFTER_MAGIC + 4;
        log.info("帧长度常量: magicHeader=0x{}, prefixAfterMagic={}, magicFooter=0x{}, minFrameBytes={}, expected={}",
                Integer.toHexString(LunarFrameConstants.MAGIC_HEADER),
                LunarFrameConstants.PREFIX_AFTER_MAGIC,
                Integer.toHexString(LunarFrameConstants.MAGIC_FOOTER),
                LunarFrameConstants.MIN_FRAME_BYTES,
                expected);
        assertEquals(expected, LunarFrameConstants.MIN_FRAME_BYTES);
    }

    @Test
    @DisplayName("负载与扩展头上限应为正且扩展头小于负载上限")
    void payloadAndHeaderLimitsShouldBeConsistent() {
        log.info("长度上限: maxPayloadLen={}, maxHeaderExtLen={}, payloadGreaterThanHeader={}",
                LunarFrameConstants.MAX_PAYLOAD_LEN,
                LunarFrameConstants.MAX_HEADER_EXT_LEN,
                LunarFrameConstants.MAX_PAYLOAD_LEN > LunarFrameConstants.MAX_HEADER_EXT_LEN);
        assertTrue(LunarFrameConstants.MAX_PAYLOAD_LEN > 0);
        assertTrue(LunarFrameConstants.MAX_HEADER_EXT_LEN > 0);
        assertTrue(LunarFrameConstants.MAX_PAYLOAD_LEN > LunarFrameConstants.MAX_HEADER_EXT_LEN);
    }
}
