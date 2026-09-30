package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CombatAttributeSheet 增量属性")
class CombatAttributeSheetTest {

    @Test
    @DisplayName("Buff 变化仅增量重算")
    void incrementalBuffUpdate() {
        CombatAttributeSheet sheet = new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(1000, 100, 50, 100, 5, 50));
        CombatAttributeSheet.BuffDelta atkUp = new CombatAttributeSheet.BuffDelta(20, 0, 0, 10, 0, 0, 0);
        sheet.onBuffStacksChanged(1001, 2, atkUp);
        CombatAttributeSheet.Snapshot s1 = sheet.resolve();
        // base 100 + 40 flat, * 1.2 pct = 168
        assertEquals(168, s1.atk());
        sheet.onBuffStacksChanged(1001, 1, atkUp);
        CombatAttributeSheet.Snapshot s2 = sheet.resolve();
        assertEquals(132, s2.atk()); // 100+20 * 1.1
        assertTrue(s2.atk() < s1.atk());
    }
}
