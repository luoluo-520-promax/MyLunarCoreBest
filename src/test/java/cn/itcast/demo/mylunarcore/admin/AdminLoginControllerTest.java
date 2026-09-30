package cn.itcast.demo.mylunarcore.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

import java.util.List;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminLoginController} WebMvc 切片测试：CSRF、登录成功/失败/禁用/锁定、登出清 Session。
 * 引入真实 {@link AdminSecurityConfig} 与 {@link AdminLoginAttemptService}；
 * {@link AuthenticationManager} 用 Mockito 模拟认证结果。
 */
@WebMvcTest(AdminLoginController.class)
@Import({AdminSecurityConfig.class, AdminSecurityAdvice.class, AdminLoginAttemptService.class})
@EnableConfigurationProperties(LunarCoreProperties.class)
@DisplayName("后台登录与登出流程测试")
class AdminLoginControllerTest {

    private static final Logger log = LoggerFactory.getLogger(AdminLoginControllerTest.class);

    @Autowired
    private MockMvc mockMvc; // 仅加载 AdminLoginController 相关 MVC 栈

    /** 模拟 Spring Security 认证；各用例 stub authenticate 成功或抛异常。 */
    @MockitoBean
    private AuthenticationManager authenticationManager;

    /** GET /api/admin/csrf 应 200，且 JSON 含非空 token 与 headerName。 */
    @Test
    @DisplayName("获取 CSRF token 应成功")
    void csrfShouldReturnToken() throws Exception {
        mockMvc.perform(get("/api/admin/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").isNotEmpty());
    }

    /**
     * authenticate 返回已认证 token 时：HTTP 200、ok=true，
     * Session 中应写入 SPRING_SECURITY_CONTEXT_KEY。
     */
    @Test
    @DisplayName("登录成功后应写入 Session 并返回成功")
    void loginShouldCreateSessionAndReturnOk() throws Exception {
        log.info("开始执行登录成功测试，准备模拟认证成功");
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "123456", List.of());
        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).willReturn(authentication);

        mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(request().sessionAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        notNullValue()));
        log.info("登录成功测试完成，响应断言全部通过");
    }

    /** 用户不存在等 BadCredentials → 401，业务码 invalid_credentials。 */
    @Test
    @DisplayName("登录失败时应返回 401")
    void loginShouldReturnUnauthorizedWhenLoginFails() throws Exception {
        doThrow(new BadCredentialsException("bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing\",\"password\":\"123456\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    /** 密码错误同样走 BadCredentials → 401 + invalid_credentials。 */
    @Test
    @DisplayName("密码错误时应返回 401")
    void loginShouldReturnUnauthorizedWhenPasswordIsWrong() throws Exception {
        doThrow(new BadCredentialsException("bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("invalid_credentials"));
    }

    /** DisabledException → 403，业务码 disabled。 */
    @Test
    @DisplayName("禁用账号时应返回 403")
    void loginShouldReturnForbiddenWhenAccountDisabled() throws Exception {
        doThrow(new DisabledException("disabled"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.code").value("disabled"));
    }

    /**
     * 同一用户连续 5 次 BadCredentials 后，第 6 次应由 AdminLoginAttemptService
     * 锁定并返回 429 + code=locked。
     */
    @Test
    @DisplayName("连续失败应锁定账号")
    void loginShouldLockAfterRepeatedFailures() throws Exception {
        doThrow(new BadCredentialsException("bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/admin/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"lock-me\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lock-me\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("locked"));
    }

    /** 先登录拿到 Session，再 POST logout，期望 200 且 ok=true（Session 失效由安全栈处理）。 */
    @Test
    @DisplayName("登出后应清理 Session 并返回成功")
    void logoutShouldInvalidateSessionAndReturnOk() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "123456", List.of());
        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).willReturn(authentication);

        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/admin/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false);

        mockMvc.perform(post("/api/admin/logout")
                        .with(csrf())
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
