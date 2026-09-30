package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.character.AttributeCalculator;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OwnedLineupRecommendService 阵容推荐测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code OwnedLineupRecommendServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("OwnedLineupRecommendService 阵容推荐测试")
class OwnedLineupRecommendServiceTest {

    private static final Logger log = LoggerFactory.getLogger(OwnedLineupRecommendServiceTest.class);

    /**
     * 验证点：仅从已拥有角色中推荐，且不超过槽位数。
     * <p>测试方法 {@code shouldRecommendOnlyOwnedAvatars}：
     * <ul>
     *   <li>{@code when(session.getPlayerData()).thenReturn(data);}</li>
     *   <li>{@code when(sessions.getOrNull(7L)).thenReturn(session);}</li>
     *   <li>{@code when(usage.topN(50)).thenReturn(List.of(}</li>
     *   <li>{@code when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals(2, result.avatarIds().size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("仅从已拥有角色中推荐，且不超过槽位数")
    void shouldRecommendOnlyOwnedAvatars() {
        PlayerData data = new PlayerData();
        AvatarEntity a1 = new AvatarEntity();
        a1.setAvatarId(1001);
        a1.setLevel(40);
        AvatarEntity a2 = new AvatarEntity();
        a2.setAvatarId(1002);
        a2.setLevel(35);
        AvatarEntity a3 = new AvatarEntity();
        a3.setAvatarId(1003);
        a3.setLevel(30);
        data.setAvatars(List.of(a1, a2, a3));

        GameSessionManager sessions = mock(GameSessionManager.class);
        GameSession session = mock(GameSession.class);
        when(session.getPlayerData()).thenReturn(data);
        when(sessions.getOrNull(7L)).thenReturn(session);

        AvatarUsageStatsService usage = mock(AvatarUsageStatsService.class);
        when(usage.topN(50)).thenReturn(List.of(
                new AvatarUsageStatsService.UsageEntry(9999, 100, 50, 0.5, 0.5, 1),
                new AvatarUsageStatsService.UsageEntry(1002, 80, 40, 0.4, 0.5, 2),
                new AvatarUsageStatsService.UsageEntry(1001, 60, 30, 0.3, 0.5, 3)
        ));
        ActivityQueryService activities = mock(ActivityQueryService.class);
        when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());

        OwnedLineupRecommendService service = new OwnedLineupRecommendService(
                sessions, usage, new AttributeCalculator(), activities, featureRepo());
        OwnedLineupRecommendService.RecommendResult result = service.recommend(7L, 2, "meta");

        log.info("已拥有阵容推荐校验: retcode={}, avatarIds={}, reason={}, hintCount={}",
                result.retcode(), result.avatarIds(), result.reason(), result.relatedHints().size());
        assertEquals(0, result.retcode());
        assertEquals(2, result.avatarIds().size());
        assertFalse(result.avatarIds().contains(9999));
        assertTrue(result.avatarIds().stream().allMatch(id -> id == 1001 || id == 1002 || id == 1003));
        assertTrue(result.reason().contains("已拥有"));
    }

    /**
     * 验证点：无角色时应返回空推荐。
     * <p>测试方法 {@code emptyRosterShouldReturnEmpty}：
     * <ul>
     *   <li>{@code when(sessions.getOrNull(1L)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertTrue(result.avatarIds().isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("无角色时应返回空推荐")
    void emptyRosterShouldReturnEmpty() {
        GameSessionManager sessions = mock(GameSessionManager.class);
        when(sessions.getOrNull(1L)).thenReturn(null);
        OwnedLineupRecommendService service = new OwnedLineupRecommendService(
                sessions, mock(AvatarUsageStatsService.class), new AttributeCalculator(),
                mock(ActivityQueryService.class), featureRepo());
        OwnedLineupRecommendService.RecommendResult result = service.recommend(1L, 4, "general");
        log.info("空阵容推荐校验: retcode={}, avatarIds={}, reason={}",
                result.retcode(), result.avatarIds(), result.reason());
        assertEquals(0, result.retcode());
        assertTrue(result.avatarIds().isEmpty());
    }

    /**
     * 验证点：pairScore 对同角色应返回负无穷。
     * <p>测试方法 {@code pairScoreRejectsSameAvatar}：
     * <ul>
     *   <li>{@code assertEquals(Double.NEGATIVE_INFINITY, same);}</li>
     * </ul>
     */
    @Test
    @DisplayName("pairScore 对同角色应返回负无穷")
    void pairScoreRejectsSameAvatar() {
        OwnedLineupRecommendService.ScoredAvatar a =
                new OwnedLineupRecommendService.ScoredAvatar(1, 10, 100, 1.0);
        double same = OwnedLineupRecommendService.pairScore(a, a);
        log.info("同角色 pairScore 校验: avatarId={}, score={}", a.avatarId(), same);
        assertEquals(Double.NEGATIVE_INFINITY, same);
    }

    /**
     * 验证点：敌人弱标签应提高克制角色排序权重。
     * <p>测试方法 {@code enemyTagsShouldBoostCounterAvatars}：
     * <ul>
     *   <li>{@code when(session.getPlayerData()).thenReturn(data);}</li>
     *   <li>{@code when(sessions.getOrNull(8L)).thenReturn(session);}</li>
     *   <li>{@code when(usage.topN(50)).thenReturn(List.of());}</li>
     *   <li>{@code when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());}</li>
     *   <li>{@code assertEquals(List.of(1002), result.avatarIds());}</li>
     *   <li>{@code assertTrue(result.reason().contains("敌人标签"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("敌人弱标签应提高克制角色排序权重")
    void enemyTagsShouldBoostCounterAvatars() {
        PlayerData data = new PlayerData();
        AvatarEntity a1 = new AvatarEntity();
        a1.setAvatarId(1001);
        a1.setLevel(20);
        AvatarEntity a2 = new AvatarEntity();
        a2.setAvatarId(1002);
        a2.setLevel(20);
        data.setAvatars(List.of(a1, a2));

        GameSessionManager sessions = mock(GameSessionManager.class);
        GameSession session = mock(GameSession.class);
        when(session.getPlayerData()).thenReturn(data);
        when(sessions.getOrNull(8L)).thenReturn(session);
        AvatarUsageStatsService usage = mock(AvatarUsageStatsService.class);
        when(usage.topN(50)).thenReturn(List.of());
        ActivityQueryService activities = mock(ActivityQueryService.class);
        when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());

        OwnedLineupRecommendService service = new OwnedLineupRecommendService(
                sessions, usage, new AttributeCalculator(), activities, featureRepo());
        OwnedLineupRecommendService.RecommendResult result =
                service.recommend(8L, 1, "challenge", List.of("break", "quantum"));
        log.info("敌情克制配队校验: avatarIds={}, reason={}", result.avatarIds(), result.reason());
        assertEquals(List.of(1002), result.avatarIds());
        assertTrue(result.reason().contains("敌人标签"));
    }

    private static AssistFeatureContentRepository featureRepo() {
        AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
        when(repo.current()).thenReturn(new AssistFeatureContent(
                1,
                List.of(),
                List.of(),
                List.of(),
                Map.of(
                        "1001", List.of("ice", "slow"),
                        "1002", List.of("break", "quantum")
                ),
                List.of(),
                List.of()
        ));
        return repo;
    }
}
