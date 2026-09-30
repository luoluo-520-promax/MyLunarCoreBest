// 声明当前包：匹配成功后发布的事件载荷
package cn.itcast.demo.mylunarcore.match.api.event;

// 列表：承载匹配成功的玩家 ID 集合
import java.util.List;

/**
 * Spring ApplicationEvent 载荷：匹配成功后发布，解耦战斗开局与匹配大厅。
 *
 * <p>当匹配系统撮合成功时发布该事件，战斗模块监听后创建对局、扣减入场资源并广播开局，
 * 匹配大厅无需关心战斗侧细节。未来可桥接 Redis Pub/Sub 实现跨节点通知。</p>
 *
 * @param matchId      本次匹配生成的唯一对局 ID
 * @param mode         玩法模式 ID
 * @param playerIds    参与对局的玩家 ID 列表（有序）
 * @param createdAtMs  匹配成功时间（Unix 毫秒）
 */
public record MatchFoundEvent(String matchId, int mode, List<Integer> playerIds, long createdAtMs) {}
