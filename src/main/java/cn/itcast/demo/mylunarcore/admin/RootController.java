// 管理后台根路径控制器所在包
package cn.itcast.demo.mylunarcore.admin;

// Spring REST 映射注解
import org.springframework.web.bind.annotation.GetMapping;
// Spring REST 控制器注解
import org.springframework.web.bind.annotation.RestController;

// 不可变键值映射工厂
import java.util.Map;

/**
 * 根路径健康检查：供浏览器或链路探测工具确认 HTTP 服务存活。
 */
@RestController // Spring REST 控制器：暴露 HTTP 端点
public class RootController {

    /**
     * GET / 返回服务基本信息与后台 API 入口路径。
     */
    @GetMapping("/") // 映射根路径 GET 请求
    public Map<String, Object> index() { // 健康检查入口
        return Map.of( // 构建不可变响应体
                "ok", true, // 服务存活标志
                "service", "MyLunarCore", // 服务名称
                "adminApi", "/api/admin" // 管理后台 API 前缀
        );
    }
}
