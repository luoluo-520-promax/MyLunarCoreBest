package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("扫荡与多倍体力")
class SweepServiceTest {

    private SweepService sweep;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("no db"));
        StaminaService stamina = mock(StaminaService.class);
        when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                List.of(50), 60));
        when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(200));
        when(stamina.snapshot(anyInt())).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L, 0, 40));
        EncounterConfigRepository encounters = mock(EncounterConfigRepository.class);
        when(encounters.current()).thenReturn(new EncounterConfig(1,
                List.of(),
                List.of(new EncounterConfig.DropEntry(2001, 2, null, null)),
                10, 0, 0, 0, 5.0));
        RewardDistributor rewards = mock(RewardDistributor.class);
        when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                .thenReturn(List.of(new RewardDistributor.GrantedItem(2001, 6)));
        sweep = new SweepService(jdbc, stamina, encounters, rewards);
    }

    @Test
    @DisplayName("未通关不可扫荡；通关后可 3 倍扫荡")
    void requireClearThenSweep() {
        assertFalse(sweep.info(11, 100).unlocked());
        SweepService.SweepResult locked = sweep.sweep(11, 100, 1);
        assertEquals(3, locked.retcode());

        sweep.recordClear(11, 100, 8);
        assertTrue(sweep.info(11, 100).unlocked());
        assertEquals(8, sweep.info(11, 100).bestTurnCount());

        SweepService.SweepResult badMul = sweep.sweep(11, 100, 9);
        assertEquals(4, badMul.retcode());

        SweepService.SweepResult ok = sweep.sweep(11, 100, 3);
        assertTrue(ok.ok());
        assertEquals(0, ok.retcode());
        assertEquals(3, ok.multiplier());
        assertEquals(20 * 3, ok.staminaCost());
        assertFalse(ok.rewards().isEmpty());
    }

    @Test
    @DisplayName("一键连续扫荡按顺序执行并汇总奖励")
    void batchSweepAggregatesRewards() {
        SweepService.BatchSweepResult locked = sweep.batchSweep(11, List.of(new SweepService.BatchSweepStep(100, 2)), 40);
        assertEquals(3, locked.retcode());

        sweep.recordClear(11, 100, 8);
        SweepService.BatchSweepResult mismatch = sweep.batchSweep(11,
                List.of(new SweepService.BatchSweepStep(100, 2)), 99);
        assertEquals(2, mismatch.retcode());

        SweepService.BatchSweepResult ok = sweep.batchSweep(11,
                List.of(new SweepService.BatchSweepStep(100, 2)), 40);
        assertTrue(ok.ok());
        assertEquals(2, ok.completedTimes());
        assertEquals(40, ok.staminaCost());
    }
}
