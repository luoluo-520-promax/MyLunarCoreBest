// 后台管理 HTTP 接口所在包
package cn.itcast.demo.mylunarcore.admin;

// 方法执行前校验权限
import org.springframework.security.access.prepost.PreAuthorize;
// GET 映射
import org.springframework.web.bind.annotation.GetMapping;
// 类级别路径
import org.springframework.web.bind.annotation.RequestMapping;
// REST 控制器
import org.springframework.web.bind.annotation.RestController;

// 返回 JSON 用的 Map
import java.util.Map;

/**
 * 示例：每个接口声明所需权限标识；无权限时返回 403（由全局异常或 Security 入口处理）。
 */
@RestController // REST API 控制器
@RequestMapping("/api/admin/demo") // 演示 RBAC 的示例接口根路径
public class AdminRbacDemoController {

    @GetMapping("/complaint") // GET /api/admin/demo/complaint
    @PreAuthorize("hasAuthority('admin:complaint:handle')") // 需具备投诉处理权限
    public Map<String, String> handleComplaintDemo() {
        return Map.of("action", "处理玩家投诉（示例）");
    }

    @GetMapping("/items") // GET /api/admin/demo/items
    @PreAuthorize("hasAuthority('admin:item:manage')") // 需具备物品管理权限
    public Map<String, String> manageItemsDemo() {
        return Map.of("action", "管理游戏内物品（示例）");
    }

    @GetMapping("/game-config") // GET /api/admin/demo/game-config
    @PreAuthorize("hasAuthority('admin:game:read')") // 需具备游戏配置只读权限
    public Map<String, String> readGameConfigDemo() {
        return Map.of("action", "查看游戏配置（示例）");
    }
}
