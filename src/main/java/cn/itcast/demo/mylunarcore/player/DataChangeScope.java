// 数据变更影响范围：用于增量同步时只拉取相关子表
package cn.itcast.demo.mylunarcore.player;

/**
 * 玩家数据变更影响的子域，供 {@link PlayerDataSyncService} 与 {@link PlayerDataAsyncLoadService} 选择增量加载范围。
 */
public enum DataChangeScope {

    /** 全量：所有子表 */
    ALL,

    /** 仅 player 主表 */
    CORE,

    /** 背包道具 */
    ITEMS,

    /** 角色 */
    AVATARS,

    /** 编队 */
    LINEUPS,

    /** 挑战进度 */
    CHALLENGES,

    /** 模拟宇宙 */
    ROGUES,

    /** 抽卡保底（gacha 相关表，当前合并到全量子表刷新） */
    GACHA,

    /** 好友关系 */
    FRIENDS,

    /** 邮件 */
    MAIL,

    /** 任务进度 */
    QUESTS
}
