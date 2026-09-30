// 多节点部署前置校验组件所在包
package cn.itcast.demo.mylunarcore.config;

// 统一日志工厂：按日志分类创建 Logger
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举：SYSTEM 表示系统/框架级日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志门面接口
import org.slf4j.Logger;
// 声明为 Spring 管理的组件（构造器在容器启动阶段执行）
import org.springframework.stereotype.Component;

/**
 * 多节点拓扑未开 Redis 时 fail-fast，避免票据/在线表/排行榜节点间分叉。
 * <p>
 * 背景：当 {@code center.mode=remote}（多游戏节点 + 独立中心服）时，玩家迁移票据、
 * 在线 UID 集合、排行榜等状态必须存放在所有节点共享的存储中；若仍然使用进程内
 * 内存实现，不同节点看到的状态会互相不一致（例如 A 节点发放迁移票据、B 节点读不到）。
 * 本组件在 Spring 容器启动（构造器执行）阶段直接抛出异常中断启动，比运行期才发现
 * 数据分叉更安全。
 */
@Component // 注册为 Spring Bean；Spring 会在启动时调用其构造器执行校验
public class MultiNodeRedisGuard {

    // 本类专用日志对象：归类到 SYSTEM 分类
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, MultiNodeRedisGuard.class);

    /**
     * 启动期校验：中心服为 remote 模式时必须同时启用 Redis。
     * <p>
     * 校验规则：
     * <ul>
     *   <li>mode 取值大小写不敏感（trim 后忽略大小写比较），兼容配置文件写法差异；</li>
     *   <li>{@code center == null} 时按 local 处理（单节点拓扑，不强制 Redis）；</li>
     *   <li>mode=remote 且 Redis 未启用 → 抛 {@link IllegalStateException}，应用启动失败；</li>
     *   <li>mode=remote 且 Redis 已启用 → 打印通过日志。</li>
     * </ul>
     *
     * @param properties 全局配置（由 Spring 构造器注入）
     */
    public MultiNodeRedisGuard(LunarCoreProperties properties) {
        // 中心服配置可能为 null（未配置 lunarcore.center.*），此时按单机 local 处理
        String mode = properties.getCenter() == null ? "local" : properties.getCenter().getMode();
        // 是否远程中心服模式：忽略大小写与首尾空格，兼容 "REMOTE" / " remote " 等写法
        boolean remote = mode != null && "remote".equalsIgnoreCase(mode.trim());
        // 是否启用了 Redis：getRedis() 有默认对象兜底，不会为 null
        boolean redisOn = properties.getRedis() != null && properties.getRedis().isEnabled();
        // 核心校验：多节点模式必须共享 Redis，否则票据/在线表/排行榜无法跨节点一致
        if (remote && !redisOn) {
            // 拼装错误信息：指明缺失的关键配置项
            String msg = "center.mode=remote requires lunarcore.redis.enabled=true (shared Redis for tickets/online/leaderboard)";
            // 先输出 error 日志（即使后续被上层捕获，日志中也保留现场）
            log.error(msg);
            // fail-fast：抛异常中断 Spring 启动，迫使运维先修正配置
            throw new IllegalStateException(msg);
        }
        // remote + redis 开启属于正常配置，打印确认日志便于启动排查
        if (remote) {
            log.info("Multi-node Redis guard passed (center.mode=remote, redis.enabled=true)");
        }
    }
}
