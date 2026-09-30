// 声明当前包：AI 旁路微服务的启动入口
package cn.itcast.demo.mylunarcore.assist;

// Spring Boot 启动器
import org.springframework.boot.SpringApplication;
// Spring Boot 自动装配注解
import org.springframework.boot.autoconfigure.SpringBootApplication;
// 启用配置属性绑定
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * AI 旁路微服务：不参与战斗结算/经济写权威状态，仅输出建议。
 *
 * <p>该服务的职责是：接收来自游戏服的只读上下文，通过规则引擎与可选的外部 LLM
 * 生成建议性回答；它不直接修改玩家数据，也不承担主业务写操作，适合作为
 * 游戏内「教练 / 助手」的旁路能力部署。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(AiAssistServiceProperties.class)
public class AiAssistServiceApplication {

    /**
     * JVM 主入口：启动 Spring 容器与内嵌 Web 服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AiAssistServiceApplication.class, args);
    }
}
