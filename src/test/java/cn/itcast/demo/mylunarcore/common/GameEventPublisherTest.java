package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * GameEventPublisher 事件发布器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GameEventPublisherTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GameEventPublisher 事件发布器测试")
class GameEventPublisherTest {

    private static final Logger log = LoggerFactory.getLogger(GameEventPublisherTest.class);

    /**
     * 验证点：publish 应委托 Spring ApplicationEventPublisher。
     * <p>测试方法 {@code publishShouldDelegateToSpringPublisher}：
     * <ul>
     *   <li>{@code verify(delegate).publishEvent(event);}</li>
     * </ul>
     */
    @Test
    @DisplayName("publish 应委托 Spring ApplicationEventPublisher")
    void publishShouldDelegateToSpringPublisher() {
        ApplicationEventPublisher delegate = mock(ApplicationEventPublisher.class);
        GameEventPublisher publisher = new GameEventPublisher(delegate);
        BattleStartedEvent event = CommonTestFixtures.battleStartedEvent();

        publisher.publish(event);

        log.info("事件发布校验: eventClass={}, battleId={}, playerId={}",
                event.getClass().getSimpleName(), event.battleId(), event.playerId());
        verify(delegate).publishEvent(event);
    }
}
