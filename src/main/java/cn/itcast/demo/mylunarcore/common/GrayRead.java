package cn.itcast.demo.mylunarcore.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记业务读路径必须按灰度策略分支（canary vs stable）。
 * 配合 {@link GrayReadAspect}：方法参数需包含 {@code long/int uid} 与可选 {@code String serverId}。
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface GrayRead {
    /** 配置逻辑名，写入 MDC / 日志便于审计。 */
    String value() default "";
}
