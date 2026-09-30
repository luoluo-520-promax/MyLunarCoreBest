package cn.itcast.demo.mylunarcore.assist; // 教练提示仓库所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 统一业务日志入口
import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 读取 CoachTips.json
import cn.itcast.demo.mylunarcore.common.LogCategory; // 业务助手机分类日志
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取 coachTipsResource
import jakarta.annotation.PostConstruct; // 启动后加载配置
import org.slf4j.Logger; // 输出加载日志
import org.springframework.stereotype.Component; // 注册为 Spring 组件

@Component // 让 Spring 管理仓库生命周期
public class CoachTipsRepository implements CoachTipsSource { // 教练提示配置仓库
    /**
     * 记录提示配置加载情况
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, CoachTipsRepository.class);
    /**
     * JSON 文件读取服务
     */
    private final ConfigFileService configFileService;
    /**
     * 保存提示资源路径
     */
    private final LunarCoreProperties properties;
    /**
     * 当前生效配置快照
     */
    private volatile CoachTipsConfig config = CoachTipsConfig.empty();
    /**
     * 构造注入依赖
     */
    public CoachTipsRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService; // 保存文件服务
        this.properties = properties; // 保存运行时配置
    } // 构造结束
    /**
     * 启动后立即加载一次
     */
    @PostConstruct
    public void load() {
        reload(); // 用磁盘配置覆盖空配置
    } // load 结束
    /**
     * 重新加载提示配置
     */
    public boolean reload() {
        String relative = properties.getAiAssist().getCoachTipsResource(); // 读取相对路径
        try { // 捕获解析异常
            CoachTipsConfig loaded = configFileService.readJson(relative, CoachTipsConfig.class); // 从磁盘读取配置
            if (loaded == null) { // 空内容不覆盖当前规则
                log.warn("CoachTips empty, keep previous config, path={}", relative); // 记录空配置告警
                return false; // 返回失败
            } // 空配置判断结束
            config = loaded; // 替换当前快照
            log.info("CoachTips loaded, version={}, tipCount={}, path={}", loaded.version(), loaded.tips().size(), relative); // 输出加载结果
            return true; // 表示重载成功
        } catch (Exception e) { // 读写失败保持旧配置
            log.warn("CoachTips reload failed, path={}, keep previous", relative, e); // 记录异常
            return false; // 返回失败结果
        } // 异常处理结束
    } // reload 结束
    /**
     * 返回当前配置
     */
    public CoachTipsConfig current() {
        return config; // 供规则引擎只读使用
    } // current 结束
    /**
     * 热更失败时恢复旧配置
     */
    public void restore(CoachTipsConfig previous) {
        if (previous != null) { // 仅在旧配置存在时恢复
            config = previous; // 切回旧快照
        } // 恢复判断结束
    } // restore 结束
}
