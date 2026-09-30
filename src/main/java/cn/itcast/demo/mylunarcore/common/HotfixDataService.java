// 从 classpath/file 加载 HotfixData 并与 /reload 联动
package cn.itcast.demo.mylunarcore.common;

// 全局配置（hotfix.resource 路径）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Jackson 对象映射器：负责把 JSON 反序列化为 HotfixData
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
 * <p>该服务只负责「当前生效热修复数据」的单例快照，不直接处理 Netty 推送；
 * 推送逻辑由 {@link VersionNettyService} 和 {@link UpdateNotifyBroadcaster} 负责。</p>
 */
@Service
public class HotfixDataService {

    // 本类专用 Logger（分类 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, HotfixDataService.class);

    // 配置前缀 lunarcore.hotfix.*，决定 hotfix.json 的读取位置
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
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 进程启动后立即加载一次热修复 JSON。
     * <p>这样后续业务在服务启动完成时即可直接读取到当前热修复配置，
     * 不必等到第一次请求再做懒加载。</p>
     */
    @PostConstruct
    public void loadOnStartup() {
        reload();
    }

    /**
     * 从配置路径重新加载热修复数据。
     *
     * @return true 表示成功替换内存中的热修复数据；false 表示保留旧数据并由日志提示原因
     */
    public boolean reload() {
        String loc = properties.getHotfix().getResource();
        try {
            Resource resource = resourceLoader.getResource(loc);
            if (!resource.exists()) {
                // 资源不存在时不清空旧数据：避免临时发布缺文件导致线上直接失去热修复配置
                log.warn("Hotfix resource not found: {}, keeping previous data", loc);
                return false;
            }
            try (InputStream in = resource.getInputStream()) {
                HotfixData next = objectMapper.readValue(in, HotfixData.class);
                if (next == null) {
                    // JSON 为空或解析结果为 null 时，统一回落到全默认对象，避免调用方 NPE
                    next = HotfixData.empty();
                }
                this.data = next;
                // 打印关键字段：资源根 URL、热修复版本、补丁序号，便于排查线上配置是否命中
                log.info("HotfixData loaded: url={}, version={}, patch={}",
                        data.getClientResourceBaseUrl(), data.getHotfixVersion(), data.getPatchVersion());
                return true;
            }
        } catch (Exception e) {
            // 解析失败时保留旧配置，避免半成品 JSON 覆盖线上可用快照
            log.error("Failed to reload HotfixData from {}", loc, e);
            return false;
        }
    }

    /**
     * 热更失败时恢复上一份内存快照。
     *
     * @param previous 热更前的旧数据；可为 null（表示恢复为全默认对象）
     */
    public void restore(HotfixData previous) {
        this.data = previous == null ? HotfixData.empty() : previous;
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
