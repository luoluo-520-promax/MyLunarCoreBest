// 内置静态资源枚举：绑定 classpath 路径与 ResourceType 注解

// 业务模块
package cn.itcast.demo.mylunarcore.common;

// 资源类型字符串注解

// 本项目业务类
import cn.itcast.demo.mylunarcore.config.ResourceType;
import lombok.Getter;

/**

 * 内置静态资源枚举：Excel 等表格建议导出为 CSV 后放入 classpath，由 {@link TabularStaticResource} 解析。

 */

@Getter
public enum StaticResourceId {

    @ResourceType("item_config")

    ITEM_CONFIG("classpath:data/items_config.csv"); // 示例：物品配置表

    /**
     * -- GETTER --
     *
     * @return 资源路径
     */
    private final String location; // Spring ResourceLoader 可解析的位置字符串

    /**

     * @param location classpath: 或 file: 等资源路径

     */

    StaticResourceId(String location) {

        this.location = location;

    }

    /**

     * @return 枚举常量字段上的 {@link ResourceType}；反射失败返回 null

     */

    ResourceType resourceType() {

        try {

            return StaticResourceId.class.getField(name()).getAnnotation(ResourceType.class); // 读取枚举常量字段注解

        } catch (NoSuchFieldException e) {

            return null;

        }

    }

}

