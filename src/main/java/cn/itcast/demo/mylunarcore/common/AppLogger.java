// 按日志分类构造 SLF4J Logger
package cn.itcast.demo.mylunarcore.common;

// SLF4J Logger 接口
import org.slf4j.Logger;
// SLF4J Logger 工厂
import org.slf4j.LoggerFactory;

/**
 * 统一日志构建入口：按 {@link LogCategory} 获取 SLF4J Logger，与 Logback 分类输出策略对齐。
 */
public final class AppLogger {

    /**
     * 工具类禁止实例化。
     */
    private AppLogger() {
    }

    /**
     * 按日志分类与调用类创建 SLF4J Logger。
     *
     * @param category 日志分类（决定 logger 名称前缀）
     * @param clazz    当前记录日志的类（用于定位）
     * @return SLF4J Logger
     */
    public static Logger logger(LogCategory category, Class<?> clazz) {
        return LoggerFactory.getLogger(category.loggerName(clazz)); // 委托工厂按分类名创建/复用 Logger
    }
}
