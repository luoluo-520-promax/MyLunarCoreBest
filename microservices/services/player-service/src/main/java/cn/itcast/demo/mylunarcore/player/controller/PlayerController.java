// 玩家服务 HTTP 控制器所在包
package cn.itcast.demo.mylunarcore.player.controller;

// 统一 API 响应体
import cn.itcast.demo.mylunarcore.common.api.ApiResponse;
// GET 映射注解
import org.springframework.web.bind.annotation.GetMapping;
// 路径变量绑定
import org.springframework.web.bind.annotation.PathVariable;
// 类级别请求路径前缀
import org.springframework.web.bind.annotation.RequestMapping;
// REST 控制器
import org.springframework.web.bind.annotation.RestController;

// 不可变 Map
import java.util.Map;

/**
 * 玩家数据 REST 接口（演示）：根据 uid 返回占位数据，实际项目可接数据库。
 */
@RestController // 返回 JSON 而非视图名
@RequestMapping("/players") // 所有接口以 /players 开头
public class PlayerController {

    /**
     * 按用户 ID 查询玩家摘要（演示实现）。
     *
     * @param uid 路径中的玩家 ID，由网关注入的 X-User-Id 或客户端指定
     */
    @GetMapping("/{uid}") // GET /players/{uid}
    public ApiResponse<Map<String, Object>> getPlayer(@PathVariable("uid") Long uid) {
        // 返回 uid 与服务名，便于验证网关路由与粘性负载均衡
        return ApiResponse.ok(Map.of("uid", uid, "service", "player-service"));
    }
}

