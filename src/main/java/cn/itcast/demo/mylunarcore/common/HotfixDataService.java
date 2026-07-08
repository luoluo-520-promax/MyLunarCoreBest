// 从 classpath/file 加载 HotfixData 并与 /reload 联动
package cn.itcast.demo.mylunarcore.common;

// 全局配置（hotfix.resource 路径）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Jackson 对象映射器
import com.fasterxml.jackson.databind.ObjectMapper;
// Bean 初始化后自动加载
import jakarta.annotation.PostConstruct;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring 抽象资源（classpath:、file: 等）
import org.springframework.core.io.Resource;
// 按路径加载 Resource
import org.springframework.core.io.ResourceLoader;
// 声明为 Spring 业务服务 Bean
import org.springframework.stereotype.Service;

// 输入流，读取 JSON 文件
import java.io.InputStream;

/**
 * 管理 {@link HotfixData}：启动时与 /reload 时从配置路径重新加载。
 */
@Service // 注册为 Spring Bean
public class HotfixDataService {

    // 本类专用 Logger（分类 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, HotfixDataService.class);

    // 配置前缀 lunarcore.hotfix.*
    private final LunarCoreProperties properties;
    // 用于 getResource("classpath:hotfix.json") 等
    private final ResourceLoader resourceLoader;
    // JSON 解析器（ObjectMapper 线程安全，可复用）
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 当前内存中的热修复数据快照（volatile 保证读者可见最新引用）
    private volatile HotfixData data = HotfixData.empty();

    /**
     * 构造器注入配置与资源加载器。
     *
     * @param properties     全局配置
     * @param resourceLoader 资源加载器
     */
    public HotfixDataService(LunarCoreProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;           // 保存配置引用
        this.resourceLoader = resourceLoader;   // 保存资源加载器引用
    }

    /**
     * 进程启动后立即加载一次热修复 JSON。
     */
    @PostConstruct // Bean 就绪后由 Spring 调用
    public void loadOnStartup() {
        reload(); // 与 /reload 共用同一套加载逻辑
    }

    /**
     * 从配置路径重新加载热修复数据。
     *
     * @return true 表示成功替换内存中的热修复数据
     */
    public boolean reload() {
        String loc = properties.getHotfix().getResource(); // 配置的 JSON 路径
        try {
            Resource resource = resourceLoader.getResource(loc); // 解析 classpath: 或 file: 路径
            if (!resource.exists()) {
                log.warn("Hotfix resource not found: {}, keeping previous data", loc); // 找不到则保留旧数据
                return false;
            }
            try (InputStream in = resource.getInputStream()) { // 自动关闭输入流
                HotfixData next = objectMapper.readValue(in, HotfixData.class); // 反序列化为 DTO
                if (next == null) {
                    next = HotfixData.empty(); // 空 JSON 时使用空对象
                }
                this.data = next; // volatile 写，读者立即可见
                log.info("HotfixData loaded: url={}, version={}, patch={}",
                        data.getClientResourceBaseUrl(), data.getHotfixVersion(), data.getPatchVersion());
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to reload HotfixData from {}", loc, e); // 解析失败保留旧数据
            return false;
        }
    }

    /**
     * 获取当前生效的热修复数据。
     *
     * @return 当前热修复 DTO（调用方勿修改内部可变字段）
     */
    public HotfixData current() {
        return data; // 返回 volatile 引用的当前快照
    }
}
