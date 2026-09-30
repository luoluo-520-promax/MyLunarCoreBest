package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.tools.GuildWarMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公会战全流程：匹配 → 报分 → 排行；并校验 Mock 客户端样本报文可生成。
 */
@DisplayName("公会战匹配报分结算集成")
class GuildWarFlowIntegrationTest {

    private GuildWarService war;
    private GuildService guild;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.ResultSetExtractor.class), any()))
                .thenReturn(null);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        guild = mock(GuildService.class);
        when(guild.findGuildIdByPlayer(1)).thenReturn(100L);
        when(guild.findGuildIdByPlayer(2)).thenReturn(200L);
        when(guild.findGuildIdByPlayer(3)).thenReturn(300L);
        war = new GuildWarService(jdbc, guild);
    }

    @Test
    @DisplayName("匹配→报分→排行闭环 + Mock 报文样本")
    void fullFlowAndMockPackets() {
        GuildWarService.SeasonInfo season = war.ensureCurrentSeason();
        GuildWarService.OpResult m1 = war.requestMatch(1);
        assertTrue(m1.success());
        GuildWarService.OpResult m2 = war.requestMatch(2);
        assertTrue(m2.success());
        assertEquals("MATCHED", m2.match().status());

        long matchId = m2.match().matchId();
        GuildWarService.OpResult report = war.reportBattleResult(1, matchId, 12, 5);
        assertTrue(report.success());
        assertEquals("SETTLED", report.match().status());

        List<GuildWarService.RankEntry> ranks = war.seasonRank(season.seasonId(), 10);
        assertFalse(ranks.isEmpty());
        assertTrue(ranks.get(0).points() > 0);

        Map<String, String> samples = GuildWarMockClient.samplePackets(matchId, season.seasonId());
        assertEquals(3, samples.size());
        assertTrue(samples.keySet().stream().anyMatch(k -> k.startsWith(String.valueOf(CmdIds.GUILD_WAR_MATCH_CS_REQ))));
        assertTrue(samples.keySet().stream().anyMatch(k -> k.startsWith(String.valueOf(CmdIds.GUILD_WAR_REPORT_CS_REQ))));
        assertTrue(samples.keySet().stream().anyMatch(k -> k.startsWith(String.valueOf(CmdIds.GUILD_WAR_RANK_CS_REQ))));
        // MatchCsReq 为空 message，Base64 可能为空串；Report/Rank 必须有载荷
        String reportKey = samples.keySet().stream()
                .filter(k -> k.startsWith(String.valueOf(CmdIds.GUILD_WAR_REPORT_CS_REQ)))
                .findFirst().orElseThrow();
        String rankKey = samples.keySet().stream()
                .filter(k -> k.startsWith(String.valueOf(CmdIds.GUILD_WAR_RANK_CS_REQ)))
                .findFirst().orElseThrow();
        assertFalse(samples.get(reportKey).isBlank());
        assertFalse(samples.get(rankKey).isBlank());
    }
}
