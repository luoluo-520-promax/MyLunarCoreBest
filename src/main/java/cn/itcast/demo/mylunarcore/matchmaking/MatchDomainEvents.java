package cn.itcast.demo.mylunarcore.matchmaking;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 匹配域事件桥：通过 Spring Events 解耦开局/通知，为拆出 match-service 铺路。
 * 契约对齐 microservices/common/match-api。
 */
@Component
public class MatchDomainEvents {

    public record MatchFoundEvent(String matchId, int mode, List<Integer> playerIds, long createdAtMs) {}

    private final ApplicationEventPublisher publisher;

    public MatchDomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publishMatchFound(String matchId, int mode, List<Integer> playerIds) {
        publisher.publishEvent(new MatchFoundEvent(
                matchId, mode, List.copyOf(playerIds), System.currentTimeMillis()));
    }
}
