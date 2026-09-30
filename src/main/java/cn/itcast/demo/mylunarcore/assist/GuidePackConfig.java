package cn.itcast.demo.mylunarcore.assist; // 攻略包配置所在包

import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 忽略未知字段以便热更兼容

import java.util.List; // 使用列表保存 FAQ 条目
/**
 * 攻略包配置根结构。 <p> 这个配置保存 FAQ 条目、下载信息和版本信息， 供本地检索和服务端匹配问答共同使用。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuidePackConfig(
        String version, // 攻略包版本号
        String locale, // 语言区域
        String downloadUrl, // CDN 或下载地址
        String contentHash, // 内容完整性哈希
        Long size, // 载荷大小字节数
        List<FaqEntry> faqs // FAQ 条目列表
) { // 记录/配置对象的紧凑构造体定义开始
    public GuidePackConfig { // 紧凑构造，规整默认值
        faqs = faqs == null ? List.of() : List.copyOf(faqs); // 冻结 FAQ 列表
        version = version == null ? "" : version; // 版本号空值回退为空串
        locale = locale == null ? "zh-CN" : locale; // 默认中文区域
        downloadUrl = downloadUrl == null ? "" : downloadUrl; // 下载地址空值回退
        contentHash = contentHash == null ? "" : contentHash; // 哈希空值回退
        size = size == null ? 0L : Math.max(0L, size); // size 至少为 0
    } // 紧凑构造结束
    /**
     * 返回空占位配置
     */
    public static GuidePackConfig empty() {
        return new GuidePackConfig("0.0.0", "zh-CN", "", "", 0L, List.of()); // 构造空 FAQ 包
    } // empty 结束
    /**
     * 攻略 FAQ 条目
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FaqEntry(
            String id, // FAQ 稳定 ID
            String question, // 标准问法
            String answer, // 标准答法
            String scene, // 场景标签
            List<String> keywords, // 附加关键词
            Integer priority // 优先级
    ) { // 记录/配置对象的紧凑构造体定义开始
        public FaqEntry { // 紧凑构造，规整内部字段
            keywords = keywords == null ? List.of() : List.copyOf(keywords); // 冻结关键词列表
            priority = priority == null ? 0 : priority; // 优先级空值回退为 0
        } // 紧凑构造结束
        /**
         * 兼容旧四参构造
         */
        public FaqEntry(String id, String question, String answer, String scene) {
            this(id, question, answer, scene, List.of(), 0); // 回退到完整构造器
        } // 兼容构造结束
    } // FaqEntry 结束
}
