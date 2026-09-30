package cn.itcast.demo.mylunarcore.center;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MigrationTicketService}：内存票据一次性核销；Redis payload 编解码往返相等。
 */
@DisplayName("MigrationTicketService 票据测试")
class MigrationTicketServiceTest {

    /**
     * issue 写入 uid/plane/floor/坐标后 consume 一次得到完整 payload；
     * 同一 ticket 再 consume 返回 null（防重放）。
     */
    @Test
    @DisplayName("内存票据签发后可核销一次")
    void memoryTicketIssueAndConsumeOnce() {
        MigrationTicketService service = new MigrationTicketService();
        String ticket = service.issue(42L, 20101, 1, 0, 1.5f, 2f, 3.5f);
        MigrationTicketService.TicketPayload payload = service.consume(ticket);
        assertNotNull(payload);
        assertEquals(42L, payload.playerUid());
        assertEquals(20101, payload.planeId());
        assertNull(service.consume(ticket)); // 第二次核销失败
    }

    /** encode→decode 后 TicketPayload equals 原对象（含 expireAt=99）。 */
    @Test
    @DisplayName("Redis payload 编解码往返")
    void redisPayloadCodecRoundTrip() {
        MigrationTicketService.TicketPayload original =
                new MigrationTicketService.TicketPayload(9L, 1, 2, 3, 4.5f, 5.5f, 6.5f, 99L);
        String encoded = MigrationTicketService.RedisTicketStore.encode(original);
        MigrationTicketService.TicketPayload decoded =
                MigrationTicketService.RedisTicketStore.decode(encoded);
        assertNotNull(decoded);
        assertEquals(original, decoded);
    }
}
