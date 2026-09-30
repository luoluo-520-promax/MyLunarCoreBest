// 后台管理 HTTP 接口所在包
package cn.itcast.demo.mylunarcore.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台登录/登出；登录成功后权限写入 Session，供后续带 Cookie + CSRF 的请求使用。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminLoginController {

    private final AuthenticationManager authenticationManager;
    private final AdminLoginAttemptService loginAttemptService;

    public AdminLoginController(AuthenticationManager authenticationManager,
                                AdminLoginAttemptService loginAttemptService) {
        this.authenticationManager = authenticationManager;
        this.loginAttemptService = loginAttemptService;
    }

    @GetMapping("/csrf")
    public ResponseEntity<Map<String, String>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok(Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        loginAttemptService.assertNotLocked(req.username());
        try {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(req.username(), req.password());
            Authentication auth = authenticationManager.authenticate(token);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            new HttpSessionSecurityContextRepository().saveContext(context, request, response);
            loginAttemptService.onSuccess(req.username());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (BadCredentialsException ex) {
            loginAttemptService.onFailure(req.username());
            throw ex;
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Boolean>> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.logout(request, response, null);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record LoginRequest(String username, String password) {
    }
}
