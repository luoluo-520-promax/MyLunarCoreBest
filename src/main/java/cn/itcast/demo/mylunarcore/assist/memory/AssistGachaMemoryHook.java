package cn.itcast.demo.mylunarcore.assist.memory;

import org.springframework.stereotype.Component;

/**
 * 抽卡结果写入玩家长期记忆（大保底/歪池等情感标签）。
 */
@Component
public class AssistGachaMemoryHook {

    private final AssistLongTermMemoryService longTermMemoryService;

    public AssistGachaMemoryHook(AssistLongTermMemoryService longTermMemoryService) {
        this.longTermMemoryService = longTermMemoryService;
    }

    public void onGachaResult(long uid, int bannerId, boolean hitTarget, boolean pityTriggered, String avatarName) {
        if (uid <= 0) {
            return;
        }
        if (hitTarget) {
            longTermMemoryService.record(uid, "gacha_win", "抽卡喜悦",
                    "在 banner " + bannerId + " 抽中「" + nullToEmpty(avatarName) + "」", 0.6);
            return;
        }
        String tag = pityTriggered ? "大保底沮丧" : "抽卡沮丧";
        longTermMemoryService.record(uid, "gacha_fail", tag,
                "banner " + bannerId + " 未出目标" + (pityTriggered ? "（触发保底/歪了）" : ""), 0.75);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
