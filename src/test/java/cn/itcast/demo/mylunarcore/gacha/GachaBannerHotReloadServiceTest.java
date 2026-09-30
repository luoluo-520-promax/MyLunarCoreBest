package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GachaBannerHotReloadService 卡池热更服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaBannerHotReloadServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaBannerHotReloadService 卡池热更服务测试")
class GachaBannerHotReloadServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GachaBannerHotReloadServiceTest.class);

    private GachaConfigService configService;
    private GameSessionManager sessionManager;
    private GachaNettyService gachaNettyService;
    private GachaBannerHotReloadService hotReloadService;

    @BeforeEach
    void setUp() {
        configService = mock(GachaConfigService.class);
        sessionManager = mock(GameSessionManager.class);
        gachaNettyService = mock(GachaNettyService.class);
        hotReloadService = new GachaBannerHotReloadService(configService, sessionManager, gachaNettyService);
        log.info("卡池热更服务初始化: bannersPath=data/Banners.json");
    }

    @AfterEach
    void tearDown() {
        hotReloadService.stop();
        log.info("卡池热更服务已停止");
    }

    /**
     * 验证点：tick 检测到文件变更且 reload 成功时应向活跃会话推送通知。
     * <p>测试方法 {@code tickShouldReloadAndPushToActiveSessions}：
     * <ul>
     *   <li>{@code when(configService.reload()).thenReturn(true);}</li>
     *   <li>{@code when(activeChannel.isActive()).thenReturn(true);}</li>
     *   <li>{@code when(session.getChannel()).thenReturn(activeChannel);}</li>
     *   <li>{@code when(sessionManager.snapshotSessions()).thenReturn(List.of(session));}</li>
     *   <li>{@code verify(configService).reload();}</li>
     *   <li>{@code verify(gachaNettyService).pushBannerUpdateNotify(activeChannel);}</li>
     * </ul>
     */
    @Test
    @DisplayName("tick 检测到文件变更且 reload 成功时应向活跃会话推送通知")
    void tickShouldReloadAndPushToActiveSessions() throws Exception {
        when(configService.reload()).thenReturn(true);

        Channel activeChannel = mock(Channel.class);
        when(activeChannel.isActive()).thenReturn(true);
        GameSession session = mock(GameSession.class);
        when(session.getChannel()).thenReturn(activeChannel);
        when(sessionManager.snapshotSessions()).thenReturn(List.of(session));

        setLastSeenModifiedMillis(0L);
        invokeTick();

        verify(configService).reload();
        verify(gachaNettyService).pushBannerUpdateNotify(activeChannel);
        log.info("热更 tick 校验: reloadCalled=true, activeSessionCount=1, pushCalled=true");
    }

    /**
     * 验证点：tick 在 reload 失败时不应推送通知。
     * <p>测试方法 {@code tickReloadFailureShouldNotPush}：
     * <ul>
     *   <li>{@code when(configService.reload()).thenReturn(false);}</li>
     *   <li>{@code when(activeChannel.isActive()).thenReturn(true);}</li>
     *   <li>{@code when(session.getChannel()).thenReturn(activeChannel);}</li>
     *   <li>{@code when(sessionManager.snapshotSessions()).thenReturn(List.of(session));}</li>
     *   <li>{@code verify(configService).reload();}</li>
     *   <li>{@code verify(gachaNettyService, never()).pushBannerUpdateNotify(activeChannel);}</li>
     * </ul>
     */
    @Test
    @DisplayName("tick 在 reload 失败时不应推送通知")
    void tickReloadFailureShouldNotPush() throws Exception {
        when(configService.reload()).thenReturn(false);

        Channel activeChannel = mock(Channel.class);
        when(activeChannel.isActive()).thenReturn(true);
        GameSession session = mock(GameSession.class);
        when(session.getChannel()).thenReturn(activeChannel);
        when(sessionManager.snapshotSessions()).thenReturn(List.of(session));

        setLastSeenModifiedMillis(0L);
        invokeTick();

        verify(configService).reload();
        verify(gachaNettyService, never()).pushBannerUpdateNotify(activeChannel);
        log.info("热更失败容错校验: reloadOk=false, pushCalled=false, sessionCount=1");
    }

    private void invokeTick() throws Exception {
        Method tick = GachaBannerHotReloadService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(hotReloadService);
    }

    private void setLastSeenModifiedMillis(long value) throws Exception {
        java.lang.reflect.Field field = GachaBannerHotReloadService.class.getDeclaredField("lastSeenModifiedMillis");
        field.setAccessible(true);
        field.setLong(hotReloadService, value);
    }
}
