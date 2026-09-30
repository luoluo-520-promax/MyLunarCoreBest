package cn.itcast.demo.mylunarcore.battle;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端权威 Hit-stop（顿帧）合成：轻击/重击/战技/终结技冻结时长不同，
 * 多人共战必须同源下发，禁止客户端本地估算。
 */
public final class HitStopComposer {

    private HitStopComposer() {}

    public record HitStopSpec(int triggerFrame, int durationMs) {}

    public record HitStopPlan(List<HitStopSpec> frames, int totalMs) {
        public static HitStopPlan empty() {
            return new HitStopPlan(List.of(), 0);
        }
    }

    /**
     * @param skillId   技能 ID（启发式分级）
     * @param critical  是否暴击
     * @param kill      是否击杀
     * @param damage    展示伤害
     */
    public static HitStopPlan compose(int skillId, boolean critical, boolean kill, int damage) {
        List<HitStopSpec> frames = new ArrayList<>(2);
        int grade = classify(skillId, damage);
        // 首段命中顿帧
        int firstMs = switch (grade) {
            case 4 -> 160; // 终结技
            case 3 -> 120; // 战技
            case 2 -> 90;  // 重击
            default -> 50; // 轻击/普攻
        };
        if (critical) {
            firstMs += 30;
        }
        if (kill) {
            firstMs += 40;
        }
        frames.add(new HitStopSpec(0, firstMs));
        // 终结技/击杀追加第二段冻结，强化「咬合」感
        if (grade >= 4 || kill) {
            frames.add(new HitStopSpec(15, kill ? 120 : 80));
        }
        int total = 0;
        for (HitStopSpec s : frames) {
            total += Math.max(0, s.durationMs());
        }
        return new HitStopPlan(List.copyOf(frames), total);
    }

    /** 1轻击 2重击 3战技 4终结技 */
    static int classify(int skillId, int damage) {
        if (skillId >= 3000 || skillId == 3) {
            return 4;
        }
        if (skillId >= 1000 || skillId == 2) {
            return 3;
        }
        if (damage >= 400 || skillId == 1) {
            return 2;
        }
        return 1;
    }
}
