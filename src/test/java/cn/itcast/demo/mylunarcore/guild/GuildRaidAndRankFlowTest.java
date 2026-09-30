package cn.itcast.demo.mylunarcore.guild;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 公会讨伐排期 + 贡献排行预留接口流程。
 */
@DisplayName("公会 Raid 与贡献榜业务流程")
class GuildRaidAndRankFlowTest {

    @Test
    @DisplayName("排期 → 查询有效场次 → 开场前可取消")
    void scheduleListAndCancelBeforeOpen() {
        GuildRaidScheduler scheduler = new InMemoryGuildRaidScheduler();
        Instant open = Instant.now().plus(2, ChronoUnit.HOURS);

        Optional<GuildRaidScheduler.RaidSlot> slot = scheduler.schedule(
                new GuildRaidScheduler.ScheduleRequest(88L, "guild_raid", open));
        assertTrue(slot.isPresent());
        assertEquals(88L, slot.get().guildId());
        assertEquals("guild_raid", slot.get().raidType());

        List<GuildRaidScheduler.RaidSlot> active = scheduler.listActive(88L);
        assertEquals(1, active.size());
        assertEquals(slot.get().raidId(), active.get(0).raidId());

        assertTrue(scheduler.cancel(slot.get().raidId(), 88L));
        assertTrue(scheduler.listActive(88L).isEmpty());
    }

    @Test
    @DisplayName("非法公会 / 错公会取消应失败")
    void invalidScheduleOrCancelShouldFail() {
        GuildRaidScheduler scheduler = new InMemoryGuildRaidScheduler();
        assertTrue(scheduler.schedule(new GuildRaidScheduler.ScheduleRequest(0, "x", null)).isEmpty());

        GuildRaidScheduler.RaidSlot slot = scheduler.schedule(
                new GuildRaidScheduler.ScheduleRequest(1L, "boss", Instant.now().plusSeconds(3600))).orElseThrow();
        assertFalse(scheduler.cancel(slot.raidId(), 999L));
    }

    @Test
    @DisplayName("DB 不可用时贡献榜回退内存累计")
    void contributionRankFallsBackToMemory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));
        GuildContributionRank rank = new DbGuildContributionRank(jdbc);

        rank.recordContribution(10L, 101, 50);
        rank.recordContribution(10L, 102, 80);
        rank.recordContribution(10L, 101, 20);

        List<GuildContributionRank.RankEntry> top = rank.weeklyRank(10L, 10);
        assertEquals(2, top.size());
        assertEquals(102, top.get(0).playerId());
        assertEquals(80, top.get(0).contribution());
        assertEquals(1, top.get(0).rank());
        assertEquals(101, top.get(1).playerId());
        assertEquals(70, top.get(1).contribution());
    }
}
