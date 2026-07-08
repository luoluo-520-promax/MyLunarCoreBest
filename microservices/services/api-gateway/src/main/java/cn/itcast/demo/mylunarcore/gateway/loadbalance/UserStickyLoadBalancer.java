// 网关负载均衡：按用户 ID 粘性路由到固定实例
package cn.itcast.demo.mylunarcore.gateway.loadbalance;

// Spring Cloud 服务实例描述
import org.springframework.cloud.client.ServiceInstance;
// 负载均衡选中实例后的默认响应包装
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
// 无可用实例时的空响应
import org.springframework.cloud.client.loadbalancer.EmptyResponse;
// 负载均衡请求上下文
import org.springframework.cloud.client.loadbalancer.Request;
// 请求中的 HTTP 头、路径等数据
import org.springframework.cloud.client.loadbalancer.RequestData;
// 将 RequestData 放入负载均衡上下文的包装类
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
// 负载均衡决策结果（选中哪台实例）
import org.springframework.cloud.client.loadbalancer.Response;
// 响应式负载均衡器接口
import org.springframework.cloud.loadbalancer.core.ReactorServiceInstanceLoadBalancer;
// 提供某服务名下所有实例列表的供应器
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
// Project Reactor 的单值异步类型
import reactor.core.publisher.Mono;

// 有序列表：承载注册中心返回的服务实例序列
import java.util.List;
// 线程安全的整数计数器，用于轮询下标
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按用户粘性路由：同一个 X-User-Id 始终选择同一实例（实例列表变化会导致映射变化）。
 * 未携带 X-User-Id 时，回退到轮询策略。
 */
public class UserStickyLoadBalancer implements ReactorServiceInstanceLoadBalancer {

    // 当前负载均衡目标的服务名（如 player-service）
    private final String serviceId;
    // 从注册中心拉取实例列表的供应器
    private final ServiceInstanceListSupplier supplier;
    // 轮询模式下递增的位置计数器
    private final AtomicInteger roundRobinPosition = new AtomicInteger(0);

    /**
     * 构造负载均衡器。
     *
     * @param supplier  实例列表供应器（由 Spring Cloud LoadBalancer 注入）
     * @param serviceId 服务标识，用于日志与配置关联
     */
    public UserStickyLoadBalancer(ServiceInstanceListSupplier supplier, String serviceId) {
        this.supplier = supplier;
        this.serviceId = serviceId;
    }

    /**
     * 负载均衡入口：异步获取实例列表并挑选一台。
     */
    @Override
    public Mono<Response<ServiceInstance>> choose(Request request) {
        // 取实例列表 → 在列表上执行 chooseInstance 逻辑
        return supplier.get(request).next().map(instances -> chooseInstance(request, instances));
    }

    /**
     * 根据请求头 X-User-Id 或轮询策略选择实例。
     */
    private Response<ServiceInstance> chooseInstance(Request request, List<ServiceInstance> instances) {
        // 没有注册实例时返回空响应，上游会处理为无可用服务
        if (instances == null || instances.isEmpty()) {
            return new EmptyResponse();
        }

        // 从网关转发的请求头读取用户 ID
        String userId = extractHeader(request, "X-User-Id");
        // 未登录或未透传用户 ID：使用轮询
        if (userId == null || userId.isBlank()) {
            // 原子递增后对实例数量取模，得到轮询下标
            int pos = Math.floorMod(roundRobinPosition.incrementAndGet(), instances.size());
            return new DefaultResponse(instances.get(pos));
        }

        // 同一 userId 的 hashCode 模实例数 → 粘性到固定下标
        int index = Math.floorMod(userId.hashCode(), instances.size());
        return new DefaultResponse(instances.get(index));
    }

    /**
     * 从负载均衡 Request 的上下文中读取 HTTP 请求头。
     */
    private String extractHeader(Request request, String headerName) {
        Object context = request.getContext();
        // Spring Cloud Gateway 会把原始请求放在 RequestDataContext 里
        if (context instanceof RequestDataContext rdc) {
            RequestData clientRequest = rdc.getClientRequest();
            if (clientRequest == null) return null;
            // 取第一个同名头（大小写不敏感由底层处理）
            return clientRequest.getHeaders().getFirst(headerName);
        }
        return null;
    }
}
