// 配置灰度判定切面：把当前请求是否命中灰度写入 MDC，供日志/业务分支读取
package cn.itcast.demo.mylunarcore.common;

// AOP 环绕通知切点参数
import org.aspectj.lang.ProceedingJoinPoint;
// 环绕通知注解
import org.aspectj.lang.annotation.Around;
// 标记切面类
import org.aspectj.lang.annotation.Aspect;
// 读取方法签名与参数名
import org.aspectj.lang.reflect.MethodSignature;
// Mapped Diagnostic Context：把灰度结果附加到当前线程日志上下文
import org.slf4j.MDC;
// Spring 组件
import org.springframework.stereotype.Component;

// 反射获取方法参数
import java.lang.reflect.Method;
// 参数对象
import java.lang.reflect.Parameter;

/**
 * {@link GrayRead} 切面：将 inGray 结果写入 MDC，供读路径显式分支，降低灰度配置误全量风险。
 * <p>切面本身不决定配置内容，只负责把「当前调用属于灰度还是稳定」这件事
 * 写入线程上下文，供后续日志和业务代码读取。</p>
 */
@Aspect
@Component
public class GrayReadAspect {

    /**
     * MDC 中保存「当前请求是否命中灰度」的键名。
     */
    public static final String MDC_IN_GRAY = "configInGray";
    /**
     * MDC 中保存灰度配置逻辑名的键名，便于日志里定位具体配置。
     */
    public static final String MDC_GRAY_NAME = "grayConfigName";

    // 灰度决策器：根据 uid/serverId 判断该请求属于灰度还是稳定
    private final ConfigGrayRelease grayRelease;

    /**
     * 构造器注入灰度发布器。
     */
    public GrayReadAspect(ConfigGrayRelease grayRelease) {
        this.grayRelease = grayRelease;
    }

    /**
     * 拦截带 {@link GrayRead} 注解的方法或类：先提取 uid/serverId，再计算灰度命中结果并写入 MDC。
     *
     * @param pjp      原始方法调用上下文
     * @param grayRead 注解实例，可携带配置逻辑名
     * @return 原方法执行结果
     * @throws Throwable 透传原方法异常，避免切面吞错
     */
    @Around("@annotation(grayRead) || @within(grayRead)")
    public Object around(ProceedingJoinPoint pjp, GrayRead grayRead) throws Throwable {
        long uid = extractUid(pjp);
        String serverId = extractServerId(pjp);
        boolean inGray = grayRelease.inGray(uid, serverId);
        String prevGray = MDC.get(MDC_IN_GRAY);
        String prevName = MDC.get(MDC_GRAY_NAME);
        try {
            // 写入当前调用对应的灰度判定结果；同一线程内后续日志可直接打印该 MDC 值
            MDC.put(MDC_IN_GRAY, Boolean.toString(inGray));
            if (grayRead != null && grayRead.value() != null && !grayRead.value().isBlank()) {
                // 如果注解指定了逻辑名，一并写入，便于审计时知道是哪个配置分支
                MDC.put(MDC_GRAY_NAME, grayRead.value());
            }
            return pjp.proceed();
        } finally {
            // 恢复之前 MDC，防止线程复用时污染后续请求上下文
            restore(MDC_IN_GRAY, prevGray);
            restore(MDC_GRAY_NAME, prevName);
        }
    }

    /**
     * 供业务代码在方法内读取切面判定结果。
     *
     * @return 当前线程 MDC 中的 configInGray 是否为 true
     */
    public static boolean currentInGray() {
        return "true".equalsIgnoreCase(MDC.get(MDC_IN_GRAY));
    }

    /**
     * 恢复 MDC 里原有值：原值为空则删除键，避免保留旧请求残留。
     */
    private static void restore(String key, String prev) {
        if (prev == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, prev);
        }
    }

    /**
     * 从方法参数中提取 uid：优先匹配参数名包含 uid/playerId/player_id 的值，
     * 若未识别出命名参数，则退化为遍历实参中的 Long/Integer。
     */
    private static long extractUid(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Method method = sig.getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName().toLowerCase();
            if (name.contains("uid") || name.equals("playerid") || name.equals("player_id")) {
                return toLong(args[i]);
            }
        }
        for (Object arg : args) {
            if (arg instanceof Long l) {
                return l;
            }
            if (arg instanceof Integer n) {
                return n.longValue();
            }
        }
        return 0L;
    }

    /**
     * 从方法参数中提取 serverId：优先匹配参数名包含 server 且实参为 String 的值。
     */
    private static String extractServerId(ProceedingJoinPoint pjp) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        Parameter[] params = sig.getMethod().getParameters();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < params.length; i++) {
            String name = params[i].getName().toLowerCase();
            if (name.contains("server") && args[i] instanceof String s) {
                return s;
            }
        }
        return "";
    }

    /**
     * 将参数值统一转换为 long，兼容 Long/Integer/其它 Number。
     */
    private static long toLong(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Integer n) {
            return n.longValue();
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }
}
