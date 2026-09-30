package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PacketIntegrityGuard} 测试：包序号单调、时钟偏移与 challenge 校验。
 * 开启 packetIntegrity，允许客户端时钟偏差 5 秒。
 */
@DisplayName("PacketIntegrityGuard 包完整性")
class PacketIntegrityGuardTest {

    private PacketIntegrityGuard guard;

    @BeforeEach
    void setUp() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAntiCheat().setPacketIntegrityEnabled(true);
        props.getAntiCheat().setMaxClientClockSkewMs(5000L);
        guard = new PacketIntegrityGuard(props);
    }

    /**
     * 同一 uid 序号 1→2 递增、时间戳前进，应全部 ok。
     */
    @Test
    @DisplayName("单调序号与新鲜时间戳应通过")
    void acceptsMonotonicSeqAndFreshTimestamp() {
        long now = System.currentTimeMillis();
        assertTrue(guard.validate(1L, now, 1L, null).ok());
        assertTrue(guard.validate(1L, now + 10, 2L, null).ok());
    }

    /**
     * 序号 5 已接受后再用同一序号 5 应失败，原因 SEQ_REPLAY（防重放）。
     */
    @Test
    @DisplayName("重复序号应判定为重放")
    void rejectsReplaySeq() {
        long now = System.currentTimeMillis();
        assertTrue(guard.validate(1L, now, 5L, null).ok());
        var r = guard.validate(1L, now + 1, 5L, null);
        assertFalse(r.ok());
        assertEquals(PacketIntegrityGuard.RejectReason.SEQ_REPLAY, r.reason());
    }

    /**
     * issueChallenge 后，错误 challenge 拒绝（CHALLENGE_MISMATCH）；
     * 随后用正确 challenge 与新序号应通过。
     */
    @Test
    @DisplayName("challenge 不匹配应拒绝，正确值可通过")
    void rejectsChallengeMismatch() {
        int ch = guard.issueChallenge(9L); // 为 uid=9 签发挑战值
        long now = System.currentTimeMillis();
        var bad = guard.validate(9L, now, 1L, ch + 1); // 故意传错
        assertFalse(bad.ok());
        assertEquals(PacketIntegrityGuard.RejectReason.CHALLENGE_MISMATCH, bad.reason());
        assertTrue(guard.validate(9L, now + 1, 2L, ch).ok());
    }
}
