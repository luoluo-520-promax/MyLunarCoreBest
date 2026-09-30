package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicZoneLineAllocatorTest {

    @Test
    void encodeAndDecodeLine() {
        int zoneId = DynamicZoneLineAllocator.encodeZoneId(1, 2, 3);
        assertEquals(3, DynamicZoneLineAllocator.lineIdOf(zoneId));
        assertEquals(10002, DynamicZoneLineAllocator.baseZoneIdOf(zoneId));
    }

    @Test
    void pickNextLineWhenPrimaryFull() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setMaxPlayers(1);
        props.getZone().setDynamicLineEnabled(true);
        props.getZone().setMaxLines(3);
        props.getZone().setAdaptiveCapacityEnabled(false);
        props.getZone().setActorMailboxEnabled(false);
        ZoneManager zones = new ZoneManager(new cn.itcast.demo.mylunarcore.center.SceneRegistry(),
                new SceneEntityIdAllocator(), props);
        DynamicZoneLineAllocator allocator = new DynamicZoneLineAllocator(zones, props);

        assertEquals(0, allocator.pickJoinableLine(1, 1, 1001L));
        zones.joinZone(1, 1, 0, 1001L, new SceneContext.ScenePos(0, 0, 0));
        Integer line = allocator.pickJoinableLine(1, 1, 1002L);
        assertNotNull(line);
        assertEquals(1, line);
    }

    @Test
    void returnNullWhenAllLinesFull() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setMaxPlayers(1);
        props.getZone().setDynamicLineEnabled(true);
        props.getZone().setMaxLines(2);
        props.getZone().setAdaptiveCapacityEnabled(false);
        props.getZone().setActorMailboxEnabled(false);
        ZoneManager zones = new ZoneManager(new cn.itcast.demo.mylunarcore.center.SceneRegistry(),
                new SceneEntityIdAllocator(), props);
        DynamicZoneLineAllocator allocator = new DynamicZoneLineAllocator(zones, props);
        zones.joinZone(1, 1, 0, 1L, new SceneContext.ScenePos(0, 0, 0));
        zones.joinZone(1, 1, 1, 2L, new SceneContext.ScenePos(0, 0, 0));
        assertNull(allocator.pickJoinableLine(1, 1, 3L));
    }

    @Test
    void packetPriorityMapsCmdRanges() {
        assertEquals(cn.itcast.demo.mylunarcore.net.PacketPriority.HIGH,
                cn.itcast.demo.mylunarcore.net.PacketPriority.ofCmdId(202));
        assertEquals(cn.itcast.demo.mylunarcore.net.PacketPriority.NORMAL,
                cn.itcast.demo.mylunarcore.net.PacketPriority.ofCmdId(320));
        assertTrue(cn.itcast.demo.mylunarcore.net.PacketPriority.ofCmdId(10)
                == cn.itcast.demo.mylunarcore.net.PacketPriority.LOW);
    }
}
