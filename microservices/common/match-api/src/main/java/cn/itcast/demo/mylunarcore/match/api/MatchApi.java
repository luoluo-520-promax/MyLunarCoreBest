// 声明当前包：匹配大厅的独立 API 契约
package cn.itcast.demo.mylunarcore.match.api;

/**
 * 匹配大厅独立 API 契约（实验性）：单体与未来 match-service 共用。
 *
 * <p>定义「排队」「取消排队」两个核心动作的抽象接口。当前由单体游戏服（MatchmakingService）
 * 与旁路 match-service（MatchController）各自实现，未来将 match-service 提升为
 * 权威匹配引擎时，调用方只需切换实现而不改动契约。</p>
 */
public interface MatchApi {

    /**
     * 入队请求：描述一名玩家希望进入哪种匹配。
     *
     * @param playerId 玩家唯一 ID
     * @param mode     匹配模式（玩法 ID）
     * @param level    玩家等级，用于匹配区间过滤
     * @param power    玩家战力/实力值，用于实力相近的撮合
     */
    record EnqueueRequest(int playerId, int mode, int level, int power) {}

    /**
     * 入队结果。
     *
     * @param success 是否成功入队
     * @param reason  失败原因（如 bad_player、already_queued），成功时一般为 ok
     * @param queueId 成功入队后生成的排队凭证（ticket），失败时为 null
     */
    record EnqueueResult(boolean success, String reason, String queueId) {}

    /**
     * 取消排队请求。
     *
     * @param playerId 要移出队列的玩家 ID
     */
    record CancelRequest(int playerId) {}

    /**
     * 将玩家加入匹配队列。
     *
     * @param request 入队请求（玩家、模式、等级、战力）
     * @return 入队结果（含排队凭证）
     */
    EnqueueResult enqueue(EnqueueRequest request);

    /**
     * 将玩家移出匹配队列。
     *
     * @param request 取消请求（玩家 ID）
     * @return true 表示成功取消；玩家不在队列或参数非法时返回 false
     */
    boolean cancel(CancelRequest request);
}
