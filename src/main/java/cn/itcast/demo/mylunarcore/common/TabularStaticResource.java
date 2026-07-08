// 表格类静态资源：延迟读取 CSV 并缓存行数组
package cn.itcast.demo.mylunarcore.common;

// 日志门面
// 日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J
// SLF4J
import lombok.Getter;
import org.slf4j.Logger;

// Spring 抽象资源
// Spring 抽象资源
import org.springframework.core.io.Resource;

// Spring ResourceLoader
// Spring ResourceLoader
import org.springframework.core.io.ResourceLoader;

// 缓冲字符读入
// 缓冲字符读入
import java.io.BufferedReader;

// 字节流转字符流（UTF-8）
// 字节流转字符流（UTF-8）
import java.io.InputStreamReader;

// UTF-8 字符集常量
// UTF-8 字符集常量
import java.nio.charset.StandardCharsets;

// 可变列表承载行数据
// 可变列表承载行数据
import java.util.ArrayList;

// 不可变空列表
// 不可变空列表
import java.util.Collections;

// 列表接口
// 列表接口
import java.util.List;

/**

 * 表格类静态资源（如 Excel 另存为 CSV）：首次访问时通过 {@link ResourceLoader} 解析，减少启动内存占用。

 */

public class TabularStaticResource {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, TabularStaticResource.class); // 本类日志

    /**
     * -- GETTER --
     *
     * @return 资源路径字符串
     */ // 返回 resourceLocation
    @Getter
    private final String resourceLocation; // 资源路径

    private final ResourceLoader resourceLoader; // 资源定位器

    private volatile List<String[]> rows; // 解析缓存在堆中；null 表示尚未加载

    /**

     * @param resourceLocation classpath: 等路径

     * @param resourceLoader   Spring 资源加载器

     */

    public TabularStaticResource(String resourceLocation, ResourceLoader resourceLoader) {

        this.resourceLocation = resourceLocation; // 保存注入的 resourceLocation 引用

        this.resourceLoader = resourceLoader; // 保存注入的 resourceLoader 引用

    }

    /** 延迟加载：首次调用时读盘并缓存。 */

    public List<String[]> getRows() {

        List<String[]> local = rows; // volatile 单次读，双重检查锁定模式

        if (local != null) { // 条件分支

            return local; // 返回 local

        }

        synchronized (this) {

            if (rows == null) { // 条件分支

                rows = load(); // 首次进入 critical section 才读盘

            }

            return rows; // 返回 rows

        }

    }

    /**

     * 丢弃缓存，使下次 {@link #getRows()} 重新加载（热重载配合使用）。

     */

    public void invalidate() {

        synchronized (this) {

            rows = null; // 释放引用，允许 GC 回收旧列表

        }

    }

    /**

     * 实际读文件并拆分为 CSV 行。

     */

    private List<String[]> load() {

        try {

            Resource res = resourceLoader.getResource(resourceLocation); // 解析为 Spring Resource

            if (!res.exists()) { // 条件分支

                log.warn("Tabular resource not found: {}", resourceLocation); // 文件缺失告警

                return Collections.emptyList(); // 返回结果

            }

            List<String[]> list = new ArrayList<>();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) { // try-with-resources 自动关闭流

                String line;

                while ((line = br.readLine()) != null) {

                    if (line.isBlank() || line.startsWith("#")) { // 条件分支

                        continue; // 跳过空行与注释行

                    }

                    list.add(line.split(",", -1)); // 保留尾部空列

                }

            }

            log.info("Loaded tabular resource {} rows={}", resourceLocation, list.size()); // 记录运行日志

            return Collections.unmodifiableList(list); // 对外不可变

        } catch (Exception e) {

            log.error("Failed to load tabular resource {}", resourceLocation, e); // 记录运行日志

            return Collections.emptyList(); // 失败返回空表，避免 NPE

        }

    }

}

