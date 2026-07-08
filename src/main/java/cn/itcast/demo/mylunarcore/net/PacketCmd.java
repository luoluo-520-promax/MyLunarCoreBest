// 命令号与处理方法绑定的注解
package cn.itcast.demo.mylunarcore.net;

// 注解会出现在 Javadoc 中
import java.lang.annotation.Documented;
// 注解只能标在方法上
import java.lang.annotation.ElementType;
// 注解保留到运行时，供启动扫描
import java.lang.annotation.Retention;
// RetentionPolicy：指定注解保留级别
import java.lang.annotation.RetentionPolicy;
// 注解使用位置声明
import java.lang.annotation.Target;

/**
 * 把网络命令 ID 绑定到 Spring Bean 的某个实例方法；启动时由 {@link PacketCommandRegistry} 扫描注册，运行期 Map 查表分发（无 switch、无每次反射）。
 */
@Documented // 生成文档时保留说明
@Target(ElementType.METHOD) // 仅用于方法
@Retention(RetentionPolicy.RUNTIME) // 运行时可通过反射读取
public @interface PacketCmd {

    /** 客户端请求命令字，取值与 {@link cn.itcast.demo.mylunarcore.net.CmdIds} 一致 */
    int value();
}
