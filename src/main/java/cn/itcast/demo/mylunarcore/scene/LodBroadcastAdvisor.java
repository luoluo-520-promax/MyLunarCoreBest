package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

/**
 * LOD 广播策略：近处完整属性，远处仅类型+简化位置。
 */
@Component
public class LodBroadcastAdvisor {

    public enum LodTier {
        /** 完整：位置+朝向+动画+状态 */
        FULL(0),
        /** 简化：位置+类型，无朝向/动画 */
        SIMPLIFIED(1),
        /** 幽灵/宠物远距：仅粗位置 */
        PROXY(2);

        private final int wire;

        LodTier(int wire) {
            this.wire = wire;
        }

        public int wire() {
            return wire;
        }
    }

    private final LunarCoreProperties properties;

    public LodBroadcastAdvisor(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public LodTier resolve(float viewerX, float viewerZ, float entityX, float entityZ, boolean isPetOrPhantom) {
        float dx = viewerX - entityX;
        float dz = viewerZ - entityZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);
        float near = properties.getZone().getLodNearRadius();
        float mid = properties.getZone().getLodMidRadius();
        if (dist <= near) {
            return LodTier.FULL;
        }
        if (dist <= mid) {
            return isPetOrPhantom ? LodTier.PROXY : LodTier.SIMPLIFIED;
        }
        return LodTier.PROXY;
    }

    /** 是否应省略朝向/动画字段。 */
    public boolean omitOrientation(LodTier tier) {
        return tier != LodTier.FULL;
    }

    /** 是否应省略详细状态。 */
    public boolean omitDetailedState(LodTier tier) {
        return tier == LodTier.PROXY;
    }
}
