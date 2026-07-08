// 抽卡卡池 Banner 配置加载与查询所在包：负责 Banners.json 的读取、索引与按时间窗筛选
package cn.itcast.demo.mylunarcore.gacha;

// TypeReference：Jackson 泛型辅助类，用于将 JSON 数组反序列化为 List<GachaBannerConfig>
import com.fasterxml.jackson.core.type.TypeReference;
// ObjectMapper：Jackson 核心 JSON 解析器
import com.fasterxml.jackson.databind.ObjectMapper;
// PostConstruct：Spring Bean 初始化完成后自动回调，用于启动时加载配置
import jakarta.annotation.PostConstruct;
// AppLogger：项目统一日志门面，按业务分类输出
import cn.itcast.demo.mylunarcore.common.AppLogger;
// LogCategory：日志分类枚举，本类使用 BUSINESS_GACHA 便于过滤抽卡相关日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Logger：SLF4J 日志接口
import org.slf4j.Logger;
// Component：声明为 Spring 单例 Bean，供 GachaNettyService 等注入使用
import org.springframework.stereotype.Component;

// File：以本地文件路径读取 Banners.json
import java.io.File;
// InputStream：从 classpath 读取内置配置
import java.io.InputStream;
// ArrayList：按类型分组时存放可变 Banner 列表
import java.util.ArrayList;
// Collections：提供 emptyList() 等不可变空集合，避免调用方 NPE
import java.util.Collections;
// List：Banner 配置列表的接口类型
import java.util.List;
// Map：bannerType → Banner 列表 的索引结构
import java.util.Map;
// ConcurrentHashMap：线程安全的 Map，支持热更 reload 与并发读查询

import java.util.concurrent.ConcurrentHashMap;

/**
 * 卡池 Banner 配置的装载、热更与查询服务。
 * <p>启动时从 {@code data/Banners.json} 读取全部 Banner，按 {@link GachaBannerType} 整型分组缓存。
 * {@link GachaBannerHotReloadService} 检测到文件变更后会调用 {@link #reload()} 原子替换内存索引，
 * 供 {@link GachaNettyService} 查询当前开放卡池与 UP 列表。</p>
 */
@Component // 注册为 Spring 容器单例，全局共享同一份配置索引
public class GachaConfigService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaConfigService.class); // 抽卡业务分类日志，便于运维按模块过滤

    private static final String DEFAULT_PATH = "data/Banners.json"; // 外部配置文件（相对工作目录，支持热更）
    private static final String CLASSPATH_PATH = "data/Banners.json"; // JAR 内置配置（桌面/无 data 目录时回退）

    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 解析器实例，整个 Bean 生命周期复用，避免重复创建
    // bannerType（整型常量）→ 该类型下全部 Banner 配置列表；ConcurrentHashMap 保证热更写入与并发读取安全
    private final Map<Integer, List<GachaBannerConfig>> configsByType = new ConcurrentHashMap<>();

    /**
     * Spring Bean 初始化完成后自动加载卡池配置。
     * <p>等价于应用启动时执行一次 {@link #reload()}，确保首个抽卡请求到来前内存中已有 Banner 数据。</p>
     */
    @PostConstruct
    public void loadOnBoot() {
        reload(); // 委托 reload 完成实际读取与索引构建
    }

    /**
     * 从 {@link #DEFAULT_PATH} 重新加载 Banner 配置并替换内存索引。
     * <p>加载失败时保留旧配置不变，避免热更误操作导致线上卡池全部不可用。</p>
     *
     * @return 成功解析并替换内存配置返回 true；任何异常发生时返回 false 并保留旧数据
     */
    public boolean reload() {
        try {
            List<GachaBannerConfig> list = readBannerConfigs();
            if (list.isEmpty()) {
                log.warn("Gacha banner config is empty");
                return false;
            }

            Map<Integer, List<GachaBannerConfig>> map = new ConcurrentHashMap<>(); // 先在临时 Map 中构建新索引，成功后再整体替换
            for (GachaBannerConfig c : list) { // 遍历 JSON 中每一条 Banner 配置
                int type = GachaBannerType.fromGachaTypeString(c.getGachaType()); // 将字符串 gachaType 转为整型常量
                if (type == 0) { // 无法识别的类型跳过，防止脏数据进入索引
                    continue;
                }
                map.computeIfAbsent(type, k -> new ArrayList<>()).add(c); // 按类型分组：不存在则新建 ArrayList，再追加当前 Banner
            }

            configsByType.clear(); // 清空旧索引
            configsByType.putAll(map); // 将新索引整体写入，读侧始终看到完整的新旧之一，不会出现半更新状态
            log.info("Loaded gacha banners, types={}", configsByType.keySet()); // 记录成功加载的类型集合，便于启动/热更排查
            return true; // 加载成功
        } catch (Exception e) {
            log.error("Failed to load gacha config from {} or classpath:{}", DEFAULT_PATH, CLASSPATH_PATH, e); // 记录完整异常栈
            return false; // 失败时不修改 configsByType，保留上一次成功的配置
        }
    }

    /**
     * 优先从外部 {@link #DEFAULT_PATH} 读取（便于热更）；不存在时回退到 classpath 内置配置。
     */
    private List<GachaBannerConfig> readBannerConfigs() throws java.io.IOException {
        File external = new File(DEFAULT_PATH);
        if (external.isFile()) {
            log.info("Loading gacha banners from file: {}", external.getPath());
            return objectMapper.readValue(external, new TypeReference<List<GachaBannerConfig>>() {});
        }

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_PATH)) {
            if (in != null) {
                log.info("Loading gacha banners from classpath:{}", CLASSPATH_PATH);
                return objectMapper.readValue(in, new TypeReference<List<GachaBannerConfig>>() {});
            }
        }

        throw new java.io.FileNotFoundException(
                DEFAULT_PATH + " (external) and classpath:" + CLASSPATH_PATH + " are both unavailable");
    }

    /**
     * 按卡池类型列出该类型下的全部 Banner 配置（不筛选开放时间窗）。
     * <p>同一类型可能有多条 Banner（不同 id、不同时间窗），调用方需自行按时间筛选或遍历。</p>
     *
     * @param bannerType {@link GachaBannerType} 整型常量
     * @return 该类型下的 Banner 列表；无配置时返回不可变空列表，调用方无需判 null
     */
    public List<GachaBannerConfig> listByType(int bannerType) {
        List<GachaBannerConfig> list = configsByType.get(bannerType); // 从内存索引 O(1) 取出该类型列表
        if (list == null) { // 该类型从未配置过任何 Banner
            return Collections.emptyList(); // 返回共享的空列表实例，避免每次 new 且不可被误修改
        }
        return list; // 返回内存中缓存的列表引用（只读使用，不应在外部修改）
    }

    /**
     * 在指定卡池类型下，选取当前时间处于开放窗口内的 Banner。
     * <p>遍历该类型全部 Banner，返回第一个满足时间条件的；UP 池结束后
     * {@link GachaNettyService} 会通过 {@link #pickNormalBannerForFallback(long)} 回退到常驻池。</p>
     *
     * @param bannerType  卡池类型整型常量
     * @param nowSeconds  当前 Unix 秒级时间戳
     * @return 命中的 Banner 配置；该类型无开放中 Banner 时返回 null
     */
    public GachaBannerConfig pickActiveBanner(int bannerType, long nowSeconds) {
        for (GachaBannerConfig c : listByType(bannerType)) { // 遍历该类型下所有 Banner（含已过期与未来未开放的）
            long begin = c.getBeginTime(); // 开放起始时间；<= 0 表示不限制开始
            long end = c.getEndTime();     // 开放结束时间；<= 0 表示不限制结束
            // 同时满足：已到达开始时间（或无开始限制）且未超过结束时间（或无结束限制）
            if ((begin <= 0 || nowSeconds >= begin) && (end <= 0 || nowSeconds <= end)) {
                return c; // 返回第一个命中的 Banner（同类型多条时以 JSON 顺序优先）
            }
        }
        return null; // 该类型当前无开放中的 Banner
    }

    /**
     * 选取当前开放的常驻池 Banner，用作 UP 池结束或 UP 列表为空时的五星/四星抽样回退来源。
     * <p>UP 池 50/50 未命中 UP 时，会从常驻池的 rateUpItems5 中抽取“歪”的五星。</p>
     *
     * @param nowSeconds 当前 Unix 秒级时间戳
     * @return 当前开放的常驻池 Banner；无则 null
     */
    public GachaBannerConfig pickNormalBannerForFallback(long nowSeconds) {
        return pickActiveBanner(GachaBannerType.NORMAL, nowSeconds); // 复用通用时间窗筛选逻辑，类型固定为常驻池
    }
}
