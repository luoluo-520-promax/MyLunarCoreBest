// 抽卡卡池 Banner 配置加载与查询所在包：负责 Banners.json 的读取、索引构建与按时间窗筛选活跃卡池
package cn.itcast.demo.mylunarcore.gacha;

// TypeReference：Jackson 泛型辅助类，用于将 JSON 数组反序列化为 List<GachaBannerConfig>
import com.fasterxml.jackson.core.type.TypeReference;
// ObjectMapper：Jackson 核心 JSON 解析器，将 Banners.json 映射为 Java 对象
import com.fasterxml.jackson.databind.ObjectMapper;
// PostConstruct：Spring Bean 初始化完成后自动回调，用于启动时加载配置
import jakarta.annotation.PostConstruct;
// AppLogger：项目统一日志门面，按业务分类输出
import cn.itcast.demo.mylunarcore.common.AppLogger;
// LogCategory：日志分类枚举，本类使用 BUSINESS_GACHA 便于过滤抽卡相关日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Logger：SLF4J 日志接口
import org.slf4j.Logger;
// Component：声明为 Spring 单例 Bean，供 GachaNettyService、GachaBannerHotReloadService 等注入使用
import org.springframework.stereotype.Component;

// File：以本地文件路径读取外部可热更的 Banners.json
import java.io.File;
// InputStream：从 classpath 读取内置配置，作为外部文件不存在时的回退
import java.io.InputStream;
// ArrayList：按 bannerType 分组时存放可变 Banner 列表
import java.util.ArrayList;
// Collections：提供 emptyList() 等不可变空集合，避免调用方对 null 做 NPE 防御
import java.util.Collections;
// List：Banner 配置列表的接口类型
import java.util.List;
// Map：bannerType → Banner 列表 的索引结构
import java.util.Map;
// ConcurrentHashMap：线程安全的 Map，支持热更 reload 与多线程并发读查询
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 卡池 Banner 配置的装载、热更与查询服务。
 * <p>
 * 这是抽卡系统的"配置读模型"，只负责把静态卡池配置（Banners.json）转成可快速查询的内存索引，
 * 不参与抽卡概率计算，也不处理玩家保底状态。这样的拆分能让热更逻辑更简单：
 * 配置变更只影响本类，抽卡业务（GachaNettyService）只读本类，不会把"配置读取"和"业务决策"缠在一起。
 * </p>
 * <p>
 * 加载策略采用"外部文件优先、classpath 回退"的双路径模式：
 * 线上运维可直接替换 {@code data/Banners.json} 实现热更；
 * 本地开发或测试环境没有外部文件时，则仍可依赖 jar 包内置资源启动。
 * </p>
 */
@Component
public class GachaConfigService {

    /** 抽卡业务专用日志记录器，便于定位卡池配置加载/热更问题 */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaConfigService.class);

    /** 外部可热更配置路径，运维替换此文件后由 GachaBannerHotReloadService 检测并重载 */
    private static final String DEFAULT_PATH = "data/Banners.json";
    /** classpath 内置回退路径，外部文件不存在时从 jar 包内读取 */
    private static final String CLASSPATH_PATH = "data/Banners.json";

    /** JSON 解析器实例，整个 Bean 生命周期复用，避免重复创建开销 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * bannerType → Banner 列表（CopyOnWrite：热更构建新 Map 后原子切换，读路径无锁）。
     */
    private final AtomicReference<Map<Integer, List<GachaBannerConfig>>> configsByTypeRef =
            new AtomicReference<>(Map.of());
    /** 灰度关闭前保留的稳定快照 */
    private volatile Map<Integer, List<GachaBannerConfig>> stableByType = Map.of();
    private final cn.itcast.demo.mylunarcore.common.ConfigGrayReader configGrayReader;
    private final cn.itcast.demo.mylunarcore.config.LunarCoreProperties lunarCoreProperties;

    private Map<Integer, List<GachaBannerConfig>> configsByType() {
        return configsByTypeRef.get();
    }

    /** 单测/无 Spring 场景 */
    public GachaConfigService() {
        this.configGrayReader = null;
        this.lunarCoreProperties = null;
    }

    public GachaConfigService(cn.itcast.demo.mylunarcore.common.ConfigGrayReader configGrayReader,
                              cn.itcast.demo.mylunarcore.config.LunarCoreProperties lunarCoreProperties) {
        this.configGrayReader = configGrayReader;
        this.lunarCoreProperties = lunarCoreProperties;
    }

    /**
     * Spring 初始化完成后自动加载一次配置。
     * 保证首个抽卡请求（GetGachaInfo / DoGacha）能直接命中内存索引，无需懒加载。
     */
    @PostConstruct
    public void loadOnBoot() {
        reload();  // 启动时执行与热更相同的全量重载逻辑
    }

    /**
     * 重新读取 Banner 配置并整体替换内存索引。
     * <p>
     * 热更流程：读取 JSON → 按 bannerType 分组 → 旧索引冻结为 stable → canary 生效。
     * 任一步失败时保留旧配置并返回 false。
     * </p>
     *
     * @return 重载成功返回 true，配置为空或读取异常返回 false
     */
    public boolean reload() {
        try {
            // 从外部文件或 classpath 读取 Banner 配置列表
            List<GachaBannerConfig> list = readBannerConfigs();
            // 配置列表为空则拒绝替换，保留旧索引
            if (list.isEmpty()) {
                log.warn("Gacha banner config is empty");
                return false;
            }

            // 构建新的 bannerType → Banner列表 索引
            Map<Integer, List<GachaBannerConfig>> map = new ConcurrentHashMap<>();
            for (GachaBannerConfig c : list) {
                // 将 JSON 中的 gachaType 字符串（如 "AvatarUp"）映射为整数 bannerType
                int type = GachaBannerType.fromGachaTypeString(c.getGachaType());
                // 无法识别的 gachaType 跳过，避免脏数据进入索引
                if (type == 0) {
                    continue;
                }
                // 按 type 分组追加；computeIfAbsent 在 key 不存在时创建空 ArrayList
                map.computeIfAbsent(type, k -> new ArrayList<>()).add(c);
            }

            // 热更前将当前索引冻结为 stable；新 Map 原子切换（CopyOnWrite）
            stableByType = snapshot();
            Map<Integer, List<GachaBannerConfig>> immutable = Map.copyOf(map);
            configsByTypeRef.set(immutable);
            log.info("Loaded gacha banners, types={}", immutable.keySet());
            return true;
        } catch (Exception e) {
            // 读取/解析异常：记录错误日志，返回 false，旧配置继续服务
            log.error("Failed to load gacha config from {} or classpath:{}", DEFAULT_PATH, CLASSPATH_PATH, e);
            return false;
        }
    }

    /** 热更前快照，供失败回滚。 */
    public Map<Integer, List<GachaBannerConfig>> snapshot() {
        Map<Integer, List<GachaBannerConfig>> copy = new ConcurrentHashMap<>();
        configsByType().forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return copy;
    }

    public void restore(Map<Integer, List<GachaBannerConfig>> previous) {
        if (previous == null || previous.isEmpty()) {
            configsByTypeRef.set(Map.of());
            return;
        }
        Map<Integer, List<GachaBannerConfig>> copy = new ConcurrentHashMap<>();
        previous.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        configsByTypeRef.set(Map.copyOf(copy));
    }

    /**
     * 双路径读取 Banner 配置：优先外部文件，不存在时回退 classpath。
     *
     * @return 解析后的 Banner 配置列表
     * @throws java.io.IOException 外部文件与 classpath 均不可用时抛出
     */
    private List<GachaBannerConfig> readBannerConfigs() throws java.io.IOException {
        // 构造外部文件对象
        File external = new File(DEFAULT_PATH);
        // 外部文件存在且为普通文件时，直接从磁盘读取（支持热更）
        if (external.isFile()) {
            log.info("Loading gacha banners from file: {}", external.getPath());
            // Jackson 将 JSON 数组反序列化为 List<GachaBannerConfig>
            return objectMapper.readValue(external, new TypeReference<List<GachaBannerConfig>>() {});
        }

        // 外部文件不存在，尝试从 classpath（jar 包内）读取内置配置
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_PATH)) {
            if (in != null) {
                log.info("Loading gacha banners from classpath:{}", CLASSPATH_PATH);
                return objectMapper.readValue(in, new TypeReference<List<GachaBannerConfig>>() {});
            }
        }

        // 两条路径均失败，抛出异常由 reload() 捕获
        throw new java.io.FileNotFoundException(
                DEFAULT_PATH + " (external) and classpath:" + CLASSPATH_PATH + " are both unavailable");
    }

    /**
     * 按卡池类型返回该类型下的全部 Banner 配置，不做时间窗筛选。
     * <p>
     * 用于需要枚举某类型所有 Banner 的场景；活跃 Banner 选取请用 pickActiveBanner。
     * </p>
     *
     * @param bannerType 卡池类型整数（新手/常驻/角色UP/武器UP）
     * @return 该类型下的 Banner 列表，无配置时返回不可变空列表
     */
    public List<GachaBannerConfig> listByType(int bannerType) {
        return listByType(bannerType, 0L);
    }

    /** 按玩家 UID 走灰度：非灰度玩家读 stable，灰度/全量读 canary。 */
    public List<GachaBannerConfig> listByType(int bannerType, long uid) {
        Map<Integer, List<GachaBannerConfig>> canary = configsByType();
        Map<Integer, List<GachaBannerConfig>> stable = stableByType.isEmpty() ? canary : stableByType;
        Map<Integer, List<GachaBannerConfig>> chosen = canary;
        if (configGrayReader != null) {
            String serverId = lunarCoreProperties == null ? "" : lunarCoreProperties.getCenter().getLocalNodeId();
            chosen = configGrayReader.select(uid, serverId, canary, stable);
        }
        List<GachaBannerConfig> list = chosen.get(bannerType);
        if (list == null) {
            return Collections.emptyList();
        }
        return list;
    }

    public GachaBannerConfig pickActiveBanner(int bannerType, long nowSeconds) {
        return pickActiveBanner(bannerType, nowSeconds, 0L);
    }

    public GachaBannerConfig pickActiveBanner(int bannerType, long nowSeconds, long uid) {
        for (GachaBannerConfig c : listByType(bannerType, uid)) {
            long begin = c.getBeginTime();
            long end = c.getEndTime();
            if ((begin <= 0 || nowSeconds >= begin) && (end <= 0 || nowSeconds <= end)) {
                return c;
            }
        }
        return null;
    }

    public GachaBannerConfig pickNormalBannerForFallback(long nowSeconds) {
        return pickActiveBanner(GachaBannerType.NORMAL, nowSeconds);
    }
}
