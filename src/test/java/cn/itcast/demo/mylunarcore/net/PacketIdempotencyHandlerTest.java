package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PacketIdempotencyHandler 幂等回放测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PacketIdempotencyHandlerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PacketIdempotencyHandler 幂等回放测试")
class PacketIdempotencyHandlerTest {

    private static final Logger log = LoggerFactory.getLogger(PacketIdempotencyHandlerTest.class);

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 64;
    private static final long PLAYER_UID = 90077L;

    /**
     * 验证点：相同请求在 TTL 内应回放首次响应且不再下发业务。
     * <p>测试方法 {@code duplicateRequestShouldReplayFirstResponse}：
     * <ul>
     *   <li>{@code assertEquals(1, downstreamCount.get());}</li>
     *   <li>{@code assertEquals(1, downstreamCount.get());}</li>
     *   <li>{@code assertEquals(response.getCmdId(), replay.getCmdId());}</li>
     *   <li>{@code assertArrayEquals(response.getPayload(), replay.getPayload());}</li>
     *   <li>{@code assertNull(channel.readInbound());}</li>
     * </ul>
     */
    @Test
    @DisplayName("相同请求在 TTL 内应回放首次响应且不再下发业务")
    void duplicateRequestShouldReplayFirstResponse() {
        List<GamePacket> downstream = new ArrayList<>();
        AtomicInteger downstreamCount = new AtomicInteger();
        EmbeddedChannel channel = channelWithCapture(downstream, downstreamCount);

        GamePacket request = NetTestFixtures.packet(CmdIds.PLAYER_HEART_BEAT_CS_REQ, "ping-1");
        GamePacket response = NetTestFixtures.packet(CmdIds.PLAYER_HEART_BEAT_SC_RSP, "pong-1");

        channel.writeInbound(request);
        assertEquals(1, downstreamCount.get());
        channel.writeOutbound(response);

        downstream.clear();
        channel.writeInbound(request);

        GamePacket replay = channel.readOutbound();
        log.info("幂等回放: cmdId={}, payload={}, downstreamAfterDup={}, replayCmdId={}, replayPayload={}",
                request.getCmdId(), new String(request.getPayload()),
                downstreamCount.get(), replay.getCmdId(), new String(replay.getPayload()));
        assertEquals(1, downstreamCount.get());
        assertEquals(response.getCmdId(), replay.getCmdId());
        assertArrayEquals(response.getPayload(), replay.getPayload());
        assertNull(channel.readInbound());
    }

    /**
     * 验证点：不同 payload 应视为不同幂等键并分别进入业务。
     * <p>测试方法 {@code differentPayloadShouldNotReplay}：
     * <ul>
     *   <li>{@code assertEquals(2, downstreamCount.get());}</li>
     *   <li>{@code assertEquals(2, downstream.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("不同 payload 应视为不同幂等键并分别进入业务")
    void differentPayloadShouldNotReplay() {
        List<GamePacket> downstream = new ArrayList<>();
        AtomicInteger downstreamCount = new AtomicInteger();
        EmbeddedChannel channel = channelWithCapture(downstream, downstreamCount);

        GamePacket first = NetTestFixtures.packet(CmdIds.GET_BAG_CS_REQ, "req-a");
        GamePacket second = NetTestFixtures.packet(CmdIds.GET_BAG_CS_REQ, "req-b");

        channel.writeInbound(first);
        channel.writeInbound(second);

        log.info("不同负载幂等: cmdId={}, firstPayload={}, secondPayload={}, downstreamCount={}",
                CmdIds.GET_BAG_CS_REQ, new String(first.getPayload()), new String(second.getPayload()),
                downstreamCount.get());
        assertEquals(2, downstreamCount.get());
        assertEquals(2, downstream.size());
    }

    /**
     * 验证点：已登录 uid 与未登录 channel 应使用不同 principal。
     * <p>测试方法 {@code loggedInUidShouldIsolateFromAnonymousChannel}：
     * <ul>
     *   <li>{@code assertEquals(1, downstreamA.size());}</li>
     *   <li>{@code assertEquals(1, downstreamB.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("已登录 uid 与未登录 channel 应使用不同 principal")
    void loggedInUidShouldIsolateFromAnonymousChannel() {
        List<GamePacket> downstreamA = new ArrayList<>();
        List<GamePacket> downstreamB = new ArrayList<>();
        EmbeddedChannel channelA = channelWithCapture(downstreamA, new AtomicInteger());
        EmbeddedChannel channelB = channelWithCapture(downstreamB, new AtomicInteger());
        channelA.attr(PlayerChannelAttributes.PLAYER_UID).set(PLAYER_UID);

        GamePacket samePayload = NetTestFixtures.packet(CmdIds.DO_GACHA_CS_REQ, "roll-1");
        channelA.writeInbound(samePayload);
        channelB.writeInbound(samePayload);

        log.info("主体隔离: uidChannelId={}, anonChannelId={}, downstreamA={}, downstreamB={}",
                channelA.id().asShortText(), channelB.id().asShortText(),
                downstreamA.size(), downstreamB.size());
        assertEquals(1, downstreamA.size());
        assertEquals(1, downstreamB.size());
    }

    private static EmbeddedChannel channelWithCapture(List<GamePacket> downstream, AtomicInteger downstreamCount) {
        ChannelInboundHandlerAdapter capture = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof GamePacket packet) {
                    downstream.add(packet);
                    downstreamCount.incrementAndGet();
                }
            }
        };
        return new EmbeddedChannel(
                new PacketIdempotencyHandler(TTL, MAX_ENTRIES),
                capture);
    }
}
