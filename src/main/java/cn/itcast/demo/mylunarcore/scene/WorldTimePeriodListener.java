package cn.itcast.demo.mylunarcore.scene;

/**
 * 箱庭时段切换回调：供 NPC 作息等玩法主动响应，而非仅广播客户端天空盒。
 */
@FunctionalInterface
public interface WorldTimePeriodListener {

    void onPeriodChanged(WorldTimeService.Period previous, WorldTimeService.Period next);
}
