// Spring ApplicationEvent 发布的薄封装
package cn.itcast.demo.mylunarcore.common;

// Spring 事件发布器接口
import org.springframework.context.ApplicationEventPublisher;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

/**
 * 对 Spring {@link ApplicationEventPublisher} 的薄封装：战斗等业务模块统一从此处发布事件。
 */
@Component // 供战斗等业务模块注入
public class GameEventPublisher {

    // Spring 容器提供的事件总线
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 构造器注入 Spring 事件发布器。
     *
     * @param applicationEventPublisher Spring 注入
     */
    public GameEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher; // 保存发布器引用
    }

    /**
     * 发布领域事件到 Spring 事件总线。
     *
     * @param event 任意事件对象；监听方参数类型需与之匹配
     */
    public void publish(Object event) {
        applicationEventPublisher.publishEvent(event); // 分发给所有 @EventListener 方法
    }
}
