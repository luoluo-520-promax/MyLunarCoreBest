// 主应用入口类所在包（包名与目录结构对应）
package cn.itcast.demo.mylunarcore;

// 命令行流程演示入口（--flow=xxx 时不启动 Spring）
import cn.itcast.demo.mylunarcore.demo.FlowDemoCli;
// 游戏服全局配置属性类（读取 application.properties）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Spring Boot 启动器：创建并运行 Spring 应用
import org.springframework.boot.SpringApplication;
// 启用 Spring Boot 自动配置（Web、数据源等按 classpath 自动装配）
import org.springframework.boot.autoconfigure.SpringBootApplication;
// 将 @ConfigurationProperties 类注册为可注入的 Bean
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MyLunarCore 主启动类：启动 Spring 容器与游戏服核心 Bean。
 * 配置/资源热更新见 {@link cn.itcast.demo.mylunarcore.common.HotReloadCoordinator}。
 */
@SpringBootApplication // 扫描本包及子包，自动注册 @Component 等
@EnableConfigurationProperties(LunarCoreProperties.class) // 绑定 lunarcore.* 配置项
public class MyLunarCoreApplication {

    /**
     * JVM 程序入口：运行此方法即可启动整个游戏服务器。
     *
     * @param args 命令行参数（本项目中通常为空）
     */
    public static void main(String[] args) {
        if (FlowDemoCli.isDemoMode(args)) {
            System.exit(FlowDemoCli.run(args));
        }
        // 以本类为配置源启动 Spring Boot（内嵌 Tomcat、Netty 等由配置决定）
        SpringApplication.run(MyLunarCoreApplication.class, args);
    }

}
