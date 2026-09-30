package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.GuildPacketHandlers;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.GuildSystemProto;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公会协议适配与 Packet 入口。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GuildNettyAndPacketTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("公会协议适配与 Packet 入口")
class GuildNettyAndPacketTest {

    private GuildService guildService;
    private PlayerContextResolver resolver;
    private GuildNettyService nettyService;
    private GuildPacketHandlers handlers;

    @BeforeEach
    void setUp() {
        guildService = mock(GuildService.class);
        resolver = mock(PlayerContextResolver.class);
        nettyService = new GuildNettyService(
                guildService,
                new GuildWarService(null, guildService),
                mock(GuildTechService.class),
                resolver,
                mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class));
        handlers = new GuildPacketHandlers(nettyService);
    }

    /**
     * 验证点：未登录创建公会返回 retcode=1。
     * <p>测试方法 {@code createWhenNotLoggedIn}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(0);}</li>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录创建公会返回 retcode=1")
    void createWhenNotLoggedIn() {
        when(resolver.resolvePlayerId(any())).thenReturn(0);
        GuildSystemProto.CreateGuildScRsp rsp = nettyService.handleCreate(
                GuildSystemProto.CreateGuildCsReq.newBuilder().setName("x").build(),
                new EmbeddedChannel());
        assertEquals(1, rsp.getRetcode());
    }

    /**
     * 验证点：创建成功回填公会摘要。
     * <p>测试方法 {@code createSuccessReturnsSummary}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(1001);}</li>
     *   <li>{@code when(guildService.create(eq(1001), eq("星穹"), eq("hi")))}</li>
     *   <li>{@code when(guildService.loadGuildForPlayer(1001)).thenReturn(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(9L, rsp.getGuild().getGuildId());}</li>
     *   <li>{@code assertEquals("星穹", rsp.getGuild().getName());}</li>
     * </ul>
     */
    @Test
    @DisplayName("创建成功回填公会摘要")
    void createSuccessReturnsSummary() {
        when(resolver.resolvePlayerId(any())).thenReturn(1001);
        when(guildService.create(eq(1001), eq("星穹"), eq("hi")))
                .thenReturn(new GuildService.OpResult(true, 0));
        when(guildService.loadGuildForPlayer(1001)).thenReturn(
                new GuildService.GuildInfo(9L, "星穹", "hi", 1, 0, 1001, 1, 30));

        GuildSystemProto.CreateGuildScRsp rsp = nettyService.handleCreate(
                GuildSystemProto.CreateGuildCsReq.newBuilder().setName("星穹").setNotice("hi").build(),
                new EmbeddedChannel());
        assertEquals(0, rsp.getRetcode());
        assertEquals(9L, rsp.getGuild().getGuildId());
        assertEquals("星穹", rsp.getGuild().getName());
    }

    /**
     * 验证点：GetInfo 组装成员列表与个人贡献。
     * <p>测试方法 {@code getInfoAssemblesMembers}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(1001);}</li>
     *   <li>{@code when(guildService.loadGuildForPlayer(1001)).thenReturn(}</li>
     *   <li>{@code when(guildService.listMembers(3L)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(2, rsp.getMembersCount());}</li>
     *   <li>{@code assertEquals(40, rsp.getMyContribution());}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetInfo 组装成员列表与个人贡献")
    void getInfoAssemblesMembers() {
        when(resolver.resolvePlayerId(any())).thenReturn(1001);
        when(guildService.loadGuildForPlayer(1001)).thenReturn(
                new GuildService.GuildInfo(3L, "G", "", 2, 50, 1001, 2, 30));
        when(guildService.listMembers(3L)).thenReturn(List.of(
                new GuildService.MemberInfo(1001, 2, 40, 10),
                new GuildService.MemberInfo(1002, 0, 5, 5)
        ));

        GuildSystemProto.GetGuildInfoScRsp rsp = nettyService.handleGetInfo(new EmbeddedChannel());
        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getMembersCount());
        assertEquals(40, rsp.getMyContribution());
    }

    /**
     * 验证点：PacketHandlers 创建公会：请求解析与 SC 回包。
     * <p>测试方法 {@code packetHandlerCreateRoundTrip}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(2001);}</li>
     *   <li>{@code when(guildService.create(eq(2001), eq("Packet公会"), eq("")))}</li>
     *   <li>{@code when(guildService.loadGuildForPlayer(2001)).thenReturn(}</li>
     *   <li>{@code when(mockCtx.channel()).thenReturn(channel);}</li>
     *   <li>{@code }).when(mockCtx).writeAndFlush(org.mockito.ArgumentMatchers.any(Object.class));}</li>
     *   <li>{@code assertNotNull(packet);}</li>
     * </ul>
     */
    @Test
    @DisplayName("PacketHandlers 创建公会：请求解析与 SC 回包")
    void packetHandlerCreateRoundTrip() throws Exception {
        when(resolver.resolvePlayerId(any())).thenReturn(2001);
        when(guildService.create(eq(2001), eq("Packet公会"), eq("")))
                .thenReturn(new GuildService.OpResult(true, 0));
        when(guildService.loadGuildForPlayer(2001)).thenReturn(
                new GuildService.GuildInfo(11L, "Packet公会", "", 1, 0, 2001, 1, 30));

        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelHandlerContext mockCtx = mock(ChannelHandlerContext.class);
        when(mockCtx.channel()).thenReturn(channel);

        java.util.concurrent.atomic.AtomicReference<GamePacket> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            captured.set(inv.getArgument(0, GamePacket.class));
            return channel.newSucceededFuture();
        }).when(mockCtx).writeAndFlush(org.mockito.ArgumentMatchers.any(Object.class));

        GuildSystemProto.CreateGuildCsReq req = GuildSystemProto.CreateGuildCsReq.newBuilder()
                .setName("Packet公会")
                .build();
        handlers.onCreate(mockCtx, new GamePacket(CmdIds.CREATE_GUILD_CS_REQ, req.toByteArray()));

        GamePacket packet = captured.get();
        assertNotNull(packet);
        assertEquals(CmdIds.CREATE_GUILD_SC_RSP, packet.getCmdId());
        GuildSystemProto.CreateGuildScRsp rsp =
                GuildSystemProto.CreateGuildScRsp.parseFrom(packet.getPayload());
        assertEquals(0, rsp.getRetcode());
        assertEquals(11L, rsp.getGuild().getGuildId());
    }

    /**
     * 验证点：商店列表与购买协议。
     * <p>测试方法 {@code shopListAndBuy}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(1001);}</li>
     *   <li>{@code when(guildService.listShopProducts()).thenReturn(List.of(}</li>
     *   <li>{@code when(guildService.boughtThisWeek(1001, 1)).thenReturn(0, 1);}</li>
     *   <li>{@code when(guildService.buyShop(1001, 1)).thenReturn(new GuildService.OpResult(true, 0));}</li>
     *   <li>{@code assertEquals(0, shop.getRetcode());}</li>
     *   <li>{@code assertEquals(1, shop.getProductsCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("商店列表与购买协议")
    void shopListAndBuy() {
        when(resolver.resolvePlayerId(any())).thenReturn(1001);
        when(guildService.listShopProducts()).thenReturn(List.of(
                new GuildService.ShopProduct(1, 101, 60, 100, 5, "星琼")
        ));
        when(guildService.boughtThisWeek(1001, 1)).thenReturn(0, 1);
        when(guildService.buyShop(1001, 1)).thenReturn(new GuildService.OpResult(true, 0));

        GuildSystemProto.GetGuildShopScRsp shop = nettyService.handleGetShop(new EmbeddedChannel());
        assertEquals(0, shop.getRetcode());
        assertEquals(1, shop.getProductsCount());
        assertEquals(100, shop.getProducts(0).getContributionCost());

        GuildSystemProto.BuyGuildShopScRsp buy = nettyService.handleBuyShop(
                GuildSystemProto.BuyGuildShopCsReq.newBuilder().setProductId(1).build(),
                new EmbeddedChannel());
        assertEquals(0, buy.getRetcode());
        assertEquals(1, buy.getBoughtThisWeek());
    }

    /**
     * 验证点：贡献/加入/退出协议 retcode 透传。
     * <p>测试方法 {@code contributeJoinLeaveRetcodes}：
     * <ul>
     *   <li>{@code when(resolver.resolvePlayerId(any())).thenReturn(1001);}</li>
     *   <li>{@code when(guildService.contribute(1001, 10)).thenReturn(new GuildService.OpResult(true, 0));}</li>
     *   <li>{@code when(guildService.loadGuildForPlayer(1001)).thenReturn(}</li>
     *   <li>{@code when(guildService.loadMember(1L, 1001)).thenReturn(}</li>
     *   <li>{@code when(guildService.join(eq(1001), anyLong())).thenReturn(new GuildService.OpResult(false, 4));}</li>
     *   <li>{@code when(guildService.leave(1001)).thenReturn(new GuildService.OpResult(true, 0));}</li>
     * </ul>
     */
    @Test
    @DisplayName("贡献/加入/退出协议 retcode 透传")
    void contributeJoinLeaveRetcodes() {
        when(resolver.resolvePlayerId(any())).thenReturn(1001);
        when(guildService.contribute(1001, 10)).thenReturn(new GuildService.OpResult(true, 0));
        when(guildService.loadGuildForPlayer(1001)).thenReturn(
                new GuildService.GuildInfo(1L, "G", "", 1, 10, 1001, 1, 30));
        when(guildService.loadMember(1L, 1001)).thenReturn(
                new GuildService.MemberInfo(1001, 2, 10, 10));
        when(guildService.join(eq(1001), anyLong())).thenReturn(new GuildService.OpResult(false, 4));
        when(guildService.leave(1001)).thenReturn(new GuildService.OpResult(true, 0));

        assertEquals(0, nettyService.handleContribute(
                GuildSystemProto.ContributeGuildCsReq.newBuilder().setAmount(10).build(),
                new EmbeddedChannel()).getRetcode());
        assertEquals(4, nettyService.handleJoin(
                GuildSystemProto.JoinGuildCsReq.newBuilder().setGuildId(8).build(),
                new EmbeddedChannel()).getRetcode());
        assertEquals(0, nettyService.handleLeave(new EmbeddedChannel()).getRetcode());
    }
}
