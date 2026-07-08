// 玩家微服务入口包
package cn.itcast.demo.mylunarcore.player;

// Spring Boot 启动类
import org.springframework.boot.SpringApplication;
// 自动配置
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 玩家服务启动类：处理 /players/** 等业务 HTTP 请求（经网关转发）。
 */
@SpringBootApplication // 启用组件扫描与自动配置
public class PlayerServiceApplication {

    /**
     * JVM 主入口。
     */
    public static void main(String[] args) {
        SpringApplication.run(PlayerServiceApplication.class, args);
    }
}

