package cn.itcast.demo.mylunarcore.admin;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Admin HTTP OpenAPI（SpringDoc）：访问 /swagger-ui.html 。
 * 游戏 Protobuf 协议文档仍由 docs/protocol-docs.md / generate_docs_from_meta.py 维护。
 */
@Configuration
public class AdminOpenApiConfig {

    @Bean
    public OpenAPI lunarCoreAdminOpenApi() {
        return new OpenAPI().info(new Info()
                .title("MyLunarCore Admin API")
                .description("运营后台与内部管理接口；游戏协议见 protocol-docs")
                .version("1.0"));
    }
}
