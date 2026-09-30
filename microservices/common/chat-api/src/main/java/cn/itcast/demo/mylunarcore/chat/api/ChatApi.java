// 声明当前包：聊天服务的独立 API 契约
package cn.itcast.demo.mylunarcore.chat.api;

/**
 * 世界/私聊独立 API 契约（实验性）。
 *
 * <p>定义聊天模块对外暴露的抽象能力，供单体游戏服与未来的 chat-service 共用。
 * 当前实现（单体 HallNettyService / 旁路 ChatController）通过该接口保持行为一致，
 * 后续消息总线切换为 Redis Pub/Sub 时无需改动调用方。</p>
 */
public interface ChatApi {

    /**
     * 世界频道消息：一条广播到全服的聊天内容。
     *
     * @param fromPlayerId 发送者玩家 ID
     * @param content      消息正文
     * @param sentAtMs     发送时间（Unix 毫秒）
     */
    record WorldMessage(int fromPlayerId, String content, long sentAtMs) {}

    /**
     * 私聊消息：在两个玩家之间定向投递。
     *
     * @param fromPlayerId 发送者玩家 ID
     * @param toPlayerId   接收者玩家 ID
     * @param content      消息正文
     * @param sentAtMs     发送时间（Unix 毫秒）
     */
    record PrivateMessage(int fromPlayerId, int toPlayerId, String content, long sentAtMs) {}

    /**
     * 发布一条世界频道消息。
     *
     * @param message 世界消息，正文为空时返回 false
     * @return true 表示已成功投递（或进入可靠队列）
     */
    boolean publishWorld(WorldMessage message);

    /**
     * 发布一条私聊消息。
     *
     * @param message 私聊消息，正文为空时返回 false
     * @return true 表示已成功投递（或进入可靠队列）
     */
    boolean publishPrivate(PrivateMessage message);
}
