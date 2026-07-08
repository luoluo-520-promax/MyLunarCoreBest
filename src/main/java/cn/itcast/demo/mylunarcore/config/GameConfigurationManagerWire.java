// Spring 与单例配置桥接类所在包
package cn.itcast.demo.mylunarcore.config;

// Bean 初始化完成后的回调注解
import jakarta.annotation.PostConstruct;
// 声明为 Spring 管理的组件
import org.springframework.stereotype.Component;

/**
 * 将 Spring 绑定的 {@link LunarCoreProperties} 挂接到饿汉式 {@link GameConfigurationManager}。
 */
@Component // 注册到 Spring 容器，启动时自动创建
public class GameConfigurationManagerWire {

    // 从 application.yml 绑定好的全局配置（构造器注入，不可变引用）
    private final LunarCoreProperties lunarCoreProperties;

    // Spring 根据构造器参数自动注入 LunarCoreProperties Bean
    public GameConfigurationManagerWire(LunarCoreProperties lunarCoreProperties) {
        this.lunarCoreProperties = lunarCoreProperties;
    }

    // Bean 属性填充完成后执行：把配置写入饿汉式单例
    @PostConstruct
    public void wire() {
        GameConfigurationManager.getInstance().wire(lunarCoreProperties);
    }
}
