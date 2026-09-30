package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("战斗打击感元数据")
class BattleFxComposerTest {

    @Test
    @DisplayName("击杀应给重震屏与慢放")
    void killShouldHeavyShake() {
        BattleFxComposer.FxHint fx = BattleFxComposer.compose(-900, true, 1001, true, 7);
        assertTrue(fx.kill());
        assertEquals(3, fx.cameraShake());
        assertTrue(fx.timeScale() < 1.0f);
        assertEquals("crit_burst", fx.damagePopupStyle());
        assertEquals(900, fx.displayDamage());
        assertEquals(7, fx.targetEntityId());
    }

    @Test
    @DisplayName("小伤害应小跳字")
    void smallHitShouldSmallPopup() {
        BattleFxComposer.FxHint fx = BattleFxComposer.compose(-20, false, 1, false, 2);
        assertEquals("small", fx.damagePopupStyle());
        assertEquals(0, fx.cameraShake());
        assertEquals(1.0f, fx.timeScale());
    }
}
