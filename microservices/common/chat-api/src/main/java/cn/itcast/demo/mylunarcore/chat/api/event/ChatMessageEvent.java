// 声明当前包：聊天消息跨模块投递的事件载荷
package cn.itcast.demo.mylunarcore.chat.api.event;

/**
 * Spring ApplicationEvent 载荷：聊天消息跨模块投递（未来可桥接 Redis Pub/Sub）。
 *
 * <p>当聊天消息需要从产生模块（如 HTTP 控制器）传递到消费模块（如消息推送、审核、
 * 跨节点广播）时，通过 Spring 事件总线发布该 record 解耦两端。未来如需跨节点，
 * 可将同一载荷序列化后桥接到 Redis Pub/Sub 通道。</p>
 *
 * @param channel      频道标识（如 world / private）
 * @param fromPlayerId 发送者玩家 ID
 * @param toPlayerId   接收者玩家 ID，世界频道为 null
 * @param content      消息正文
 * @param sentAtMs     发送时间（Unix 毫秒）
 */
public record ChatMessageEvent(String channel, int fromPlayerId, Integer toPlayerId,
                               String content, long sentAtMs) {}
