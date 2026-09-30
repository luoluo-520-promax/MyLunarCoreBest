// 表格类静态资源：延迟读取 CSV 并缓存行数组
package cn.itcast.demo.mylunarcore.common;

// 日志门面：按系统分类创建 Logger
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类：SYSTEM 域
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger 接口
import lombok.Getter;
import org.slf4j.Logger;

// Spring 抽象资源：表示 classpath/file/http 等资源
import org.springframework.core.io.Resource;
// Spring ResourceLoader：把路径字符串解析为 Resource
import org.springframework.core.io.ResourceLoader;

// 缓冲字符读入
import java.io.BufferedReader;
// 字节流转字符流（UTF-8）
import java.io.InputStreamReader;
// UTF-8 字符集常量
import java.nio.charset.StandardCharsets;
// 可变列表承载解析后的 CSV 行
import java.util.ArrayList;
// 不可变空列表
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * 表格类静态资源（如 Excel 另存为 CSV）：首次访问时通过 {@link ResourceLoader} 解析，减少启动内存占用。
 * <p>该类采用「首次访问加载 + 缓存 + 热重载失效」模式：
 * 启动时只登记资源位置，真正读文件发生在 {@link #getRows()} 的首次调用；
 * 后续若配置热更则由 {@link #invalidate()} 丢弃缓存，下次再懒加载。</p>
 */
public class TabularStaticResource {

    // 本类日志：用于记录资源加载成功/失败、缺失等信息
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, TabularStaticResource.class);

    /**
     * -- GETTER --
     *
     * @return 资源路径字符串
     */ // 返回 resourceLocation
    @Getter
    // 资源定位字符串，例如 file:data/items_config.csv 或 classpath:xxx.csv
    private final String resourceLocation;

    // Spring 资源定位器：根据 resourceLocation 解析实际文件/类路径资源
    private final ResourceLoader resourceLoader;

    // 解析结果缓存：null 表示尚未加载；volatile 保证多线程读到最新引用
    private volatile List<String[]> rows;

    /**
     * @param resourceLocation classpath: 等路径
     * @param resourceLoader   Spring 资源加载器
     */
    public TabularStaticResource(String resourceLocation, ResourceLoader resourceLoader) {
        // 保存资源路径：后续加载时才真正解析
        this.resourceLocation = resourceLocation;
        // 保存资源加载器：由 Spring 注入，兼容多种资源协议
        this.resourceLoader = resourceLoader;
    }

    /** 延迟加载：首次调用时读盘并缓存。 */
    public List<String[]> getRows() {
        List<String[]> local = rows; // volatile 单次读，降低同步开销
        if (local != null) {
            // 已经加载过：直接返回缓存，避免重复 IO
            return local;
        }
        synchronized (this) {
            if (rows == null) {
                // 双重检查锁定：只在首次访问时真正进入加载分支
                rows = load();
            }
            return rows;
        }
    }

    /**
     * 丢弃缓存，使下次 {@link #getRows()} 重新加载（热重载配合使用）。
     */
    public void invalidate() {
        synchronized (this) {
            // 置空后旧列表失去强引用，下一次读将重新从文件装载
            rows = null;
        }
    }

    /** 当前已缓存行（未加载则为 null）。 */
    public List<String[]> snapshotCachedRows() {
        List<String[]> local = rows;
        return local == null ? null : List.copyOf(local);
    }

    /** 恢复热更前缓存；null 表示保持未加载。 */
    public void restoreCachedRows(List<String[]> previous) {
        synchronized (this) {
            rows = previous == null ? null : List.copyOf(previous);
        }
    }

    /**
     * 实际读文件并拆分为 CSV 行。
     * <p>解析规则：
     * <ul>
     *   <li>空行与以 {@code #} 开头的注释行跳过；</li>
     *   <li>使用 {@code split(",", -1)} 保留尾部空列，避免字段数量错位；</li>
     *   <li>加载失败时返回空表而不是抛异常，尽量避免配置缺失导致服务启动失败。</li>
     * </ul>
     * </p>
     */
    private List<String[]> load() {
        try {
            // ResourceLoader 统一解析 classpath/file/http 等路径
            Resource res = resourceLoader.getResource(resourceLocation);
            if (!res.exists()) {
                // 配置资源不存在：记录告警并返回空表，调用方应自行容错
                log.warn("Tabular resource not found: {}", resourceLocation);
                return Collections.emptyList();
            }
            List<String[]> list = new ArrayList<>();
            // UTF-8 读取，确保中文配置不会乱码
            try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) {
                        // 空行/注释行不参与表格解析
                        continue;
                    }
                    // 保留末尾空列，便于列数固定的 CSV 表格解析
                    list.add(line.split(",", -1));
                }
            }
            log.info("Loaded tabular resource {} rows={}", resourceLocation, list.size());
            return Collections.unmodifiableList(list);
        } catch (Exception e) {
            // 任意 IO/解析异常统一降级为空表：避免单个静态资源异常拖垮整服
            log.error("Failed to load tabular resource {}", resourceLocation, e);
            return Collections.emptyList();
        }
    }
}
