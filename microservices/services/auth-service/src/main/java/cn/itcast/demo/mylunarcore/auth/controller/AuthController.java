// 认证服务 HTTP 控制器所在包
package cn.itcast.demo.mylunarcore.auth.controller;

// 统一 API 响应包装类
import cn.itcast.demo.mylunarcore.common.api.ApiResponse;
// JWT 相关配置（用户表、密钥、过期时间等）
import cn.itcast.demo.mylunarcore.auth.security.AuthJwtProperties;
// 签发 JWT 的业务服务
import cn.itcast.demo.mylunarcore.auth.security.JwtTokenService;
// GET 请求映射
import org.springframework.web.bind.annotation.GetMapping;
// POST 请求映射
import org.springframework.web.bind.annotation.PostMapping;
// 请求体 JSON 反序列化
import org.springframework.web.bind.annotation.RequestBody;
// 控制器根路径前缀
import org.springframework.web.bind.annotation.RequestMapping;
// 声明为 REST 控制器（返回值直接写 HTTP 体）
import org.springframework.web.bind.annotation.RestController;

// 不可变键值对 Map 工厂
import java.util.Map;

/**
 * 认证 REST 接口：健康检查、用户名密码登录并返回 Bearer Token。
 */
@RestController // 组合 @Controller + @ResponseBody
@RequestMapping("/auth") // 本类所有接口前缀为 /auth
public class AuthController {

    // 从 yml 读取的 JWT 与用户凭证配置
    private final AuthJwtProperties jwtProperties;
    // 负责生成 JWT 字符串
    private final JwtTokenService tokenService;

    /**
     * 构造器注入依赖（Spring 自动调用）。
     */
    public AuthController(AuthJwtProperties jwtProperties, JwtTokenService tokenService) {
        this.jwtProperties = jwtProperties;
        this.tokenService = tokenService;
    }

    /**
     * 健康检查：供网关或 K8s 探活，无需登录。
     */
    @GetMapping("/health") // GET /auth/health
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("service", "auth-service", "status", "UP"));
    }

    /**
     * 登录：校验用户名密码，成功则签发 JWT 并返回 token 与用户信息。
     */
    @PostMapping("/login") // POST /auth/login，请求体为 JSON
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest request) {
        // 请求体或用户名/密码为空 → 400
        if (request == null || request.username() == null || request.password() == null) {
            return ApiResponse.fail(40001, "bad_request");
        }
        // 从配置中的用户表按用户名查找
        AuthJwtProperties.UserCredential user = jwtProperties.getUsers().get(request.username());
        // 用户不存在或密码明文不匹配 → 401（演示项目为明文比对，生产应使用哈希）
        if (user == null || user.getPassword() == null || !user.getPassword().equals(request.password())) {
            return ApiResponse.fail(40101, "invalid_credentials");
        }
        // 签发 JWT，包含 uid、username、role
        JwtTokenService.TokenResult tokenResult = tokenService.createToken(user.getUid(), request.username(), user.getRole());
        // 返回标准成功体：Token 类型、访问令牌、过期时间戳及用户摘要
        return ApiResponse.ok(Map.of(
                "tokenType", "Bearer",
                "accessToken", tokenResult.token(),
                "expiresAt", tokenResult.expireAt(),
                "uid", user.getUid(),
                "username", request.username(),
                "role", user.getRole()
        ));
    }

    /**
     * 登录请求体：Java record，对应 JSON 字段 username、password。
     */
    public record LoginRequest(String username, String password) {
    }
}

