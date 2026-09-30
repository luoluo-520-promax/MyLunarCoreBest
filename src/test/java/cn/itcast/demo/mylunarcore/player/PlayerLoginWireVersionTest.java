package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.AccountPasswordService;
import cn.itcast.demo.mylunarcore.common.PlayerTickRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.hall.ChatService;
import cn.itcast.demo.mylunarcore.model.AccountEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GameLoginRateLimiter;
import cn.itcast.demo.mylunarcore.net.KcpSessionCryptoCodec;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.net.ProtocolHmacSigner;
import cn.itcast.demo.mylunarcore.net.SessionCryptoBinder;
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录业务流程：协议版本与会话密钥。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerLoginWireVersionTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("登录业务流程：协议版本与会话密钥")
class PlayerLoginWireVersionTest {

    private PlayerSessionService sessionService;
    private GameSessionManager sessionManager;
    private GameLoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        PlayerDataRepository repository = mock(PlayerDataRepository.class);
        sessionManager = mock(GameSessionManager.class);
        SceneManager sceneManager = mock(SceneManager.class);
        PlayerTickRegistry tickRegistry = mock(PlayerTickRegistry.class);
        PlayerDataAsyncLoadService asyncLoad = mock(PlayerDataAsyncLoadService.class);
        PlayerDataPeriodicPersistenceService periodic = mock(PlayerDataPeriodicPersistenceService.class);
        LunarCoreProperties props = new LunarCoreProperties();
        props.getKcpCrypto().setEnabled(true);
        props.getProtocolHmac().setEnabled(true);
        PlayerLoginApplicationService loginApp = mock(PlayerLoginApplicationService.class);
        ConnectionLifecycleService lifecycle = mock(ConnectionLifecycleService.class);
        rateLimiter = mock(GameLoginRateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(true);
        AccountPasswordService passwords = mock(AccountPasswordService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ChatService> chatProvider = mock(ObjectProvider.class);
        when(chatProvider.getIfAvailable()).thenReturn(null);

        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.player.StaminaService> staminaProvider = mock(ObjectProvider.class);
        when(staminaProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.common.BusinessMetrics> metricsProvider = mock(ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ClientVersionGateService> versionGateProvider = mock(ObjectProvider.class);
        when(versionGateProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceProvider = mock(ObjectProvider.class);
        when(maintenanceProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineProvider = mock(ObjectProvider.class);
        when(offlineProvider.getIfAvailable()).thenReturn(null);
        sessionService = new PlayerSessionService(
                repository,
                sessionManager,
                sceneManager,
                tickRegistry,
                asyncLoad,
                periodic,
                props,
                loginApp,
                lifecycle,
                rateLimiter,
                passwords,
                chatProvider,
                new ProtocolCompatService(),
                new SessionCryptoBinder(props),
                staminaProvider,
                metricsProvider,
                versionGateProvider,
                maintenanceProvider,
                offlineProvider);

        AccountEntity account = new AccountEntity();
        account.setId("1");
        account.setUsername("alice");
        account.setPassword("{noop}pwd");
        account.setStatus(1);
        when(repository.findAccountByUsername("alice")).thenReturn(account);
        when(passwords.matches(eq("pwd"), anyString())).thenReturn(true);
        when(passwords.needsRehash(anyString())).thenReturn(false);
        doNothing().when(loginApp).recordSuccessfulLogin(any(), anyLong(), any(), anyString());
        doNothing().when(tickRegistry).register(any());
        doNothing().when(sessionManager).updateActive(anyLong());
        doNothing().when(asyncLoad).reloadFullAsync(anyLong(), any());

        PlayerEntity player = new PlayerEntity();
        player.setUid(1001L);
        player.setAccountId(1L);
        player.setNickname("Alice");
        player.setLevel(10);
        player.setExp(0);
        player.setWorldLevel(1);
        player.setStamina(100);
        player.setSceneId(1);
        player.setPosX(BigDecimal.ZERO);
        player.setPosY(BigDecimal.ZERO);
        player.setPosZ(BigDecimal.ZERO);
        player.setCurrencyJson("{}");
        when(repository.loadPlayerByUsername("alice")).thenReturn(player);
        when(sessionManager.canAcceptNewOnlineSlot(1001L)).thenReturn(true);
        when(sessionManager.createOrReplace(eq(1001L), any(), any())).thenAnswer(inv -> {
            GameSession s = new GameSession(1001L, inv.getArgument(1), null);
            return s;
        });
        when(sessionManager.bindSessionToken(1001L)).thenReturn("tok-1001");
        PlayerData core = new PlayerData();
        core.setPlayer(player);
        when(repository.loadCoreData(1001L)).thenReturn(core);
    }

    /**
     * 验证点：wire_version=1 应返回 RET_PROTOCOL_INCOMPATIBLE。
     * <p>测试方法 {@code rejectWireV1}：
     * <ul>
     *   <li>{@code assertEquals(PlayerSessionService.RET_PROTOCOL_INCOMPATIBLE, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("wire_version=1 应返回 RET_PROTOCOL_INCOMPATIBLE")
    void rejectWireV1() {
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(
                PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                        .setUsername("alice").setPassword("pwd").setWireVersion(1).build(),
                new EmbeddedChannel(),
                "127.0.0.1");
        assertEquals(PlayerSessionService.RET_PROTOCOL_INCOMPATIBLE, rsp.getRetcode());
        assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());
    }

    /**
     * 验证点：未声明 wire_version 视为旧客户端并拒绝。
     * <p>测试方法 {@code rejectMissingWireVersion}：
     * <ul>
     *   <li>{@code assertEquals(PlayerSessionService.RET_PROTOCOL_INCOMPATIBLE, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未声明 wire_version 视为旧客户端并拒绝")
    void rejectMissingWireVersion() {
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(
                PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                        .setUsername("alice").setPassword("pwd").build(),
                new EmbeddedChannel(),
                "127.0.0.1");
        assertEquals(PlayerSessionService.RET_PROTOCOL_INCOMPATIBLE, rsp.getRetcode());
    }

    /**
     * 验证点：wire_version=2 登录成功并下发会话密钥。
     * <p>测试方法 {@code acceptWireV2AndBindCryptoKey}：
     * <ul>
     *   <li>{@code assertEquals(PlayerSessionService.RET_OK, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());}</li>
     *   <li>{@code assertFalse(rsp.getSessionCryptoKey().isBlank());}</li>
     *   <li>{@code assertEquals("tok-1001", rsp.getSessionToken());}</li>
     *   <li>{@code assertNotNull(ch.attr(KcpSessionCryptoCodec.SESSION_KEY).get());}</li>
     *   <li>{@code assertNotNull(ch.attr(ProtocolHmacSigner.SESSION_HMAC_KEY).get());}</li>
     * </ul>
     */
    @Test
    @DisplayName("wire_version=2 登录成功并下发会话密钥")
    void acceptWireV2AndBindCryptoKey() {
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(
                PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                        .setUsername("alice").setPassword("pwd").setWireVersion(2).build(),
                ch,
                "127.0.0.1");
        assertEquals(PlayerSessionService.RET_OK, rsp.getRetcode());
        assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());
        assertFalse(rsp.getSessionCryptoKey().isBlank());
        assertEquals("tok-1001", rsp.getSessionToken());
        assertNotNull(ch.attr(KcpSessionCryptoCodec.SESSION_KEY).get());
        assertNotNull(ch.attr(ProtocolHmacSigner.SESSION_HMAC_KEY).get());
        assertTrue(rsp.getPlayerInfo().getUid() > 0);
    }

    @Test
    @DisplayName("wire v3 上报触屏输入后回填推荐布局")
    void acceptTouchInputAndRecommendMobileLayout() {
        EmbeddedChannel ch = new EmbeddedChannel();
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(
                PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                        .setUsername("alice").setPassword("pwd").setWireVersion(3)
                        .setInputMethods(cn.itcast.demo.mylunarcore.net.InputCapability.TOUCH)
                        .setDeviceId("android-phone")
                        .build(),
                ch,
                "127.0.0.1");
        assertEquals(PlayerSessionService.RET_OK, rsp.getRetcode());
        assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());
        assertEquals(cn.itcast.demo.mylunarcore.net.InputCapability.TOUCH, rsp.getInputMethods());
        assertEquals(cn.itcast.demo.mylunarcore.net.InputCapability.LAYOUT_MOBILE_TOUCH, rsp.getRecommendedLayoutId());
    }

    /**
     * 验证点：限流优先于账密校验仍返回协议版本。
     * <p>测试方法 {@code rateLimitedStillReturnsWireVersion}：
     * <ul>
     *   <li>{@code when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(false);}</li>
     *   <li>{@code assertEquals(PlayerSessionService.RET_RATE_LIMITED, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("限流优先于账密校验仍返回协议版本")
    void rateLimitedStillReturnsWireVersion() {
        when(rateLimiter.tryAcquire(anyString(), anyString())).thenReturn(false);
        PlayerSessionProto.PlayerLoginScRsp rsp = sessionService.handleLogin(
                PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                        .setUsername("alice").setPassword("pwd").setWireVersion(2).build(),
                new EmbeddedChannel(),
                "127.0.0.1");
        assertEquals(PlayerSessionService.RET_RATE_LIMITED, rsp.getRetcode());
        assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, rsp.getWireVersion());
    }
}
