package cn.itcast.demo.mylunarcore.assist; // 攻略包仓库所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 使用统一业务日志入口
import cn.itcast.demo.mylunarcore.common.ConfigFileService; // 从 data 目录读取配置
import cn.itcast.demo.mylunarcore.common.LogCategory; // 业务助手机分类日志
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取 guidePackResource
import jakarta.annotation.PostConstruct; // 启动后加载攻略包
import org.slf4j.Logger; // 输出加载与校验日志
import org.springframework.stereotype.Component; // 交给 Spring 管理

import java.nio.charset.StandardCharsets; // 计算 payload 时使用 UTF-8
import java.security.MessageDigest; // 使用 SHA-256 生成完整性 hash
import java.util.HexFormat; // 将摘要字节转成十六进制

/**
 * 从 data 目录读取并校验 guide pack 配置，供本地攻略问答使用。
 */
@Component
public class GuidePackRepository { // 攻略包仓库
    /**
     * 记录攻略包热更日志
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, GuidePackRepository.class);
    /**
     * 配置文件读取服务
     */
    private final ConfigFileService configFileService;
    /**
     * 读取攻略包资源路径
     */
    private final LunarCoreProperties properties;
    /**
     * 当前生效攻略包快照
     */
    private volatile GuidePackConfig config = GuidePackConfig.empty();
    /**
     * 构造注入依赖
     */
    public GuidePackRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService; // 保存文件服务
        this.properties = properties; // 保存全局配置
    } // 构造结束
    /**
     * 容器启动后立即加载攻略包
     */
    @PostConstruct
    public void load() {
        reload(); // 用磁盘内容初始化快照
    } // load 结束
    /**
     * 重新加载攻略包
     */
    public boolean reload() {
        String relative = properties.getAiAssist().getGuidePackResource(); // 读取攻略包相对路径
        try { // 捕获读写异常
            GuidePackConfig loaded = configFileService.readJson(relative, GuidePackConfig.class); // 读取 JSON 攻略包
            if (loaded == null) { // 空文件不覆盖当前配置
                return false; // 表示没有成功加载
            } // 空内容判断结束
            config = enrichIntegrity(loaded); // 补全哈希和大小后写入快照
            log.info("GuidePack loaded version={} faqs={} size={} hash={} path={}", config.version(), config.faqs().size(), config.size(), config.contentHash(), relative); // 记录攻略包加载信息
            return true; // 返回成功
        } catch (Exception e) { // 发生异常时保留旧配置
            log.warn("GuidePack reload failed path={}", relative, e); // 记录加载失败
            return false; // 返回失败结果
        } // 异常处理结束
    } // reload 结束
    /**
     * 返回当前攻略包快照
     */
    public GuidePackConfig current() {
        return config; // 供本地 FAQ 检索使用
    } // current 结束
    /**
     * 热更失败时恢复旧快照
     */
    public void restore(GuidePackConfig previous) {
        if (previous != null) { // 仅在旧快照存在时恢复
            config = previous; // 切回旧攻略包
        } // 恢复判断结束
    } // restore 结束
    /**
     * 为加载结果补全完整性字段
     */
    static GuidePackConfig enrichIntegrity(GuidePackConfig loaded) {
        String payload = buildPayload(loaded); // 生成稳定的内容载荷
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8); // 将载荷转成 UTF-8 字节
        String hash = loaded.contentHash() == null || loaded.contentHash().isBlank() ? sha256(bytes) : loaded.contentHash(); // 缺 hash 时现场计算
        long size = loaded.size() == null || loaded.size() <= 0 ? bytes.length : loaded.size(); // 缺 size 时按实际长度回填
        return new GuidePackConfig(loaded.version(), loaded.locale(), loaded.downloadUrl(), hash, size, loaded.faqs()); // 返回补全后的新配置
    } // enrichIntegrity 结束
    /**
     * 构造稳定哈希载荷
     */
    private static String buildPayload(GuidePackConfig pack) {
        StringBuilder sb = new StringBuilder(); // 准备拼接 payload
        sb.append(nullToEmpty(pack.version())).append('|').append(nullToEmpty(pack.locale())).append('|'); // 版本与语言先入载荷
        for (GuidePackConfig.FaqEntry faq : pack.faqs()) { // 逐条 FAQ 写入载荷
            if (faq == null) { // 跳过空 FAQ 条目
                continue; // 不让空项影响哈希
            } // 空项判断结束
            sb.append(nullToEmpty(faq.id())).append('=') // 写入 FAQ ID
                    .append(nullToEmpty(faq.question())).append('=') // 写入标准问法
                    .append(nullToEmpty(faq.answer())).append('=') // 写入标准答法
                    .append(nullToEmpty(faq.scene())).append('=') // 写入场景标签
                    .append(faq.keywords()).append('=') // 写入关键词列表文本
                    .append(faq.priority()).append(';'); // 写入优先级并分隔
        } // FAQ 循环结束
        return sb.toString(); // 返回稳定载荷文本
    } // buildPayload 结束
    /**
     * 计算 SHA-256 十六进制
     */
    private static String sha256(byte[] bytes) {
        try { // 正常情况下使用标准摘要算法
            MessageDigest md = MessageDigest.getInstance("SHA-256"); // 获取摘要实例
            return HexFormat.of().formatHex(md.digest(bytes)); // 输出十六进制 hash
        } catch (Exception e) { // 极端环境下算法不可用
            return Integer.toHexString(java.util.Arrays.hashCode(bytes)); // 退回到数组 hashCode
        } // 异常处理结束
    } // sha256 结束
    /**
     * 将空值转为安全空串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s; // 避免拼接时出现 null
    } // nullToEmpty 结束
}
