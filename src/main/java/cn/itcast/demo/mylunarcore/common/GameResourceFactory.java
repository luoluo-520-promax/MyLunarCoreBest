// 静态资源工厂：按种类创建表格资源实例
package cn.itcast.demo.mylunarcore.common;

// Spring ResourceLoader：classpath 等资源定位
import org.springframework.core.io.ResourceLoader;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

/**
 * 工厂方法模式：按 {@link GameResourceKind} 扩展不同资源加载/封装逻辑，当前实现以表格资源为主。
 */
@Component // 注册为 Spring Bean
public class GameResourceFactory {

    /**
     * 创建表格型静态资源实例。
     *
     * @param classpathOrUrl classpath: 或 URL 路径
     * @param resourceLoader Spring 资源加载器
     * @return 表格资源封装
     */
    public TabularStaticResource createTabularResource(String classpathOrUrl, ResourceLoader resourceLoader) {
        return new TabularStaticResource(classpathOrUrl, resourceLoader); // 直接构造延迟加载实例
    }

    /**
     * 工厂方法入口：新增资源种类时可在此分支扩展，无需改动调用方枚举以外的契约。
     */
    public TabularStaticResource createGameResource(GameResourceKind kind, String classpathOrUrl, ResourceLoader resourceLoader) {
        if (kind == GameResourceKind.TABULAR) { // 当前仅支持表格资源
            return createTabularResource(classpathOrUrl, resourceLoader); // 委托表格分支
        }
        throw new IllegalArgumentException("Unsupported GameResourceKind: " + kind); // 未知种类直接失败
    }
}
