package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("GameServerPacketCache 协议包体缓存测试")
class GameServerPacketCacheTest {

    private static final Logger log = LoggerFactory.getLogger(GameServerPacketCacheTest.class);

    private static final int TEST_CMD_ID = 9999;

    private GameServerPacketCache cache;

    @BeforeEach
    void setUp() {
        cache = new GameServerPacketCache();
        log.info("包体缓存初始化: cacheClass={}", cache.getClass().getSimpleName());
    }

    @Test
    @DisplayName("getOrPutSerializedPayload 应缓存并复用同一 byte[]")
    void getOrPutShouldCachePayload() {
        byte[] first = cache.getOrPutSerializedPayload(TEST_CMD_ID, cmd -> new byte[]{(byte) cmd, 1, 2});
        byte[] second = cache.getOrPutSerializedPayload(TEST_CMD_ID, cmd -> new byte[]{(byte) 0, 9, 9});

        log.info("包体缓存校验: cmdId={}, firstLength={}, secondLength={}, sameInstance={}",
                TEST_CMD_ID, first.length, second.length, first == second);
        assertSame(first, second);
        assertArrayEquals(new byte[]{(byte) TEST_CMD_ID, 1, 2}, first);
    }

    @Test
    @DisplayName("invalidateCmd 应清除指定指令缓存")
    void invalidateCmdShouldRemoveCachedPayload() {
        byte[] before = cache.getOrPutSerializedPayload(TEST_CMD_ID, cmd -> new byte[]{7, 7, 7});
        cache.invalidateCmd(TEST_CMD_ID);
        byte[] after = cache.getOrPutSerializedPayload(TEST_CMD_ID, cmd -> new byte[]{8, 8, 8});

        log.info("缓存失效校验: cmdId={}, beforeHash={}, afterHash={}, sameInstance={}",
                TEST_CMD_ID, System.identityHashCode(before),
                System.identityHashCode(after), before == after);
        assertArrayEquals(new byte[]{8, 8, 8}, after);
    }

    @Test
    @DisplayName("playerLogoutOkPayload 应返回固定登出成功包体")
    void playerLogoutOkPayloadShouldBeStable() {
        byte[] first = cache.playerLogoutOkPayload();
        byte[] second = cache.playerLogoutOkPayload();

        log.info("登出包体缓存校验: payloadLength={}, sameInstance={}",
                first.length, first == second);
        assertSame(first, second);
    }
}
