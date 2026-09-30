package cn.itcast.demo.mylunarcore.net;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注解驱动协议文档：标记 CmdId 与用途，供 docs 生成工具提取并同步 protocol-docs.md。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface ProtoCommand {

    /** 命令号，默认从字段常量读取时可填 0。 */
    int cmdId() default 0;

    /** 简短用途说明。 */
    String purpose();

    /** 所属系统：battle/hall/scene/assist/... */
    String system() default "";

    /** 是否为服务端推送 Notify。 */
    boolean pushNotify() default false;
}
