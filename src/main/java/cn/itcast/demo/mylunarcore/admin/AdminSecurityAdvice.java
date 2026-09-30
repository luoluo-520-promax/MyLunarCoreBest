// 后台管理 HTTP 接口所在包
package cn.itcast.demo.mylunarcore.admin;

// 当前 HTTP 请求
import jakarta.servlet.http.HttpServletRequest;
// HTTP 状态枚举
import org.springframework.http.HttpStatus;
// 带状态码的响应包装
import org.springframework.http.ResponseEntity;
// 请求体 JSON 无法解析
import org.springframework.http.converter.HttpMessageNotReadableException;
// 已登录但权限不足
import org.springframework.security.access.AccessDeniedException;
// 用户名或密码错误
import org.springframework.security.authentication.BadCredentialsException;
// 账号被禁用
import org.springframework.security.authentication.DisabledException;
// 缺少必填请求参数
import org.springframework.web.bind.MissingServletRequestParameterException;
// 声明异常处理方法
import org.springframework.web.bind.annotation.ExceptionHandler;
// 全局 REST 异常切面（仅作用于 @RestController）
import org.springframework.web.bind.annotation.RestControllerAdvice;
// 参数类型与路径变量不匹配
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
// 无匹配控制器或静态资源
import org.springframework.web.servlet.resource.NoResourceFoundException;
// SLF4J 日志
import org.slf4j.Logger;
// SLF4J 日志工厂
import org.slf4j.LoggerFactory;

// 统一错误 JSON 结构
import java.util.Map;

/**
 * 后台 HTTP API 全局异常处理：统一错误响应结构，避免控制器层重复 try/catch。
 * <p>
 * 设计约束：
 * <ul>
 *   <li>认证/鉴权失败始终返回固定结构，便于前端统一拦截。</li>
 *   <li>参数错误透传异常消息，方便调用方快速定位请求问题。</li>
 *   <li>未知异常不暴露堆栈细节，降低敏感信息泄漏风险。</li>
 * </ul>
 */
@RestControllerAdvice // 捕获本应用 REST 控制器抛出的异常
public class AdminSecurityAdvice {

    private static final Logger log = LoggerFactory.getLogger(AdminSecurityAdvice.class);

    /**
     * 处理无路由/无静态资源（例如访问未实现的 GET /）。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "not_found", "资源不存在", request.getRequestURI());
    }

    /**
     * 处理访问被拒绝场景（已登录但权限不足）。
     *
     * @param ex Spring Security 抛出的权限异常
     * @param request 当前请求
     * @return HTTP 403 与统一错误体
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "forbidden", "权限不足", request.getRequestURI());
    }

    /**
     * 处理账号密码错误（未认证成功）。
     *
     * @param ex 认证失败异常
     * @param request 当前请求
     * @return HTTP 401 与统一错误体
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> badCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_credentials", "用户名或密码错误", request.getRequestURI());
    }

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> accountLocked(AccountLockedException ex, HttpServletRequest request) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "locked", "登录失败次数过多，账号已临时锁定", request.getRequestURI());
    }

    /**
     * 处理账号被禁用场景。
     *
     * @param ex 账号禁用异常
     * @param request 当前请求
     * @return HTTP 403 与统一错误体
     */
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, Object>> disabled(DisabledException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "disabled", "账号已禁用", request.getRequestURI());
    }

    /**
     * 处理请求参数、类型、JSON 反序列化等客户端可修复错误。
     *
     * @param ex 具体 bad request 异常
     * @param request 当前请求
     * @return HTTP 400；异常消息可能为空，统一在 {@link #build(HttpStatus, String, String, String)} 中兜底
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> badRequest(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage(), request.getRequestURI());
    }

    /**
     * 兜底处理未捕获异常。
     *
     * @param ex 未知异常
     * @param request 当前请求
     * @return HTTP 500，返回通用消息而非详细堆栈
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internalError(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "服务器内部错误", request.getRequestURI());
    }

    /**
     * 构建统一错误响应体。
     *
     * @param status HTTP 状态码
     * @param code 业务错误码
     * @param message 业务错误消息；为 null 时转换为空串，避免 JSON 字段不稳定
     * @param path 请求路径
     * @return 统一格式的错误响应
     */
    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status).body(Map.of(
                "ok", false, // 业务失败标记
                "status", status.value(), // HTTP 状态码数值
                "code", code, // 机器可读错误码
                "message", message == null ? "" : message, // 给人看的说明
                "path", path // 出错的请求路径
        ));
    }
}
