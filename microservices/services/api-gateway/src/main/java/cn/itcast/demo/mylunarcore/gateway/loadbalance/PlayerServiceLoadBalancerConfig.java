// 为 player-service 注册自定义负载均衡 Bean
package cn.itcast.demo.mylunarcore.gateway.loadbalance;

// 响应式负载均衡器接口
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
// 从注册中心获取服务实例列表的供应器
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
// 负载均衡客户端工厂，按 serviceId 获取 supplier
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
// 声明 @Bean 的配置类
import org.springframework.context.annotation.Bean;
// 标记为 Spring 配置类
import org.springframework.context.annotation.Configuration;
// 环境变量与配置属性访问
import org.springframework.core.env.Environment;

/**
 * player-service 的负载均衡配置：使用 {@link UserStickyLoadBalancer} 替代默认轮询。
 */
@Configuration // 本类中的 @Bean 会注册到 Spring 容器
public class PlayerServiceLoadBalancerConfig {

    /**
     * 声明用户粘性负载均衡器 Bean，替换默认 ReactorLoadBalancer。
     *
     * @param environment               当前 Spring 环境（含 LoadBalancer 上下文属性）
     * @param loadBalancerClientFactory 按服务名创建 supplier 的工厂
     */
    @Bean // 方法返回值注册为 Bean，供 @LoadBalancerClient 引用
    public ReactorServiceInstanceLoadBalancer userStickyLoadBalancer(
            Environment environment,
            LoadBalancerClientFactory loadBalancerClientFactory
    ) {
        // 从环境读取当前 @LoadBalancerClient 绑定的 serviceId（如 player-service）
        String serviceId = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        // 懒加载获取该服务的实例列表供应器
        ServiceInstanceListSupplier supplier = loadBalancerClientFactory
                .getLazyProvider(serviceId, ServiceInstanceListSupplier.class)
                .getIfAvailable();
        if (supplier == null) {
            // 理论上不会发生：LoadBalancer 会提供默认 supplier
            throw new IllegalStateException("No ServiceInstanceListSupplier for serviceId=" + serviceId);
        }
        // 用自定义粘性策略包装供应器
        return new UserStickyLoadBalancer(supplier, serviceId);
    }
}

