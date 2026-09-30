package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.home.HomeVisitorLogService;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchPhase;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.InputCapability;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * wire v3 体验闭环：协议版本、输入能力、匹配不锁界面、材料反查、家园拜访、体力溢出常量。
 */
@DisplayName("体验闭环补齐（wire v3）")
class ExperienceLoopFillTest {

    @Test
    void wireVersionIsV3AndCompatKeepsV2Floor() {
        assertEquals(3, CmdIds.PROTOCOL_WIRE_VERSION);
        ProtocolCompatService compat = new ProtocolCompatService();
        assertFalse(compat.isCompatible(1));
        assertTrue(compat.isCompatible(2));
        assertTrue(compat.isCompatible(3));
    }

    @Test
    void inputCapabilityRecommendsMobileLayout() {
        int touch = InputCapability.TOUCH;
        assertEquals(InputCapability.LAYOUT_MOBILE_TOUCH,
                InputCapability.recommendedLayoutId(touch, "android-phone"));
        assertTrue((InputCapability.toFeatureBits(touch)
                & cn.itcast.demo.mylunarcore.net.ClientFeatureFlags.INPUT_TOUCH) != 0);
        assertEquals(InputCapability.LAYOUT_PC_KM,
                InputCapability.recommendedLayoutId(InputCapability.KEYBOARD_MOUSE, "windows"));
        assertEquals(InputCapability.LAYOUT_CONSOLE_GAMEPAD,
                InputCapability.recommendedLayoutId(InputCapability.GAMEPAD, "xbox"));
    }

    @Test
    void hallQueueDoesNotEnterMatchingUntilReadyCheck() {
        RoomService rooms = new RoomService();
        GameSessionManager sessions = mock(GameSessionManager.class);
        GameSession s1 = new GameSession(101, new EmbeddedChannel(), null);
        GameSession s2 = new GameSession(102, new EmbeddedChannel(), null);
        s1.setSessionState(PlayerSessionState.HALL);
        s2.setSessionState(PlayerSessionState.HALL);
        when(sessions.getOrNull(101L)).thenReturn(s1);
        when(sessions.getOrNull(102L)).thenReturn(s2);

        MatchmakingService mm = new MatchmakingService(
                rooms, sessions, new LunarCoreProperties(), new BusinessMetrics(new SimpleMeterRegistry()));
        assertTrue(mm.joinQueue(101, 1, 10, 100).success());
        assertEquals(PlayerSessionState.HALL, s1.getSessionState());
        assertEquals(MatchPhase.QUEUED, s1.getMatchPhase());

        assertTrue(mm.joinQueue(102, 1, 10, 100).success());
        mm.batchMatchTick();
        assertEquals(MatchPhase.READY_CHECK, s1.getMatchPhase());
        assertEquals(MatchPhase.READY_CHECK, s2.getMatchPhase());
        assertEquals(PlayerSessionState.HALL, s1.getSessionState());

        var room = rooms.findRoomByPlayer(101);
        assertTrue(room != null);
        var ready = mm.readyCheck(101, room.roomId(), true);
        assertTrue(ready.success());
        assertTrue(ready.locked());
        assertEquals(MatchPhase.LOCKED, s1.getMatchPhase());
        assertEquals(PlayerSessionState.MATCHING, s1.getSessionState());
    }

    @Test
    void itemSourceLookupReturnsStageShortcuts() {
        ItemApplicationService items = new ItemApplicationService(
                mock(cn.itcast.demo.mylunarcore.repo.ItemRepository.class));
        var sources = items.queryItemSources(201);
        assertFalse(sources.isEmpty());
        assertTrue(sources.get(0).stageId() > 0);
    }

    @Test
    void homeVisitorLogRecordsVisitAndUnread() {
        HomeVisitorLogService logs = new HomeVisitorLogService(null);
        assertTrue(logs.recordVisit(10, 20) != null);
        assertEquals(1, logs.unreadCount(10));
        assertEquals(1, logs.drainUnread(10).size());
        assertEquals(0, logs.unreadCount(10));
    }

    @Test
    void staminaOverflowDefaultsAndAutoWaitConstants() {
        assertEquals(0.30, StaminaOverflowService.DEFAULT_OVERFLOW_RATIO, 0.0001);
        assertEquals(240, StaminaOverflowService.DEFAULT_RESERVE_CAP);
        assertEquals(1_500L, BattleAutoService.BASE_AUTO_WAIT_MS);
        assertEquals(10_000L, MatchmakingService.READY_CHECK_TIMEOUT_MS);
        assertEquals(1020, CmdIds.SET_BATTLE_AUTO_CS_REQ);
        assertEquals(1045, CmdIds.MATCH_READY_CHECK_SC_RSP);
    }
}
