package cn.itcast.demo.mylunarcore.assist; // 助手安全规则仓库所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 统一业务日志入口
import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 读取安全规则文件
import cn.itcast.demo.mylunarcore.common.LogCategory; // 使用助手机分类日志
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取安全规则资源路径
import jakarta.annotation.PostConstruct; // 启动后加载安全规则
import org.slf4j.Logger; // 记录加载结果
import org.springframework.stereotype.Component; // 注册为 Spring 组件

/**
 * 安全规则配置仓库
 */
@Component // 交给 Spring 管理
public class AssistSafetyRulesRepository implements AssistSafetyRulesSource {
    /**
     * 记录安全规则日志
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistSafetyRulesRepository.class);
    /**
     * 统一配置读取服务
     */
    private final ConfigFileService configFileService;
    /**
     * 保存规则资源路径来源
     */
    private final LunarCoreProperties properties;
    /**
     * 默认先使用内置安全规则
     */
    private volatile AssistSafetyRulesConfig config = AssistSafetyRulesConfig.defaults();
    /**
     * 构造注入依赖
     */
    public AssistSafetyRulesRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService; // 保存文件服务
        this.properties = properties; // 保存运行时配置
    } // 构造结束
    /**
     * 容器启动后加载磁盘规则
     */
    @PostConstruct
    public void load() {
        reload(); // 以磁盘配置覆盖默认规则
    } // load 结束
    /**
     * 重新加载安全规则
     */
    public boolean reload() {
        String relative = properties.getAiAssist().getSafetyRulesResource(); // 读取规则文件相对路径
        try { // 捕获 IO 与 JSON 异常
            AssistSafetyRulesConfig loaded = configFileService.readJson(relative, AssistSafetyRulesConfig.class); // 解析安全规则配置
            if (loaded == null) { // 空文件不覆盖当前规则
                log.warn("AssistSafetyRules empty, keep previous, path={}", relative); // 记录空内容告警
                return false; // 返回失败状态
            } // 空规则判断结束
            config = loaded; // 替换为新规则快照
            log.info("AssistSafetyRules loaded version={} blockPatterns={} path={}", loaded.version(), loaded.blockPatterns().size(), relative); // 记录加载信息
            return true; // 表示重载成功
        } catch (Exception e) { // 读取或解析失败时保留旧规则
            log.warn("AssistSafetyRules reload failed path={}, keep previous/defaults", relative, e); // 记录失败原因
            return false; // 返回失败结果
        } // 异常处理结束
    } // reload 结束
    /**
     * 返回当前规则快照
     */
    @Override
    public AssistSafetyRulesConfig current() {
        return config; // 供过滤器与清洗器使用
    } // current 结束
    /**
     * 热更失败时恢复旧规则
     */
    public void restore(AssistSafetyRulesConfig previous) {
        if (previous != null) { // 只有旧规则存在才恢复
            config = previous; // 切回旧规则快照
        } // 恢复判断结束
    } // restore 结束
}
