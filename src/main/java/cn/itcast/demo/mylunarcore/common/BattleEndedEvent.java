// 战斗结束相关事件类型所在包
package cn.itcast.demo.mylunarcore.common;

/**
 * 战斗从运行时移除时发布（正常结算或中途退出等）。
 */
// record：不可变数据载体，自动生成构造器、getter、equals/hashCode
public record BattleEndedEvent(
        long battleId,           // 战斗实例唯一 id
        int playerId,            // 参与玩家 id
        int endStatus,           // 结束状态码（胜/负/退出等，由业务定义）
        String reason,           // 结束原因描述或枚举名
        long endTimeSeconds      // 结束时间（Unix 秒）
) {
}
