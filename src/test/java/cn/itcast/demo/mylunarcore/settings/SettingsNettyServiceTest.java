package cn.itcast.demo.mylunarcore.settings; // 设置协议门面单测所在包

import cn.itcast.demo.mylunarcore.net.CmdIds; // 断言推送命令号是否为 936
import cn.itcast.demo.mylunarcore.net.GamePacket; // 捕获 writeAndFlush 的下行包
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver; // Mock：模拟登录/未登录
import cn.itcast.demo.mylunarcore.protocol.SettingsSystemProto; // 构造请求与断言响应字段
import io.netty.channel.Channel; // Mock 连接
import io.netty.channel.ChannelFuture; // writeAndFlush 返回值占位
import org.junit.jupiter.api.BeforeEach; // 每个用例前重建 mock
import org.junit.jupiter.api.DisplayName; // 中文用例名
import org.junit.jupiter.api.Test; // 测试方法
import org.mockito.ArgumentCaptor; // 捕获推送的 GamePacket

import java.util.List; // 工单列表构造

import static org.junit.jupiter.api.Assertions.assertEquals; // 相等断言
import static org.junit.jupiter.api.Assertions.assertFalse; // 假值断言
import static org.junit.jupiter.api.Assertions.assertTrue; // 真值断言
import static org.mockito.ArgumentMatchers.any; // 任意参数匹配
import static org.mockito.ArgumentMatchers.anyBoolean; // 任意布尔
import static org.mockito.ArgumentMatchers.anyInt; // 任意 int
import static org.mockito.ArgumentMatchers.anyString; // 任意字符串
import static org.mockito.ArgumentMatchers.eq; // 精确匹配 playerId
import static org.mockito.Mockito.mock; // 创建 mock
import static org.mockito.Mockito.never; // 断言从未调用
import static org.mockito.Mockito.verify; // 校验交互
import static org.mockito.Mockito.when; // 桩返回值

/**
 * SettingsNettyService 协议门面测试：登录校验、回包字段、推送 CmdId、工单映射、sanitize 夹紧。
 */
@DisplayName("SettingsNettyService 设置与客服协议测试")
class SettingsNettyServiceTest {

    /**
     * 模拟已登录玩家的 uid，与 stub 的 getOrCreate/update 入参一致
     */
    private static final int PLAYER_ID = 1001;

    /**
     * Mock：控制 resolvePlayerId 返回 0（未登录）或 PLAYER_ID
     */
    private PlayerContextResolver contextResolver;
    /**
     * Mock：设置应用服务，避免真实 JDBC
     */
    private PlayerSettingsApplicationService settingsService;
    /**
     * Mock：工单应用服务
     */
    private SupportTicketApplicationService ticketService;
    /**
     * 被测门面实例
     */
    private SettingsNettyService service;

    /**
     * 每个用例前重新 mock，避免用例间状态污染
     */
    @BeforeEach
    void setUp() {
        contextResolver = mock(PlayerContextResolver.class); // 新建会话解析 mock
        settingsService = mock(PlayerSettingsApplicationService.class); // 新建设置服务 mock
        ticketService = mock(SupportTicketApplicationService.class); // 新建工单服务 mock
        service = new SettingsNettyService(contextResolver, settingsService, ticketService); // 组装被测对象
    } // setUp 结束

    /**
     * 未登录时拉取设置必须失败且不得访问设置服务，防止匿名读写云端配置
     */
    @Test
    @DisplayName("未登录拉取设置应返回 retcode=1")
    void getSettingsWithoutLoginShouldFail() {
        when(contextResolver.resolvePlayerId(any())).thenReturn(0); // 模拟未绑定会话
        SettingsSystemProto.GetPlayerSettingsScRsp rsp = service.handleGetPlayerSettings(
                SettingsSystemProto.GetPlayerSettingsCsReq.getDefaultInstance(), mock(Channel.class)); // 发空请求
        assertEquals(1, rsp.getRetcode()); // 业务约定：1=未登录
        verify(settingsService, never()).getOrCreate(anyInt()); // 不得触达仓储建档逻辑
    } // getSettingsWithoutLoginShouldFail 结束

    /**
     * 已登录拉取应成功，并带出默认画质 2、至少 5 条键位、主音量 80
     */
    @Test
    @DisplayName("拉取设置成功应返回默认键位与画质")
    void getSettingsShouldReturnSnapshot() {
        when(contextResolver.resolvePlayerId(any())).thenReturn(PLAYER_ID); // 已登录
        when(settingsService.getOrCreate(PLAYER_ID)).thenReturn(PlayerSettingsDefaults.create()); // 返回出厂设置

        SettingsSystemProto.GetPlayerSettingsScRsp rsp = service.handleGetPlayerSettings(
                SettingsSystemProto.GetPlayerSettingsCsReq.getDefaultInstance(), mock(Channel.class));

        assertEquals(0, rsp.getRetcode()); // 成功
        assertEquals(2, rsp.getSettings().getDisplay().getGraphicsQuality()); // 默认高画质
        assertTrue(rsp.getSettings().getKeybindsCount() >= 5); // 默认键位表非空
        assertEquals(80, rsp.getSettings().getSound().getMasterVolume()); // 默认主音量
    } // getSettingsShouldReturnSnapshot 结束

    /**
     * 更新成功后必须 writeAndFlush CmdId=936 推送，客户端才能热刷新设置 UI
     */
    @Test
    @DisplayName("更新设置应推送 PLAYER_SETTINGS_UPDATE_SC_NOTIFY")
    void updateSettingsShouldNotify() {
        when(contextResolver.resolvePlayerId(any())).thenReturn(PLAYER_ID); // 已登录
        PlayerSettings updated = PlayerSettingsDefaults.create(); // 基线默认
        updated.getSound().setMasterVolume(30); // 模拟用户把主音量调到 30
        when(settingsService.update(eq(PLAYER_ID), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(updated); // 应用层返回合并结果

        Channel channel = mock(Channel.class); // 捕获推送
        when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class)); // 异步写完成占位

        SettingsSystemProto.UpdatePlayerSettingsCsReq req = SettingsSystemProto.UpdatePlayerSettingsCsReq.newBuilder()
                .setSettings(SettingsNettyService.toProto(updated)) // 把领域对象编成协议快照提交
                .build();
        SettingsSystemProto.UpdatePlayerSettingsScRsp rsp = service.handleUpdatePlayerSettings(req, channel);

        assertEquals(0, rsp.getRetcode()); // 更新成功
        assertEquals(30, rsp.getSettings().getSound().getMasterVolume()); // 回包音量与合并结果一致
        ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class); // 捕获推送包
        verify(channel).writeAndFlush(captor.capture()); // 必须发生一次推送
        assertEquals(CmdIds.PLAYER_SETTINGS_UPDATE_SC_NOTIFY, captor.getValue().getCmdId()); // 命令号=936
    } // updateSettingsShouldNotify 结束

    /**
     * 工单提交成功时 retcode=0 且 ticketId 透传应用层返回值
     */
    @Test
    @DisplayName("提交客服工单成功应回传 ticketId")
    void submitTicketShouldReturnId() {
        when(contextResolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
        when(ticketService.submit(eq(PLAYER_ID), anyString(), anyString(), anyString()))
                .thenReturn(SupportTicketApplicationService.SubmitResult.ok(88L)); // 模拟入库得到 88

        SettingsSystemProto.SubmitSupportTicketScRsp rsp = service.handleSubmitSupportTicket(
                SettingsSystemProto.SubmitSupportTicketCsReq.newBuilder()
                        .setCategory("sound") // 声音类咨询
                        .setSubject("没有声音")
                        .setContent("设置里主音量开了但仍无声")
                        .build(),
                mock(Channel.class));

        assertEquals(0, rsp.getRetcode()); // 成功
        assertEquals(88L, rsp.getTicketId()); // 工单号回传
    } // submitTicketShouldReturnId 结束

    /**
     * 列表接口应把实体的 category、adminReply 等字段正确映射到协议 SupportTicketInfo
     */
    @Test
    @DisplayName("查询工单列表应映射状态与回复")
    void listTicketsShouldMapFields() {
        when(contextResolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
        SupportTicketEntity ticket = new SupportTicketEntity(); // 构造一条已回复工单
        ticket.setId(7L);
        ticket.setPlayerId(PLAYER_ID);
        ticket.setCategory("display");
        ticket.setSubject("画质卡顿");
        ticket.setContent("高画质掉帧");
        ticket.setStatus(2); // 已回复
        ticket.setAdminReply("请尝试降低分辨率缩放");
        when(ticketService.listForPlayer(PLAYER_ID, 1, 20))
                .thenReturn(new SupportTicketApplicationService.ListResult(List.of(ticket), 1)); // 一页一条、总数 1

        SettingsSystemProto.GetSupportTicketListScRsp rsp = service.handleGetSupportTicketList(
                SettingsSystemProto.GetSupportTicketListCsReq.newBuilder().setPage(1).setPageSize(20).build(),
                mock(Channel.class));

        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getTotal()); // 总数正确
        assertEquals(1, rsp.getTicketsCount()); // 本页一条
        assertEquals("display", rsp.getTickets(0).getCategory()); // 分类映射
        assertEquals("请尝试降低分辨率缩放", rsp.getTickets(0).getAdminReply()); // 客服回复映射
    } // listTicketsShouldMapFields 结束

    /**
     * sanitize 必须把越界音量夹到 100、负画质夹到 0，且键位表不为空
     */
    @Test
    @DisplayName("默认设置 sanitize 应夹紧越界音量")
    void sanitizeShouldClampVolumes() {
        PlayerSettings raw = PlayerSettingsDefaults.create();
        raw.getSound().setMasterVolume(999); // 故意越上界
        raw.getDisplay().setGraphicsQuality(-1); // 故意越下界
        PlayerSettings sanitized = PlayerSettingsDefaults.sanitize(raw); // 执行夹紧
        assertEquals(100, sanitized.getSound().getMasterVolume()); // 上限 100
        assertEquals(0, sanitized.getDisplay().getGraphicsQuality()); // 下限 0
        assertFalse(sanitized.getKeybinds().isEmpty()); // 键位仍可用
    } // sanitizeShouldClampVolumes 结束
} // SettingsNettyServiceTest 结束
