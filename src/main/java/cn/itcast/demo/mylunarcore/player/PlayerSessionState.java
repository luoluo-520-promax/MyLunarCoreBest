// 玩家会话玩法状态枚举：标识当前连接处于哪个游戏子系统
package cn.itcast.demo.mylunarcore.player;

/**
 * 在线玩家当前所处的玩法状态。
 * <p>
 * 存储在 {@link GameSession#sessionState} 中，业务模块（场景、战斗、匹配等）
 * 在进入/离开对应玩法时更新，供路由、同步范围控制与运维监控使用。
 * 默认值为 {@link #HALL}，表示玩家刚登录尚未进入具体玩法。
 */
public enum PlayerSessionState {

    /** 大厅状态：玩家已登录，处于主界面/社交界面，未进入场景或副本 */
    HALL,

    /** 场景状态：玩家正在开放世界或副本场景中移动、交互 */
    SCENE,

    /** 战斗状态：玩家处于常规回合制/即时战斗实例中 */
    BATTLE,

    /** 挑战状态：玩家在进行忘却之庭、虚构叙事等挑战玩法 */
    CHALLENGE,

    /** 模拟宇宙（Rogue）状态：玩家处于肉鸽类玩法流程中 */
    ROGUE,

    /** 匹配状态：玩家正在匹配队列中等待组队或 PvP 对手 */
    MATCHING,

    /** 主线剧情章节中：打断性提示应推迟 */
    STORY,

    /** 对话树进行中：打断性提示应推迟 */
    DIALOGUE
}
