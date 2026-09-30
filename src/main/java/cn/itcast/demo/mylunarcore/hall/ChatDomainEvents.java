package cn.itcast.demo.mylunarcore.hall;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 聊天域事件桥：Spring Events 解耦广播/审计，契约对齐 microservices/common/chat-api。
 */
@Component
public class ChatDomainEvents {

    public record ChatMessageEvent(String channel, int fromPlayerId, Integer toPlayerId,
                                   String content, long sentAtMs) {}

    private final ApplicationEventPublisher publisher;

    public ChatDomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishWorld(int fromPlayerId, String content) {
        publisher.publishEvent(new ChatMessageEvent(
                "world", fromPlayerId, null, content, System.currentTimeMillis()));
    }

    public void publishPrivate(int fromPlayerId, int toPlayerId, String content) {
        publisher.publishEvent(new ChatMessageEvent(
                "private", fromPlayerId, toPlayerId, content, System.currentTimeMillis()));
    }
}
