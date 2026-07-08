// 静态资源类型注解所在包
package cn.itcast.demo.mylunarcore.config;

// 注解会出现在 JavaDoc 中
import java.lang.annotation.Documented;
// 注解可标注的元素类型（字段、类、方法等）
import java.lang.annotation.ElementType;
// 注解保留策略：编译后仍可在运行时通过反射读取
import java.lang.annotation.Retention;
// 引入 RetentionPolicy
import java.lang.annotation.RetentionPolicy;
// 注解可应用的目标（本注解用于 FIELD、TYPE、METHOD）
import java.lang.annotation.Target;

/**
 * 标记静态资源类型（如由 Excel 导出或表格文件），供 {@link cn.itcast.demo.mylunarcore.common.StaticResourceRegistry} 按类型注册与延迟加载。
 */
@Documented // 生成文档时包含本注解说明
@Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD}) // 可标在字段、类、方法上
@Retention(RetentionPolicy.RUNTIME) // 运行时保留，供注册表扫描
public @interface ResourceType { // @interface 表示这是一个注解类型，不是普通接口

    /** 资源类型标识，全局唯一。 */
    String value(); // 注解唯一属性，使用时写作 @ResourceType("xxx")
}
