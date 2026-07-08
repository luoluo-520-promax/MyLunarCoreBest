// Netty 游戏业务线程池配置所在包
package cn.itcast.demo.mylunarcore.config;
// Netty 默认事件执行器组（业务线程池）
import io.netty.util.concurrent.DefaultEventExecutorGroup;
// Netty 默认线程工厂（可指定线程名前缀）
import io.netty.util.concurrent.DefaultThreadFactory;
// 容器销毁前的清理回调
import jakarta.annotation.PreDestroy;
// 声明 Spring 配置类，可定义 @Bean
import org.springframework.context.annotation.Bean;
// Spring 框架类
import org.springframework.context.annotation.Configuration;
// 时间单位枚举，用于优雅关闭等待
import java.util.concurrent.TimeUnit;

/**
 * 游戏业务线程池：在 Netty I/O 线程完成编解码后，将包处理与 JDBC 等阻塞操作放到独立线程，
 * 避免占用 {@link io.netty.channel.nio.NioEventLoop}，使多连接下不会因单玩家慢查询阻塞同线程上其他连接。
 */
@Configuration // 本类中的 @Bean 会注册到 Spring 容器
public class NettyGameBusinessConfiguration {

    // 保存线程池引用，供 @PreDestroy 优雅关闭（volatile 保证可见性）
    private volatile DefaultEventExecutorGroup gameBusinessExecutorGroup;

    // destroyMethod=""：关闭由本类的 @PreDestroy 手动处理，不让 Spring 自动 shutdown
    @Bean(destroyMethod = "")
    public DefaultEventExecutorGroup gameBusinessExecutorGroup(LunarCoreProperties properties) {
        // 从配置读取业务线程数
        int threads = properties.getNetty().getBusinessThreads();
        // 未配置或 <=0 时按 CPU 核心数估算（至少 4 线程）
        if (threads <= 0) { // 条件分支
            threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        }
        // 创建 Netty 业务线程池，线程名前缀 game-business，守护线程
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(
                threads,
                new DefaultThreadFactory("game-business", true));
        this.gameBusinessExecutorGroup = group; // 记下引用供销毁时使用
        return group; // 交给 Spring 管理，供 Handler 注入使用
    }

    // 应用关闭前：优雅停止业务线程池
    @PreDestroy
    public void shutdownBusinessExecutors() {
        DefaultEventExecutorGroup g = gameBusinessExecutorGroup;
        if (g != null) { // 条件分支
            // 0 秒静默期后开始关，最多等 5 秒；syncUninterruptibly 阻塞直到结束
            g.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
