// API 网关微服务入口包
package cn.itcast.demo.mylunarcore.gateway;

// player-service 专用负载均衡配置类
import cn.itcast.demo.mylunarcore.gateway.loadbalance.PlayerServiceLoadBalancerConfig;
// 限流相关配置属性
import cn.itcast.demo.mylunarcore.gateway.ratelimit.GatewayRateLimitProperties;
// JWT 鉴权相关配置属性
import cn.itcast.demo.mylunarcore.gateway.security.GatewayAuthProperties;
// Spring Boot：启动入口，运行内嵌容器
import org.springframework.boot.SpringApplication;
// Spring Boot：组合 @Configuration、@EnableAutoConfiguration、@ComponentScan
import org.springframework.boot.autoconfigure.SpringBootApplication;
// 启用 @ConfigurationProperties 类型并注册为 Bean
import org.springframework.boot.context.properties.EnableConfigurationProperties;
// 为指定服务名绑定自定义 LoadBalancer 配置
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;

/**
 * Spring Cloud Gateway 启动类：统一入口、鉴权、限流、路由与负载均衡。
 */
@SpringBootApplication // 扫描本模块 Spring 组件并启用自动配置
@EnableConfigurationProperties({GatewayAuthProperties.class, GatewayRateLimitProperties.class}) // 绑定网关鉴权与限流配置项
@LoadBalancerClient(name = "player-service", configuration = PlayerServiceLoadBalancerConfig.class) // player-service 使用自定义负载均衡配置
public class ApiGatewayApplication {

    /**
     * JVM 主入口：启动 Spring Boot 与 Gateway 过滤器链。
     */
    public static void main(String[] args) {
        // 以本类为源启动 Spring 应用上下文
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
