package cn.itcast.demo.mylunarcore.arena;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ArenaRatingService} ELO 结算单测。
 * mock JDBC：query 返回 null 触发内存新建评分；update/queryForList 空实现即可。
 */
@DisplayName("竞技场 ELO")
class ArenaRatingServiceTest {

    private ArenaRatingService arena;

    /** 注入不会读到真实行的 JdbcTemplate，评分在内存侧创建与更新。 */
    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(null); // 视为库中无记录 → getOrCreate 用默认分
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        arena = new ArenaRatingService(jdbc);
    }

    /**
     * 玩家 1 胜 玩家 2：胜者 rating 不应低于赛前；
     * 败者赛后分相对「刚 getOrCreate(2) 的当前值」不会无故暴涨（宽松上界 +50）。
     */
    @Test
    @DisplayName("胜者评分应上升")
    void winnerRatingIncreases() {
        int before = arena.getOrCreate(1).rating();
        ArenaRatingService.MatchResult r = arena.settleMatch(1, 2); // 1 胜 2 负
        assertTrue(r.winnerRatingAfter() >= before);
        assertTrue(r.loserRatingAfter() <= arena.getOrCreate(2).rating() + 50);
    }
}
