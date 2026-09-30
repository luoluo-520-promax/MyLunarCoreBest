package cn.itcast.demo.mylunarcore.minigame;

import java.util.Map;

/**
 * 活动小玩法实例钩子：onStart / onTick / onEnd / getReward。
 */
public interface MiniGameInstance {

    String miniGameId();

    void onStart(int playerId, Map<String, Object> context);

    /** @return true 表示玩法已结束 */
    boolean onTick(int playerId, Map<String, Object> tickPayload);

    void onEnd(int playerId, Map<String, Object> result);

    Map<String, Object> getReward(int playerId);
}
