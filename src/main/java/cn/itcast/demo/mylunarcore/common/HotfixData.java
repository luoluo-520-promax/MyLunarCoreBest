// 客户端热修复 JSON 映射实体
package cn.itcast.demo.mylunarcore.common;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * 客户端版本与资源热更新参数：资源包、语言包、版本清单、删除列表、配置文件与补丁校验信息。
 */
@Setter
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class HotfixData {

    /** 客户端静态资源下载根 URL */
    private String clientResourceBaseUrl = "";

    /** 热修复包版本号（展示/比对用） */
    private String hotfixVersion = "0.0.0";

    /** 递增补丁序号 */
    private long patchVersion;

    /** 游戏资源包 */
    private ResourcePackInfo gameResourcePack = new ResourcePackInfo();

    /** 音频/语言包列表 */
    private List<LanguagePackInfo> audioLanguagePacks = Collections.emptyList();

    /** 版本信息与文件清单 */
    private VersionManifestInfo versionInfo = new VersionManifestInfo();

    /** 需删除的客户端文件路径列表 */
    private List<String> deletedFiles = Collections.emptyList();

    /** 需热更的配置文件列表 */
    private List<ConfigFileEntry> configFiles = Collections.emptyList();

    /** 校验与补丁工具信息 */
    private PatchToolInfo patchTool = new PatchToolInfo();

    /** 端侧攻略包元数据（方案 E：版本号供客户端 Local First 校验） */
    private ResourcePackInfo guidePack = new ResourcePackInfo();

    /** @return 全默认值的占位对象 */
    public static HotfixData empty() {
        return new HotfixData();
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourcePackInfo {
        private String version = "";
        private String url = "";
        private String hash = "";
        private long size;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LanguagePackInfo {
        /** 语言标识，如 zh-CN、en-US、ja-JP */
        private String locale = "";
        private String version = "";
        private String url = "";
        private String hash = "";
        private long size;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionManifestInfo {
        private String version = "";
        private String manifestUrl = "";
        private String manifestHash = "";
        private List<ManifestFileEntry> files = Collections.emptyList();
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestFileEntry {
        private String path = "";
        private String hash = "";
        private long size;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConfigFileEntry {
        private String name = "";
        private String url = "";
        private String hash = "";
        private long size;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PatchToolInfo {
        /** 校验算法，如 sha256、md5 */
        private String algorithm = "sha256";
        /** 差分补丁基址 URL */
        private String patchBaseUrl = "";
        /** 客户端校验/补丁工具下载地址 */
        private String verifyToolUrl = "";
        /** 整包 manifest 校验值 */
        private String manifestHash = "";
    }
}
