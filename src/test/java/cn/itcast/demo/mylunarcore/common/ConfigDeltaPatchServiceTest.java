package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDeltaPatchServiceTest {

    @Test
    void applyDeltaReplacesJsonPath() {
        ConfigDeltaPatchService svc = new ConfigDeltaPatchService();
        svc.putSnapshot("banners", "{\"cost\":160,\"pity\":{\"max\":90}}");
        String json = svc.applyDelta("banners", Map.of("cost", 160, "pity.max", 80));
        assertTrue(json.contains("\"max\":80") || json.contains("80"));
        assertTrue(svc.toBinarySnapshot("banners").length > 10);
    }

    @Test
    void diffDetectsChangedField() {
        ConfigDeltaPatchService svc = new ConfigDeltaPatchService();
        Map<String, Object> delta = svc.diff("{\"a\":1}", "{\"a\":2,\"b\":3}");
        assertEquals(2, delta.get("a"));
        assertEquals(3, delta.get("b"));
    }
}
