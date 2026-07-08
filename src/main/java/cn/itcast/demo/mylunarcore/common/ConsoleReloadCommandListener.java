// 控制台 stdin 监听：识别 /reload 命令并触发热重载
package cn.itcast.demo.mylunarcore.common;

// 全局配置（是否启用控制台 /reload 监听）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Bean 初始化完成后启动后台线程
import jakarta.annotation.PostConstruct;
// Bean 销毁前停止监听线程
import jakarta.annotation.PreDestroy;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举（系统级）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// 按行读取 stdin 文本
import java.io.BufferedReader;
// IO 异常类型
import java.io.IOException;
// 将 System.in 字节流包装为字符流
import java.io.InputStreamReader;
// UTF-8 字符集常量
import java.nio.charset.StandardCharsets;
// 线程安全的停止标志
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 命令行输入 {@code /reload} 时触发 {@link HotReloadCoordinator#reloadAll()}。
 * <p>仅重载配置与静态资源，不支持 JVM 字节码热替换。</p>
 */
@Component // 随 Spring 容器启动注册
public class ConsoleReloadCommandListener {

    // 本类专用 Logger（分类 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ConsoleReloadCommandListener.class);

    // 注入的全局配置：lunarcore.reload-console-enabled 开关
    private final LunarCoreProperties properties;
    // 热重载协调器：聚合 hotfix、活动、抽卡、静态表格等资源刷新
    private final HotReloadCoordinator coordinator;
    // 置 true 后 runLoop 退出循环
    private final AtomicBoolean stopped = new AtomicBoolean();
    // 后台 stdin 读取线程引用，stop() 时 interrupt
    private Thread thread;

    /**
     * 构造器注入配置与热重载协调器。
     *
     * @param properties  全局配置
     * @param coordinator 热更协调器
     */
    public ConsoleReloadCommandListener(LunarCoreProperties properties, HotReloadCoordinator coordinator) {
        this.properties = properties;   // 保存配置引用
        this.coordinator = coordinator; // 保存协调器引用
    }

    /**
     * 若配置启用则启动守护线程读取 {@link System#in}。
     */
    @PostConstruct // Bean 就绪后由 Spring 调用
    public void start() {
        // 配置关闭时不创建线程，避免占用 stdin
        if (!properties.isReloadConsoleEnabled()) {
            log.info("Console /reload listener disabled (lunarcore.reload-console-enabled=false)");
            return;
        }
        thread = new Thread(this::runLoop, "console-reload"); // 命名便于线程 dump 定位
        thread.setDaemon(true);  // 守护线程：不阻止 JVM 退出
        thread.start();          // 开始阻塞读取 stdin
        log.info("Console listener started: type /reload to hot-reload config and resources (no code hot reload)");
    }

    /**
     * 阻塞循环：逐行读取控制台输入，匹配 {@code /reload} 后调用协调器。
     */
    private void runLoop() {
        // try-with-resources：进程退出时自动关闭 Reader
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            while (!stopped.get()) { // 未收到停止信号则持续读取
                String line = br.readLine(); // 阻塞等待一行输入
                if (line == null) {
                    break; // stdin 已关闭（如 IDE 停止或管道断开）
                }
                String t = line.trim(); // 去掉首尾空白，便于匹配命令
                if ("/reload".equalsIgnoreCase(t)) { // 大小写不敏感匹配 /reload
                    try {
                        coordinator.reloadAll(); // 执行全量热重载
                    } catch (Exception e) {
                        log.error("/reload failed", e); // 单条命令失败不影响后续输入
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Console reload stdin closed: {}", e.toString()); // 正常关闭时可能抛 IO 异常
        }
    }

    /**
     * 进程关闭时置停止标志并打断读取线程。
     */
    @PreDestroy // 容器销毁前由 Spring 调用
    public void stop() {
        stopped.set(true);           // 通知 runLoop 退出 while
        if (thread != null) {
            thread.interrupt();      // 打断 readLine 阻塞，加快线程结束
        }
    }
}
