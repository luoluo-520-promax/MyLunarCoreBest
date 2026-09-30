// 主应用入口类所在包（包名与目录结构对应）
package cn.itcast.demo.mylunarcore;

// 游戏服全局配置属性类（读取 application.properties）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Spring Boot 启动器：创建并运行 Spring 应用
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * MyLunarCore 主启动类：启动 Spring 容器与游戏服核心 Bean。
 * 配置/资源热更新见 {@link cn.itcast.demo.mylunarcore.common.HotReloadCoordinator}。
 * <p>
 * Redis 自动配置默认排除：仅当 {@code lunarcore.redis.enabled=true} 时由
 * {@link cn.itcast.demo.mylunarcore.config.LunarRedisConfiguration} 显式装配，避免无 Redis 时启动失败。
 */
@SpringBootApplication(exclude = {
        DataRedisAutoConfiguration.class,
        DataRedisRepositoriesAutoConfiguration.class
})
@EnableConfigurationProperties(LunarCoreProperties.class)
@EnableTransactionManagement
@EnableScheduling
public class MyLunarCoreApplication {

    /**
     * JVM 程序入口：运行此方法即可启动整个游戏服务器。
     *
     * @param args 命令行参数（本项目中通常为空）
     */
    public static void main(String[] args) {
        SpringApplication.run(MyLunarCoreApplication.class, args);
    }

}
