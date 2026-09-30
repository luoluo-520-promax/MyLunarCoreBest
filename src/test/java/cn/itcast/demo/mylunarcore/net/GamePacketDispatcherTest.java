package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.common.GameTrafficMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GamePacketDispatcher 包分发测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GamePacketDispatcherTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GamePacketDispatcher 包分发测试")
class GamePacketDispatcherTest {

    private static final Logger log = LoggerFactory.getLogger(GamePacketDispatcherTest.class);

    private PacketCommandRegistry registry;
    private GameTrafficMetrics metrics;
    private GamePacketDispatcher dispatcher;
    private ChannelHandlerContext ctx;
    private Channel channel;

    @BeforeEach
    void setUp() {
        registry = mock(PacketCommandRegistry.class);
        metrics = new GameTrafficMetrics();
        dispatcher = new GamePacketDispatcher(registry, metrics);
        ctx = mock(ChannelHandlerContext.class);
        channel = mock(Channel.class);
        when(ctx.channel()).thenReturn(channel);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 29000));
        log.info("分发器初始化: initialPacketCount={}", metrics.getPacketsHandled());
    }

    /**
     * 验证点：已注册 cmdId 应调用对应处理器并计数。
     * <p>测试方法 {@code registeredCmdShouldInvokeHandlerAndCount}：
     * <ul>
     *   <li>{@code when(registry.getHandler(cmdId)).thenReturn(handler);}</li>
     *   <li>{@code assertEquals(countBefore + 1, countAfter);}</li>
     *   <li>{@code assertEquals(cmdId, handledPacket.get().getCmdId());}</li>
     *   <li>{@code verify(registry).getHandler(cmdId);}</li>
     * </ul>
     */
    @Test
    @DisplayName("已注册 cmdId 应调用对应处理器并计数")
    void registeredCmdShouldInvokeHandlerAndCount() {
        int cmdId = CmdIds.FIGHT_START_CS_REQ;
        GamePacket packet = NetTestFixtures.packet(cmdId, "start");
        AtomicReference<GamePacket> handledPacket = new AtomicReference<>();
        PacketCommandHandler handler = (c, p) -> handledPacket.set(p);
        when(registry.getHandler(cmdId)).thenReturn(handler);

        long countBefore = metrics.getPacketsHandled();
        dispatcher.dispatch(ctx, packet);
        long countAfter = metrics.getPacketsHandled();

        log.info("已注册分发: cmdId={}, remote={}, countBefore={}, countAfter={}, handledCmdId={}",
                cmdId, channel.remoteAddress(), countBefore, countAfter,
                handledPacket.get() == null ? null : handledPacket.get().getCmdId());
        assertEquals(countBefore + 1, countAfter);
        assertEquals(cmdId, handledPacket.get().getCmdId());
        verify(registry).getHandler(cmdId);
    }

    /**
     * 验证点：未注册 cmdId 应仅计数不调用处理器。
     * <p>测试方法 {@code unknownCmdShouldCountWithoutHandler}：
     * <ul>
     *   <li>{@code when(registry.getHandler(cmdId)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(countBefore + 1, countAfter);}</li>
     *   <li>{@code verify(registry).getHandler(cmdId);}</li>
     * </ul>
     */
    @Test
    @DisplayName("未注册 cmdId 应仅计数不调用处理器")
    void unknownCmdShouldCountWithoutHandler() {
        int cmdId = 99999;
        GamePacket packet = NetTestFixtures.packet(cmdId, new byte[0]);
        when(registry.getHandler(cmdId)).thenReturn(null);

        long countBefore = metrics.getPacketsHandled();
        dispatcher.dispatch(ctx, packet);
        long countAfter = metrics.getPacketsHandled();

        log.info("未知命令: cmdId={}, remote={}, countBefore={}, countAfter={}, handlerInvoked=false",
                cmdId, channel.remoteAddress(), countBefore, countAfter);
        assertEquals(countBefore + 1, countAfter);
        verify(registry).getHandler(cmdId);
    }

    /**
     * 验证点：处理器抛异常不应向外传播。
     * <p>测试方法 {@code handlerExceptionShouldBeSwallowed}：
     * <ul>
     *   <li>{@code doThrow(new RuntimeException("boom")).when(handler).handle(any(), any());}</li>
     *   <li>{@code when(registry.getHandler(cmdId)).thenReturn(handler);}</li>
     *   <li>{@code verify(handler).handle(ctx, packet);}</li>
     * </ul>
     */
    @Test
    @DisplayName("处理器抛异常不应向外传播")
    void handlerExceptionShouldBeSwallowed() throws Exception {
        int cmdId = CmdIds.FIGHT_ACTION_CS_REQ;
        GamePacket packet = NetTestFixtures.packet(cmdId, "action");
        PacketCommandHandler handler = mock(PacketCommandHandler.class);
        doThrow(new RuntimeException("boom")).when(handler).handle(any(), any());
        when(registry.getHandler(cmdId)).thenReturn(handler);

        dispatcher.dispatch(ctx, packet);

        log.info("异常兜底: cmdId={}, remote={}, metricsAfter={}",
                cmdId, channel.remoteAddress(), metrics.getPacketsHandled());
        verify(handler).handle(ctx, packet);
    }

    /**
     * 验证点：空处理器映射应安全返回。
     * <p>测试方法 {@code nullHandlerShouldNotThrow}：
     * <ul>
     *   <li>{@code when(registry.getHandler(cmdId)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(1L, metrics.getPacketsHandled());}</li>
     *   <li>{@code verify(registry).getHandler(cmdId);}</li>
     * </ul>
     */
    @Test
    @DisplayName("空处理器映射应安全返回")
    void nullHandlerShouldNotThrow() {
        int cmdId = CmdIds.GET_CUR_SCENE_INFO_CS_REQ;
        when(registry.getHandler(cmdId)).thenReturn(null);

        dispatcher.dispatch(ctx, NetTestFixtures.packet(cmdId));

        log.info("空处理器: cmdId={}, remote={}, packetCount={}",
                cmdId, channel.remoteAddress(), metrics.getPacketsHandled());
        assertEquals(1L, metrics.getPacketsHandled());
        verify(registry).getHandler(cmdId);
    }
}
