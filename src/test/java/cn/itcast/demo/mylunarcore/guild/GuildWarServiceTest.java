package cn.itcast.demo.mylunarcore.guild;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公会战闭环。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GuildWarServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("公会战闭环")
class GuildWarServiceTest {

    private GuildWarService war;
    private GuildService guild;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(java.util.List.of());
        guild = mock(GuildService.class);
        when(guild.findGuildIdByPlayer(1)).thenReturn(100L);
        when(guild.findGuildIdByPlayer(2)).thenReturn(200L);
        war = new GuildWarService(jdbc, guild);
    }

    /**
     * 验证点：匹配与报分应更新积分榜。
     * <p>测试方法 {@code matchAndReport}：
     * <ul>
     *   <li>{@code assertTrue(m1.success());}</li>
     *   <li>{@code assertTrue(m2.success());}</li>
     *   <li>{@code assertEquals("MATCHED", m2.match().status());}</li>
     *   <li>{@code assertTrue(report.success());}</li>
     *   <li>{@code assertEquals("SETTLED", report.match().status());}</li>
     *   <li>{@code assertTrue(war.seasonRank(report.match().seasonId(), 10).size() >= 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("匹配与报分应更新积分榜")
    void matchAndReport() {
        GuildWarService.OpResult m1 = war.requestMatch(1);
        assertTrue(m1.success());
        GuildWarService.OpResult m2 = war.requestMatch(2);
        assertTrue(m2.success());
        assertEquals("MATCHED", m2.match().status());

        GuildWarService.OpResult report = war.reportBattleResult(1, m2.match().matchId(), 10, 3);
        assertTrue(report.success());
        assertEquals("SETTLED", report.match().status());
        assertTrue(war.seasonRank(report.match().seasonId(), 10).size() >= 1);
    }
}
