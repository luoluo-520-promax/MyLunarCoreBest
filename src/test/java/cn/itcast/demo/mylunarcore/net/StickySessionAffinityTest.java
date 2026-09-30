package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StickySessionAffinity UID 粘性")
class StickySessionAffinityTest {

    @Test
    void sameUidMapsStable() {
        assertEquals(StickySessionAffinity.nodeIndexForUid(10001, 4),
                StickySessionAffinity.nodeIndexForUid(10001, 4));
        assertTrue(StickySessionAffinity.isStickyOwner(7, StickySessionAffinity.nodeIndexForUid(7, 3), 3));
    }
}
