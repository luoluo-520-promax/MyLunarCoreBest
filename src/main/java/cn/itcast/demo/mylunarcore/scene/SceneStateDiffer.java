package cn.itcast.demo.mylunarcore.scene;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景实体增量同步：记录上次下发快照，仅标记变更字段。
 */
@Component
public class SceneStateDiffer {

    /** bit0=x bit1=y bit2=z bit3=rotY bit4=hp/state bit5=anim */
    public static final int FIELD_X = 1;
    public static final int FIELD_Y = 1 << 1;
    public static final int FIELD_Z = 1 << 2;
    public static final int FIELD_ROT = 1 << 3;
    public static final int FIELD_STATE = 1 << 4;
    public static final int FIELD_ANIM = 1 << 5;
    public static final int FIELD_ALL = FIELD_X | FIELD_Y | FIELD_Z | FIELD_ROT | FIELD_STATE | FIELD_ANIM;

    public record EntitySnapshot(float x, float y, float z, float rotY, int stateFlags) {
    }

    public record Diff(int entityId, int changedMask, float dx, float dy, float dz,
                       float x, float y, float z, float rotY, int stateFlags) {
        public boolean isFull() {
            return (changedMask & FIELD_ALL) == FIELD_ALL;
        }

        public boolean positionOnly() {
            return (changedMask & ~(FIELD_X | FIELD_Y | FIELD_Z)) == 0
                    && (changedMask & (FIELD_X | FIELD_Y | FIELD_Z)) != 0;
        }
    }

    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, EntitySnapshot>> lastSent =
            new ConcurrentHashMap<>();

    public Diff diffAndRemember(long viewerUid, int entityId, float x, float y, float z,
                                float rotY, int stateFlags) {
        ConcurrentHashMap<Integer, EntitySnapshot> map =
                lastSent.computeIfAbsent(viewerUid, k -> new ConcurrentHashMap<>());
        EntitySnapshot prev = map.get(entityId);
        EntitySnapshot next = new EntitySnapshot(x, y, z, rotY, stateFlags);
        if (prev == null) {
            map.put(entityId, next);
            return new Diff(entityId, FIELD_ALL, 0, 0, 0, x, y, z, rotY, stateFlags);
        }
        int mask = 0;
        float dx = 0, dy = 0, dz = 0;
        if (Math.abs(x - prev.x()) > 0.01f) {
            mask |= FIELD_X;
            dx = x - prev.x();
        }
        if (Math.abs(y - prev.y()) > 0.01f) {
            mask |= FIELD_Y;
            dy = y - prev.y();
        }
        if (Math.abs(z - prev.z()) > 0.01f) {
            mask |= FIELD_Z;
            dz = z - prev.z();
        }
        if (Math.abs(rotY - prev.rotY()) > 0.5f) {
            mask |= FIELD_ROT;
        }
        if (stateFlags != prev.stateFlags()) {
            mask |= FIELD_STATE;
        }
        if (mask == 0) {
            return null;
        }
        map.put(entityId, next);
        return new Diff(entityId, mask, dx, dy, dz, x, y, z, rotY, stateFlags);
    }

    public void clearViewer(long viewerUid) {
        lastSent.remove(viewerUid);
    }

    public void clearEntity(int entityId) {
        for (Map.Entry<Long, ConcurrentHashMap<Integer, EntitySnapshot>> e : lastSent.entrySet()) {
            e.getValue().remove(entityId);
        }
    }
}
