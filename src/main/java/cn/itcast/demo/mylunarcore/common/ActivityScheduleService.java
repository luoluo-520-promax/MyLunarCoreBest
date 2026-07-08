// 活动排期与开放时间判断所在包
package cn.itcast.demo.mylunarcore.common;

// 全局配置（活动 JSON 资源路径等）
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// Jackson：泛型 List 反序列化时需要 TypeReference
import com.fasterxml.jackson.core.type.TypeReference;
// Jackson：JSON 与 Java 对象互转
import com.fasterxml.jackson.databind.ObjectMapper;
// Bean 创建后执行加载
import jakarta.annotation.PostConstruct;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger
import lombok.Getter;
import org.slf4j.Logger;
// Spring 组件注解
import org.springframework.stereotype.Component;
// Spring 抽象资源（classpath:、file: 等）
import org.springframework.core.io.Resource;
// 按路径加载 Resource
import org.springframework.core.io.ResourceLoader;

// 输入流，读取 JSON 文件
import java.io.InputStream;
// 时间点（毫秒转日期）
import java.time.Instant;
// 本地日期（不含时分秒）
import java.time.LocalDate;
// 时区，用于把毫秒转为“服务器本地日期”
import java.time.ZoneId;
// 空列表常量
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * 从 {@code ActivityScheduling.json} 加载活动时间窗（Unix 秒），
 * 在游戏 Tick 中做每日重置检测，并提供活动是否开放查询。
 */
@Component // Spring 单例 Bean
public class ActivityScheduleService {

    // 活动相关业务的日志
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityScheduleService.class);

    // 配置：活动表 JSON 在 classpath 或磁盘上的位置
    private final LunarCoreProperties properties;
    // 用于 getResource("classpath:...") 等
    private final ResourceLoader resourceLoader;
    // JSON 解析器（每个服务持有一个实例即可）
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @return 当前加载的活动时间窗列表（只读语义，勿在外部修改元素）
     */
    // 内存中的活动时间窗列表，加载失败时为空列表
    @Getter
    private List<ActivityWindow> windows = Collections.emptyList();
    // 上次已处理的“服务器本地日期”，用于检测跨天
    private volatile LocalDate lastResetDay;

    /**
     * 构造器注入配置与资源加载器。
     */
    public ActivityScheduleService(LunarCoreProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 应用启动后自动加载活动排期表。
     */
    @PostConstruct
    public void loadSchedule() {
        reloadSchedule(); // 与热重载共用同一套逻辑
    }

    /**
     * 重新从配置文件加载排期；热更新协调器在 /reload 时也会调用。
     */
    public void reloadSchedule() {
        String loc = properties.getActivityScheduleResource(); // 如 classpath:data/ActivityScheduling.json
        try {
            Resource resource = resourceLoader.getResource(loc);
            if (!resource.exists()) {
                log.warn("Activity schedule resource not found: {}", loc);
                return;
            }
            try (InputStream in = resource.getInputStream()) { // try-with-resources 自动关闭流
                // 将 JSON 数组解析为 List<ActivityWindow>
                List<ActivityWindow> list = objectMapper.readValue(in, new TypeReference<List<ActivityWindow>>() {});
                // null 安全：拷贝为不可变列表，防止外部修改
                this.windows = list == null ? Collections.emptyList() : List.copyOf(list);
                log.info("Loaded {} activity schedule entries from {}", windows.size(), loc);
            }
        } catch (Exception e) {
            log.error("Failed to load activity schedule from {}", loc, e);
        }
    }

    /**
     * 每个游戏 Tick 调用：检测是否跨“服务器本地日”，跨天时打日志（可扩展发奖等）。
     *
     * @param nowMillis   当前时间毫秒
     * @param deltaMillis 与上一 Tick 的时间差（本方法未使用，预留扩展）
     */
    public void onTick(long nowMillis, long deltaMillis) {
        ZoneId zone = ZoneId.systemDefault(); // 服务器默认时区
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate();
        if (lastResetDay == null) {
            lastResetDay = today; // 首次 Tick 只记录日期，不触发重置
            return;
        }
        if (!today.equals(lastResetDay)) {
            log.info("Daily reset: server date {} -> {}", lastResetDay, today);
            lastResetDay = today; // 更新为今天，后续可在此挂每日任务
        }
    }

    /**
     * 判断指定活动 ID 在当前服务器时间是否处于开放窗口内。
     *
     * @param activityId 活动配置 ID
     * @return 若在任一时间窗 [beginTime, endTime] 内（秒级）则 true
     */
    public boolean isActivityActive(int activityId) {
        long nowSec = Instant.now().getEpochSecond(); // 当前 Unix 秒
        for (ActivityWindow w : windows) {
            if (w.activityId == activityId && nowSec >= w.beginTime && nowSec <= w.endTime) {
                return true;
            }
        }
        return false;
    }

    /**
     * 对应 ActivityScheduling.json 中单行数据的 Java 映射。
     * 字段为 public，便于 Jackson 无参构造 + 字段赋值。
     */
    public static final class ActivityWindow {
        /** 活动 ID */
        public int activityId;
        /** 开放开始时间（Unix 秒） */
        public long beginTime;
        /** 开放结束时间（Unix 秒） */
        public long endTime;
        /** 关联玩法模块 ID */
        public long moduleId;
    }
}
