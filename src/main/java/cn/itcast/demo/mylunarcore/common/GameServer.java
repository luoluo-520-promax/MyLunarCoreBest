// 游戏主循环与在线玩家 Tick 所在包
package cn.itcast.demo.mylunarcore.common;

// 活动排期服务：按游戏 Tick 做每日重置等
import cn.itcast.demo.mylunarcore.common.ActivityScheduleService;
// 全局配置（含游戏循环开关与周期）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Spring Bean 作用域常量（如 SINGLETON）
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
// 声明 Bean 是单例还是原型等作用域
import org.springframework.context.annotation.Scope;
// Bean 创建完成后的初始化回调注解
import jakarta.annotation.PostConstruct;
// Bean 销毁前的清理回调注解
import jakarta.annotation.PreDestroy;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类（系统、业务等）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// 标记为 Spring 管理的组件
import org.springframework.stereotype.Component;

// JDK 定时器：按固定周期执行任务
import java.util.Timer;
// 定时器要执行的一次性任务抽象类
import java.util.TimerTask;

/**
 * 游戏主循环：用 {@link Timer} 周期性调用 {@link #onTick()}，
 * 驱动在线玩家状态与全局活动等与时间相关的逻辑。
 */
@Component // 注册为 Spring Bean，可被其他类注入
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON) // 全进程只有一个 GameServer 实例
public class GameServer {

    // 本类使用的日志记录器（分类为 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GameServer.class);

    // 注入的配置：含 game-loop.enabled、periodMs 等
    private final LunarCoreProperties properties;
    // 在线玩家注册表：提供 snapshot 供 Tick 遍历
    private final PlayerTickRegistry playerTickRegistry;
    // 活动排期：在 Tick 中做日切等
    private final ActivityScheduleService activityScheduleService;

    // 游戏循环用的 JDK Timer，stop 时置 null
    private Timer gameLoopTimer;
    // 上一帧 Tick 的时间戳（毫秒），用于计算 delta
    private long lastTickMillis;

    /**
     * 构造器注入：Spring 自动传入三个依赖 Bean。
     */
    public GameServer(LunarCoreProperties properties,
                      PlayerTickRegistry playerTickRegistry,
                      ActivityScheduleService activityScheduleService) {
        this.properties = properties;
        this.playerTickRegistry = playerTickRegistry;
        this.activityScheduleService = activityScheduleService;
    }

    /**
     * Bean 初始化后启动游戏循环定时器。
     */
    @PostConstruct
    public void start() {
        // 配置关闭游戏循环时直接返回，不创建 Timer
        if (!properties.getGameLoop().isEnabled()) {
            log.info("Game loop disabled (lunarcore.game-loop.enabled=false)");
            return;
        }
        // 周期至少 50ms，防止配置过小导致 CPU 飙高
        long period = Math.max(50L, properties.getGameLoop().getPeriodMs());
        lastTickMillis = System.currentTimeMillis(); // 记录起始时间
        // 守护线程 Timer：JVM 退出时不会阻塞进程结束
        gameLoopTimer = new Timer("game-loop-timer", true);
        // 按固定频率 schedule：首次延迟 period，之后每 period 执行一次
        gameLoopTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    onTick(); // 单帧逻辑
                } catch (Exception e) {
                    // 单帧异常不终止整个循环，只打错误日志
                    log.error("Game loop tick failed", e);
                }
            }
        }, period, period);
        log.info("GameServer game loop started: Timer periodMs={}", period);
    }

    /**
     * 应用关闭时取消定时器，释放资源。
     */
    @PreDestroy
    public void stop() {
        if (gameLoopTimer != null) {
            gameLoopTimer.cancel(); // 取消所有已调度任务
            gameLoopTimer = null;
        }
    }

    /**
     * 每一帧游戏逻辑：先 Tick 所有在线玩家，再 Tick 活动排期。
     * 包内可见，主要由 Timer 任务调用。
     */
    void onTick() {
        long now = System.currentTimeMillis(); // 当前帧时间
        long delta = now - lastTickMillis; // 与上一帧的时间差（毫秒）
        lastTickMillis = now; // 更新上一帧时间

        // 遍历当前在线玩家快照（避免并发修改注册表）
        for (OnlinePlayer player : playerTickRegistry.snapshotOnlinePlayers()) {
            try {
                player.onTick(now, delta); // 每个玩家独立 Tick（移动、体力等）
            } catch (Exception e) {
                // 单个玩家异常不影响其他玩家
                log.warn("Player tick failed, uid={}, isolating error", player.getUid(), e);
            }
        }

        try {
            activityScheduleService.onTick(now, delta); // 全局活动日切等
        } catch (Exception e) {
            log.warn("Activity schedule tick failed", e);
        }
    }
}
