package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlayerLoadingStateService 切图加载态。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerLoadingStateServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerLoadingStateService 切图加载态")
class PlayerLoadingStateServiceTest {

    private PlayerLoadingStateService service;

    @BeforeEach
    void setUp() {
        service = new PlayerLoadingStateService(new MigrationTicketService());
    }

    /**
     * 验证点：begin→complete 应解除 LOADING。
     * <p>测试方法 {@code beginAndComplete}：
     * <ul>
     *   <li>{@code assertTrue(service.isLoading(1001L));}</li>
     *   <li>{@code assertTrue(service.completeLoading(1001L, ticket.ticket()));}</li>
     *   <li>{@code assertFalse(service.isLoading(1001L));}</li>
     * </ul>
     */
    @Test
    @DisplayName("begin→complete 应解除 LOADING")
    void beginAndComplete() {
        var ticket = service.beginLoading(1001L, 10, 1, 0, 0f, 0f, 0f);
        assertTrue(service.isLoading(1001L));
        assertTrue(service.completeLoading(1001L, ticket.ticket()));
        assertFalse(service.isLoading(1001L));
    }

    @Test
    @DisplayName("预加载握手票据 complete 应标记 handshakeOnly")
    void handshakeOnlyTicket() {
        var ticket = service.beginLoading(2002L, 10, 1, 0, 0f, 0f, 0f, true);
        assertTrue(ticket.handshakeOnly());
        assertTrue(service.isHandshakeOnly(2002L));
        var result = service.completeLoadingResult(2002L, ticket.ticket());
        assertTrue(result.ok());
        assertTrue(result.handshakeOnly());
        assertFalse(service.isLoading(2002L));
    }

    /**
     * 验证点：错误票据不能解除 LOADING。
     * <p>测试方法 {@code wrongTicketRejected}：
     * <ul>
     *   <li>{@code assertFalse(service.completeLoading(1001L, "bad-ticket"));}</li>
     *   <li>{@code assertTrue(service.isLoading(1001L));}</li>
     * </ul>
     */
    @Test
    @DisplayName("错误票据不能解除 LOADING")
    void wrongTicketRejected() {
        service.beginLoading(1001L, 10, 1, 0, 0f, 0f, 0f);
        assertFalse(service.completeLoading(1001L, "bad-ticket"));
        assertTrue(service.isLoading(1001L));
    }
}
