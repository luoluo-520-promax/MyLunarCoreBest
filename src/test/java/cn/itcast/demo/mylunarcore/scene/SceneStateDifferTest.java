package cn.itcast.demo.mylunarcore.scene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("SceneStateDiffer 增量同步")
class SceneStateDifferTest {

    @Test
    @DisplayName("无变化返回 null，有位移返回 diff")
    void diffOnlyOnChange() {
        SceneStateDiffer differ = new SceneStateDiffer();
        assertNotNull(differ.diffAndRemember(1L, 10, 0, 0, 0, 0, 0));
        assertNull(differ.diffAndRemember(1L, 10, 0, 0, 0, 0, 0));
        SceneStateDiffer.Diff d = differ.diffAndRemember(1L, 10, 1.0f, 0, 0, 0, 0);
        assertNotNull(d);
        assertEquals(SceneStateDiffer.FIELD_X, d.changedMask() & SceneStateDiffer.FIELD_X);
    }
}
