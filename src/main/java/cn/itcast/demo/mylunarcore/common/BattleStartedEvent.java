// 战斗开始相关事件类型所在包
package cn.itcast.demo.mylunarcore.common;

/**
 * 战斗在内存中创建并注册完成时发布的事件。
 */
// record：战斗开始时携带的只读快照，供监听器打日志或统计
public record BattleStartedEvent(
        long battleId,           // 新战斗实例 id
        int playerId,            // 发起或所属玩家 id
        int battleStageId,       // 关卡/舞台配置 id
        int lineupId,            // 使用的编队预设 id
        int waveCount,           // 波次数量
        long startTimeSeconds    // 开始时间（Unix 秒）
) {
}
