// 认证微服务入口包

// 当前类所属包：cn.itcast.demo.mylunarcore.auth
package cn.itcast.demo.mylunarcore.auth;



// JWT 配置属性类，需显式启用绑定

// 本项目业务类
import cn.itcast.demo.mylunarcore.auth.security.AuthJwtProperties;

// Spring Boot 启动器

// Spring 框架类
import org.springframework.boot.SpringApplication;

// 自动配置 Spring Boot 应用

// Spring 框架类
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 启用 @ConfigurationProperties 类注册为 Bean

// Spring 框架类
import org.springframework.boot.context.properties.EnableConfigurationProperties;



/**

 * 认证服务启动类：提供 /auth/login、/auth/health 等 REST 接口。

 */

@SpringBootApplication // 扫描本包及子包下的组件

@EnableConfigurationProperties(AuthJwtProperties.class) // 加载 mylunarcore.auth.jwt 配置

public class AuthServiceApplication {



    /**

     * JVM 主入口：启动内嵌 Tomcat 与 Spring 容器。

     */

    public static void main(String[] args) {

        SpringApplication.run(AuthServiceApplication.class, args);

    }

}


