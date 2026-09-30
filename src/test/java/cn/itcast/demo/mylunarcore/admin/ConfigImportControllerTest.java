package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigImportService;
import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ConfigImportController} 切片测试：未登录拒绝、带 admin:ops:write 权限时
 * JSON 批量导入与统一活动导入成功路径；ConfigImportService/审计服务均 mock。
 */
@WebMvcTest(ConfigImportController.class)
@Import({AdminSecurityConfig.class, AdminSecurityAdvice.class})
@EnableConfigurationProperties(LunarCoreProperties.class)
@DisplayName("ConfigImportController 配置导入接口测试")
class ConfigImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 实际写盘/热加载逻辑 mock，本类只验 HTTP 鉴权与响应字段。 */
    @MockitoBean
    private ConfigImportService configImportService;

    @MockitoBean
    private ConfigPublishAuditService auditService;

    /** 无 Session 调用 import/json-files，即使带 CSRF 也应 401。 */
    @Test
    @DisplayName("未登录导入应返回 401")
    void importShouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/admin/ops/import/json-files")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"files\":{\"ActivityScheduling.json\":\"[]\"}}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Session 带 admin:ops:write：stub importJsonFiles 返回 ok/reloaded，
     * 断言 HTTP 200 与 JSON 字段。
     */
    @Test
    @DisplayName("有权限导入 JSON 文件应返回成功")
    void importJsonFilesShouldReturnOk() throws Exception {
        given(configImportService.importJsonFiles(any(), anyBoolean(), anyBoolean()))
                .willReturn(Map.of("ok", true, "written", java.util.List.of("ActivityScheduling.json"), "reloaded", true));

        mockMvc.perform(post("/api/admin/ops/import/json-files")
                        .with(csrf())
                        .session(authenticatedSession("admin:ops:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"files\":{\"ActivityScheduling.json\":\"[]\"},\"reload\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reloaded").value(true));
    }

    /**
     * 统一活动导入：stub 返回 activityId=5000701 与 gameDataKey，
     * 请求体含夏日庆典字段，断言响应回显这些键。
     */
    @Test
    @DisplayName("有权限导入统一活动应返回成功")
    void importActivityShouldReturnOk() throws Exception {
        given(configImportService.importUnifiedActivity(any(), anyBoolean(), anyBoolean()))
                .willReturn(Map.of(
                        "ok", true,
                        "activityId", 5000701,
                        "detailFile", "activities/activity.5000701.json",
                        "gameDataKey", "activity.5000701",
                        "reloaded", true));

        mockMvc.perform(post("/api/admin/ops/import/activity")
                        .with(csrf())
                        .session(authenticatedSession("admin:ops:write"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reload": true,
                                  "activity": {
                                    "activityId": 5000701,
                                    "name": "夏日庆典",
                                    "moduleId": 50007,
                                    "beginTime": 1750000000,
                                    "endTime": 1752592000,
                                    "unlockLevel": 20
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityId").value(5000701))
                .andExpect(jsonPath("$.gameDataKey").value("activity.5000701"));
    }

    /**
     * 构造已认证 MockHttpSession：把指定权限写入 SecurityContext 并挂到 Session 属性，
     * 供后续 MockMvc 请求复用登录态。
     */
    private MockHttpSession authenticatedSession(String... authorities) {
        MockHttpSession session = new MockHttpSession();
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "ops01",
                "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}
