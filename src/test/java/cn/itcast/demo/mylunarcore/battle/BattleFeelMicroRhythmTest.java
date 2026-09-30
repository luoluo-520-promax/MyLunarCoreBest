package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HitStop / InputBuffer / Delta 手感单元")
class BattleFeelMicroRhythmTest {

    @Test
    void hitStopShouldDifferBySkillGrade() {
        HitStopComposer.HitStopPlan basic = HitStopComposer.compose(1, false, false, 80);
        HitStopComposer.HitStopPlan ult = HitStopComposer.compose(3001, false, false, 500);
        assertTrue(ult.totalMs() > basic.totalMs());
        assertFalse(ult.frames().isEmpty());
    }

    @Test
    void inputBufferShouldQueueUntilActionWindow() {
        PlayerInputBufferService buf = new PlayerInputBufferService();
        long battleId = 9L;
        long now = System.currentTimeMillis();
        buf.openActionWindow(battleId, now + 200);
        boolean queued = buf.enqueueOrExecuteNow(battleId, 1, 1001, 1, 2, 1, List.of(2), now);
        assertTrue(queued);
        PlayerInputBufferService.BufferedInput polled = buf.poll(battleId, 1);
        assertEquals(2, polled.skillId());
        assertEquals(PlayerInputBufferService.CancelTier.SKILL, polled.tier());
    }

    @Test
    void fxComposerShouldEmbedHitStop() {
        BattleFxComposer.FxHint fx = BattleFxComposer.compose(-900, true, 3001, true, 7);
        assertTrue(fx.hitStop().totalMs() > 0);
        assertEquals(7, fx.targetEntityId());
    }
}
