// /reload 聚合入口：热修复、活动、抽卡配置与静态资源失效
package cn.itcast.demo.mylunarcore.common;

// 活动排期服务（reloadSchedule）
import cn.itcast.demo.mylunarcore.common.ActivityScheduleService;
// 抽卡配置服务（reload）
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
// 客户端热修复 JSON 加载
import cn.itcast.demo.mylunarcore.common.HotfixDataService;
// 静态表格资源注册表
import cn.itcast.demo.mylunarcore.common.StaticResourceRegistry;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

/**
 * 聚合配置热更新与资源热更新入口。不包含 JVM 代码热替换（不支持代码热更新）。
 */
@Component // 随 Spring 容器启动注册
public class HotReloadCoordinator {

    // 本类专用 Logger（分类 SYSTEM）
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, HotReloadCoordinator.class);

    // 热修复 JSON 加载服务
    private final HotfixDataService hotfixDataService;
    // 活动排期 JSON 加载服务
    private final ActivityScheduleService activityScheduleService;
    // 抽卡卡池配置加载服务
    private final GachaConfigService gachaConfigService;
    // CSV 等静态表格资源注册表
    private final StaticResourceRegistry staticResourceRegistry;

    /**
     * 构造器注入各子模块热重载服务。
     */
    public HotReloadCoordinator(HotfixDataService hotfixDataService,
                                ActivityScheduleService activityScheduleService,
                                GachaConfigService gachaConfigService,
                                StaticResourceRegistry staticResourceRegistry) {
        this.hotfixDataService = hotfixDataService;               // 保存热修复服务
        this.activityScheduleService = activityScheduleService;   // 保存活动排期服务
        this.gachaConfigService = gachaConfigService;             // 保存抽卡配置服务
        this.staticResourceRegistry = staticResourceRegistry;     // 保存静态资源注册表
    }

    /**
     * 与启动时一致：热修复数据 + 活动排期 + 抽卡配置 + 表格静态资源缓存失效。
     */
    public void reloadAll() {
        log.info("=== /reload: hotfix + activity schedule + gacha + static tabular resources ===");
        hotfixDataService.reload();                  // 重新加载 hotfix.json
        activityScheduleService.reloadSchedule();    // 重新加载活动排期表
        gachaConfigService.reload();                 // 重新加载抽卡卡池配置
        staticResourceRegistry.reloadAll();          // 失效全部已缓存静态表格
    }
}
