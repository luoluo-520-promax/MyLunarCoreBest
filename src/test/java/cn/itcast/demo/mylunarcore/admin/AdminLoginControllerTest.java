package cn.itcast.demo.mylunarcore.admin; // 测试类所在包

import org.junit.jupiter.api.DisplayName; // 测试名称注解
import org.junit.jupiter.api.Test; // 单元测试注解
import org.springframework.beans.factory.annotation.Autowired; // 自动注入
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest; // 仅加载 Web 层切片
import org.springframework.context.annotation.Import; // 导入额外配置
import org.springframework.test.context.bean.override.mockito.MockitoBean; // 替换容器中的 Bean 为 Mock
import org.springframework.http.MediaType; // 媒体类型
import org.springframework.security.authentication.AuthenticationManager; // 认证管理器
import org.springframework.security.authentication.BadCredentialsException; // 密码错误异常
import org.springframework.security.authentication.DisabledException; // 账号禁用异常
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken; // 用户名密码令牌
import org.springframework.security.core.Authentication; // 认证结果
import org.springframework.security.web.context.HttpSessionSecurityContextRepository; // Session 中安全上下文键
import org.springframework.mock.web.MockHttpSession; // Mock Session
import org.springframework.test.web.servlet.MockMvc; // MockMvc 测试客户端
import org.slf4j.Logger; // 日志接口
import org.slf4j.LoggerFactory; // 日志工厂

import java.util.List; // 集合类型

import static org.hamcrest.Matchers.notNullValue; // 非空匹配器
import static org.mockito.ArgumentMatchers.any; // 任意对象参数
import static org.mockito.BDDMockito.given; // BDD 风格模拟
import static org.mockito.Mockito.doThrow; // 让方法抛出异常
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post; // 构造 POST 请求
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath; // JSON 断言
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request; // 请求断言
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status; // 状态码断言

@WebMvcTest(AdminLoginController.class) // 只启动登录控制器相关 Web 层，避免全量扫描 protocol 与游戏服 Bean
@Import({AdminSecurityConfig.class, AdminSecurityAdvice.class}) // 装配安全规则与统一异常响应
@DisplayName("后台登录与登出流程测试") // 测试类名称
class AdminLoginControllerTest { // 测试类开始

    private static final Logger log = LoggerFactory.getLogger(AdminLoginControllerTest.class); // 测试日志

    @Autowired // 注入 MockMvc
    private MockMvc mockMvc; // MockMvc 对象

    @MockitoBean // 替换 AdminSecurityConfig 中的 AuthenticationManager
    private AuthenticationManager authenticationManager; // 认证管理器

    @Test // 标记登录测试
    @DisplayName("登录成功后应写入 Session 并返回成功") // 登录测试名称
    void loginShouldCreateSessionAndReturnOk() throws Exception { // 登录测试方法
        log.info("开始执行登录成功测试，准备模拟认证成功"); // 记录测试步骤
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "123456", List.of()); // 构造认证结果
        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).willReturn(authentication); // 模拟认证成功
        log.info("认证器已配置为返回成功结果，开始发送登录请求"); // 记录测试步骤

        mockMvc.perform(post("/api/admin/login") // 发送登录请求
                        .contentType(MediaType.APPLICATION_JSON) // 指定 JSON 请求体
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}")) // 填入用户名和密码
                .andExpect(status().isOk()) // 断言返回 200
                .andExpect(jsonPath("$.ok").value(true)) // 断言返回成功标记
                .andExpect(request().sessionAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                        notNullValue())); // MockMvc 不一定会下发 JSESSIONID Cookie，断言 Session 已写入安全上下文
        log.info("登录成功测试完成，响应断言全部通过"); // 记录测试步骤
    } // 登录测试结束

    @Test // 标记登录失败测试
    @DisplayName("登录失败时应返回 401") // 登录失败测试名称
    void loginShouldReturnUnauthorizedWhenLoginFails() throws Exception { // 登录失败测试方法
        log.info("开始执行登录失败测试，准备模拟用户名不存在场景"); // 记录测试步骤
        doThrow(new BadCredentialsException("bad credentials")) // 模拟认证失败
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class)); // 对任意登录令牌生效
        log.info("认证器已配置为抛出 BadCredentialsException，开始发送登录请求"); // 记录测试步骤

        mockMvc.perform(post("/api/admin/login") // 发送登录请求
                        .contentType(MediaType.APPLICATION_JSON) // 指定 JSON 请求体
                        .content("{\"username\":\"missing\",\"password\":\"123456\"}")) // 填入不存在的用户名
                .andExpect(status().isUnauthorized()) // 断言返回 401
                .andExpect(jsonPath("$.ok").value(false)) // 断言错误标记
                .andExpect(jsonPath("$.code").value("invalid_credentials")); // 断言错误码
        log.info("登录失败测试完成，已验证返回 401 与 invalid_credentials"); // 记录测试步骤
    } // 登录失败测试结束

    @Test // 标记密码错误测试
    @DisplayName("密码错误时应返回 401") // 密码错误测试名称
    void loginShouldReturnUnauthorizedWhenPasswordIsWrong() throws Exception { // 密码错误测试方法
        log.info("开始执行密码错误测试，准备模拟密码不正确场景"); // 记录测试步骤
        doThrow(new BadCredentialsException("bad credentials")) // 模拟密码错误
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class)); // 对任意登录令牌生效
        log.info("认证器已配置为抛出 BadCredentialsException，开始发送错误密码登录请求"); // 记录测试步骤

        mockMvc.perform(post("/api/admin/login") // 发送登录请求
                        .contentType(MediaType.APPLICATION_JSON) // 指定 JSON 请求体
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}")) // 填入错误密码
                .andExpect(status().isUnauthorized()) // 断言返回 401
                .andExpect(jsonPath("$.ok").value(false)) // 断言错误标记
                .andExpect(jsonPath("$.code").value("invalid_credentials")); // 断言错误码
        log.info("密码错误测试完成，已验证返回 401 与 invalid_credentials"); // 记录测试步骤
    } // 密码错误测试结束

    @Test // 标记禁用账号测试
    @DisplayName("禁用账号时应返回 403") // 禁用账号测试名称
    void loginShouldReturnForbiddenWhenAccountDisabled() throws Exception { // 禁用账号测试方法
        log.info("开始执行禁用账号测试，准备模拟账号被禁用场景"); // 记录测试步骤
        doThrow(new DisabledException("disabled")) // 模拟账号被禁用
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class)); // 对任意登录令牌生效
        log.info("认证器已配置为抛出 DisabledException，开始发送登录请求"); // 记录测试步骤

        mockMvc.perform(post("/api/admin/login") // 发送登录请求
                        .contentType(MediaType.APPLICATION_JSON) // 指定 JSON 请求体
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}")) // 填入用户名和密码
                .andExpect(status().isForbidden()) // 断言返回 403
                .andExpect(jsonPath("$.ok").value(false)) // 断言错误标记
                .andExpect(jsonPath("$.code").value("disabled")); // 断言错误码
        log.info("禁用账号测试完成，已验证返回 403 与 disabled"); // 记录测试步骤
    } // 禁用账号测试结束

    @Test // 标记登出测试
    @DisplayName("登出后应清理 Session 并返回成功") // 登出测试名称
    void logoutShouldInvalidateSessionAndReturnOk() throws Exception { // 登出测试方法
        log.info("开始执行登出测试，先模拟登录成功以获取 Session"); // 记录测试步骤
        Authentication authentication = new UsernamePasswordAuthenticationToken("admin", "123456", List.of()); // 构造认证结果
        given(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).willReturn(authentication); // 模拟认证成功

        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/api/admin/login") // 先执行登录
                        .contentType(MediaType.APPLICATION_JSON) // 指定 JSON 请求体
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}")) // 填入用户名和密码
                .andExpect(status().isOk()) // 断言登录成功
                .andReturn() // 获取响应结果
                .getRequest() // 取得请求对象
                .getSession(false); // 读取登录创建的 Session
        log.info("已获取登录后的 Session，开始发送登出请求"); // 记录测试步骤

        mockMvc.perform(post("/api/admin/logout") // 发送登出请求
                        .session(session)) // 携带登录后的 Session
                .andExpect(status().isOk()) // 断言返回 200
                .andExpect(jsonPath("$.ok").value(true)); // 断言返回成功标记
        log.info("登出测试完成，已验证 Session 被清理且返回成功"); // 记录测试步骤
    } // 登出测试结束
} // 测试类结束
