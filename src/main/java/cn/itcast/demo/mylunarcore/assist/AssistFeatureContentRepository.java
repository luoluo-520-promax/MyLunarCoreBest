package cn.itcast.demo.mylunarcore.assist; // 助手功能内容仓库所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 使用统一业务日志入口
import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 读取 data 目录配置文件
import cn.itcast.demo.mylunarcore.common.LogCategory; // 选择业务助手机分类
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取内容包资源路径
import jakarta.annotation.PostConstruct; // 容器启动后执行加载
import org.slf4j.Logger; // 记录热更加载结果
import org.springframework.stereotype.Component; // 注册为 Spring 组件

/**
 * 助手功能内容仓库
 */
@Component // 让仓库由 Spring 托管
public class AssistFeatureContentRepository {
    /**
     * 记录内容包加载日志
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistFeatureContentRepository.class);
    /**
     * 统一配置文件读取服务
     */
    private final ConfigFileService configFileService;
    /**
     * 全局配置，用于定位资源路径
     */
    private final LunarCoreProperties properties;
    /**
     * 当前生效的内容快照
     */
    private volatile AssistFeatureContent content = AssistFeatureContent.empty();
    /**
     * 构造注入依赖
     */
    public AssistFeatureContentRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService; // 保存配置文件服务引用
        this.properties = properties; // 保存全局配置引用
    } // 构造方法结束
    /**
     * 容器启动后立即加载一次内容
     */
    @PostConstruct
    public void load() {
        reload(); // 使用磁盘配置初始化快照
    } // load 结束
    /**
     * 重新加载功能内容
     */
    public boolean reload() {
        String relative = properties.getAiAssist().getFeatureContentResource(); // 读取内容包相对路径
        try { // 捕获读写与反序列化异常
            AssistFeatureContent loaded = configFileService.readJson(relative, AssistFeatureContent.class); // 从磁盘读取内容包
            if (loaded == null) { // 空文件不应覆盖当前快照
                log.warn("AssistFeatureContent empty, keep previous, path={}", relative); // 记录空内容的告警
                return false; // 返回加载失败
            } // 空内容判断结束
            content = loaded; // 原子替换为新内容快照
            log.info("AssistFeatureContent loaded version={} routes={} pois={} enemies={} lore={} ladders={} path={}", loaded.version(), loaded.exploreRoutes().size(), loaded.pois().size(), loaded.enemyWeaknesses().size(), loaded.loreEntries().size(), loaded.questHintLadders().size(), relative); // 记录加载规模
            return true; // 表示加载成功
        } catch (Exception e) { // 捕获异常保持旧配置
            log.warn("AssistFeatureContent reload failed path={}, keep previous/empty", relative, e); // 记录失败原因
            return false; // 返回失败结果
        } // 异常处理结束
    } // reload 结束
    /**
     * 返回当前快照
     */
    public AssistFeatureContent current() {
        return content; // 供业务侧只读使用
    } // current 结束
    /**
     * 热更失败时恢复旧快照
     */
    public void restore(AssistFeatureContent previous) {
        if (previous != null) { // 仅在旧快照存在时恢复
            content = previous; // 重新切回旧内容
        } // 恢复判断结束
    } // restore 结束
}
