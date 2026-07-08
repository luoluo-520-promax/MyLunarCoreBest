// 静态表格资源注册表：启动登记路径，支持热失效后延迟重载
package cn.itcast.demo.mylunarcore.common;

// @ResourceType 元数据注解来源
import cn.itcast.demo.mylunarcore.config.ResourceType;
// Bean 初始化完成后执行的回调注解
import jakarta.annotation.PostConstruct;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring ResourceLoader
import org.springframework.core.io.ResourceLoader;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// 不可修改视图包装
import java.util.Collections;
// 插入顺序稳定的 Map
import java.util.LinkedHashMap;
// Map 接口
import java.util.Map;
// Optional 容器
import java.util.Optional;

/**
 * 启动时注册 {@link ResourceType} 与资源路径；具体解析在首次 {@link TabularStaticResource#getRows()} 时进行（延迟加载）。
 */
@Component // 随 Spring 容器启动注册
public class StaticResourceRegistry {

    // 本类专用 Logger（分类 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, StaticResourceRegistry.class);

    // Spring 资源加载器
    private final ResourceLoader resourceLoader;
    // 静态资源工厂 Bean
    private final GameResourceFactory gameResourceFactory;
    // resourceType -> 表格资源实例（LinkedHashMap 保持注册顺序）
    private final Map<String, TabularStaticResource> byType = new LinkedHashMap<>();

    /**
     * 构造器注入资源加载器与工厂。
     *
     * @param resourceLoader      Spring 注入
     * @param gameResourceFactory 工厂 Bean
     */
    public StaticResourceRegistry(ResourceLoader resourceLoader, GameResourceFactory gameResourceFactory) {
        this.resourceLoader = resourceLoader;               // 保存资源加载器
        this.gameResourceFactory = gameResourceFactory;     // 保存资源工厂
    }

    /**
     * 扫描 {@link StaticResourceId} 枚举并注册全部已知表格资源。
     */
    @PostConstruct // Bean 就绪后由 Spring 调用
    public void registerKnownResources() {
        for (StaticResourceId id : StaticResourceId.values()) { // 遍历所有已知静态资源
            ResourceType rt = id.resourceType(); // 读取 @ResourceType 元数据
            String key = rt == null ? id.name() : rt.value(); // 无注解时用枚举名作 key
            byType.put(key, gameResourceFactory.createGameResource(GameResourceKind.TABULAR, id.getLocation(), resourceLoader));
            log.debug("Registered static resource type={} location={}", key, id.getLocation());
        }
    }

    /**
     * 按资源类型查找已注册的表格资源。
     *
     * @param resourceType {@link ResourceType#value()} 或枚举名（无注解时）
     * @return Optional 包装的表格资源
     */
    public Optional<TabularStaticResource> getByType(String resourceType) {
        return Optional.ofNullable(byType.get(resourceType)); // 未注册时返回 empty
    }

    /**
     * /reload 时丢弃已缓存表格，下次访问重新加载。
     */
    public void reloadAll() {
        for (TabularStaticResource r : byType.values()) { // 逐个失效已缓存资源
            r.invalidate(); // 标记需延迟重载
        }
        log.info("Invalidated {} tabular static resources (lazy reload on next access)", byType.size());
    }

    /**
     * 获取当前注册表快照。
     *
     * @return 不可修改的注册表视图
     */
    public Map<String, TabularStaticResource> snapshot() {
        return Collections.unmodifiableMap(byType); // 防止外部修改内部 Map
    }
}
