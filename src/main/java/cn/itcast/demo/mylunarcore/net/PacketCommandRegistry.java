// 启动时扫描 @PacketCmd 并注册 cmdId → Handler
package cn.itcast.demo.mylunarcore.net;

// 业务包（方法签名需要）
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// 解析 Spring AOP 代理背后的真实类
import org.springframework.aop.support.AopUtils;
// Spring ApplicationContext：遍历 Bean 定义与实例
import org.springframework.context.ApplicationContext;
// 声明 Spring 组件（可被扫描）
import org.springframework.stereotype.Component;
// Spring 反射工具：doWithMethods
import org.springframework.util.ReflectionUtils;

// PostConstruct：Bean 就绪后执行注册
import jakarta.annotation.PostConstruct;
// MethodHandle：高性能绑定调用
import java.lang.invoke.MethodHandle;
// Lookup：创建 MethodHandle
import java.lang.invoke.MethodHandles;
// Method：反射方法对象
import java.lang.reflect.Method;
// Modifier：判断 static 等修饰符
import java.lang.reflect.Modifier;
// HashMap：cmdId → handler
import java.util.HashMap;
// Map 接口
import java.util.Map;

/**
 * 启动时扫描 Spring 容器中带 {@link PacketCmd} 的方法，绑定为 {@link PacketCommandHandler}（MethodHandle），
 * 运行期仅 Map 查找与 invoke，无 switch、无每次反射。
 */
@Component // 注册为 Spring Bean，启动期执行扫描
public class PacketCommandRegistry {

    private final ApplicationContext applicationContext; // Spring 容器引用
    // cmdId → 可调用处理器
    private final Map<Integer, PacketCommandHandler> handlers = new HashMap<>();

    /**
     * 构造器注入 Spring 容器。
     */
    public PacketCommandRegistry(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext; // 保存容器引用
    }

    /**
     * 扫描容器内 Bean，收集带 {@link PacketCmd} 的方法并注册处理器映射。
     */
    @PostConstruct // Bean 就绪后执行一次全量注册
    public void register() {
        String[] names = applicationContext.getBeanDefinitionNames(); // 所有 Bean 名称
        MethodHandles.Lookup lookup = MethodHandles.lookup(); // 当前模块 Lookup（用于 unreflect）
        for (String name : names) { // 逐个 Bean 名称处理
            Object bean;
            try {
                bean = applicationContext.getBean(name); // 尝试获取实例
            } catch (Exception e) {
                continue; // 无法实例化的定义跳过
            }
            Class<?> targetClass = AopUtils.getTargetClass(bean); // 解代理拿到真实类
            if (!targetClass.getName().startsWith("cn.itcast.demo.mylunarcore.")) {
                continue; // 只扫描本项目 Bean，加快启动
            }
            ReflectionUtils.doWithMethods(targetClass, method -> { // 遍历目标类方法
                PacketCmd cmd = method.getAnnotation(PacketCmd.class); // 读取路由注解
                if (cmd == null || Modifier.isStatic(method.getModifiers())) {
                    return; // 仅实例方法参与路由
                }
                try {
                    registerMethod(lookup, bean, method); // 绑定 MethodHandle
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to bind PacketCmd handler: " + method, e); // 绑定失败视为启动错误
                }
            }, m -> m.getAnnotation(PacketCmd.class) != null); // 过滤器：仅带注解的方法
        }
    }

    /**
     * 将单个 {@link PacketCmd} 方法编译为 {@link PacketCommandHandler} 并放入映射表。
     */
    private void registerMethod(MethodHandles.Lookup lookup, Object bean, Method method) throws IllegalAccessException {
        PacketCmd cmd = method.getAnnotation(PacketCmd.class); // 再次读取注解（获取 cmdId）
        int cmdId = cmd.value(); // 路由键
        if (handlers.containsKey(cmdId)) {
            throw new IllegalStateException("Duplicate PacketCmd for cmdId=" + cmdId + " method=" + method); // 禁止重复注册
        }
        method.setAccessible(true); // 允许调用非 public 方法
        MethodHandle mh = lookup.unreflect(method).bindTo(bean); // 预绑定接收者，调用更快
        handlers.put(cmdId, (ctx, packet) -> { // 放入 Map：lambda 包装 MethodHandle.invoke
            try {
                mh.invoke(ctx, packet); // 反射式调用目标方法
            } catch (Throwable t) {
                if (t instanceof Exception) {
                    throw (Exception) t; // 业务可检异常向上抛
                }
                throw new RuntimeException(t); // 其他 Throwable 包装为运行时异常
            }
        });
    }

    /** 按命令号取处理器；未注册返回 null */
    public PacketCommandHandler getHandler(int cmdId) {
        return handlers.get(cmdId); // Map 查找
    }
}
