// 声明当前包：API 网关的降级（Fallback）控制器
package cn.itcast.demo.mylunarcore.gateway.fallback;

// Content-Type 常量（application/json）
import org.springframework.http.MediaType;
// 请求映射注解：任意 HTTP 方法均可命中
import org.springframework.web.bind.annotation.RequestMapping;
// REST 控制器：返回值直接写为 JSON 响应体
import org.springframework.web.bind.annotation.RestController;
// Reactor 响应式单值类型：包装返回的 Map
import reactor.core.publisher.Mono;

// 键值对：构建统一 JSON 错误体
import java.util.Map;

/**
 * ai-assist-service 熔断/超时降级：返回可识别的降级 JSON，供游戏服客户端决定本地规则兜底。
 *
 * <p>当网关路由到 ai-assist-service 发生熔断或超时时，Spring Cloud Gateway 会把请求
 * 转发到该降级端点。响应使用与 ai-assist-service 相同的契约结构（schemaVersion /
 * retcode / source 等），并标记 source 为 gateway-fallback，方便游戏服识别并切换
 * 到本地规则引擎兜底。</p>
 */
@RestController
public class AiAssistFallbackController {

    /**
     * 降级端点：返回结构化降级响应。
     *
     * <p>retcode=4 表示服务不可用；data 内的 schemaVersion=1、answer 为空串、
     * relatedHints/citedConfigIds 为空列表，结构上与正常响应完全对齐。</p>
     *
     * @return 统一降级 JSON（code=4）
     */
    @RequestMapping(value = "/ai/fallback", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> fallback() {
        // 外层遵循统一响应体，内层遵循 ai-assist 契约
        return Mono.just(Map.of(
                "code", 4,
                "message", "ai-assist-service unavailable",
                "data", Map.of(
                        "schemaVersion", 1,
                        "retcode", 4,
                        "answer", "",
                        "source", "gateway-fallback",
                        "relatedHints", java.util.List.of(),
                        "citedConfigIds", java.util.List.of()
                )
        ));
    }
}
