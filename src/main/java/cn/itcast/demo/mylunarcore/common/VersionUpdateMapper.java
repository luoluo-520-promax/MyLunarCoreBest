// HotfixData（内存 DTO）与版本更新 Protobuf 消息之间的双向映射/校验工具
package cn.itcast.demo.mylunarcore.common;

// 版本更新协议生成类（编译期由 src/main/proto/version_update.proto 生成）
import cn.itcast.demo.mylunarcore.protocol.VersionUpdateProto;
// 注册为 Spring 业务组件，供 VersionNettyService 等注入使用
import org.springframework.stereotype.Component;

// 不可变空列表（manifest 为空或缺数据时返回）
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * HotfixData 与版本更新 Protobuf 协议之间的映射。
 * <p>职责：把运营下发的 {@link HotfixData}（JSON 模型）逐字段拷贝到
 * {@link VersionUpdateProto.VersionUpdateScNotify} 等协议消息中，
 * 并对 manifest 清单做启动/推送前校验（空路径、缺 hash、非法校验算法）。</p>
 */
@Component
public class VersionUpdateMapper {

    /**
     * 将 {@link HotfixData} 转换为客户端拉取版本信息时的响应通知包体。
     *
     * @param data 当前生效的热修复配置；为 null 时返回默认空消息（避免 NPE）
     * @return 组装完成的 VersionUpdateScNotify，供 GetVersionInfoScRsp 或主动推送使用
     */
    public VersionUpdateProto.VersionUpdateScNotify toNotify(HotfixData data) {
        if (data == null) {
            // 无数据时返回 protobuf 默认实例：所有字段为默认值，客户端按缺失处理
            return VersionUpdateProto.VersionUpdateScNotify.getDefaultInstance();
        }
        // Builder 模式逐字段填充：资源根 URL、版本号（字符串）、补丁序号（数字）
        VersionUpdateProto.VersionUpdateScNotify.Builder builder = VersionUpdateProto.VersionUpdateScNotify.newBuilder()
                .setClientResourceBaseUrl(nullToEmpty(data.getClientResourceBaseUrl()))
                .setHotfixVersion(nullToEmpty(data.getHotfixVersion()))
                .setPatchVersion(data.getPatchVersion());

        // 主资源包信息（版本/URL/hash/size）仅在非空时设置，保持协议消息最小化
        if (data.getGameResourcePack() != null) {
            builder.setGameResourcePack(toProto(data.getGameResourcePack()));
        }
        // 音频/语言包是复数列表：逐个转换为协议元素追加到 repeated 字段
        if (data.getAudioLanguagePacks() != null) {
            for (HotfixData.LanguagePackInfo pack : data.getAudioLanguagePacks()) {
                builder.addAudioLanguagePacks(toProto(pack));
            }
        }
        if (data.getVersionInfo() != null) {
            builder.setVersionInfo(toProto(data.getVersionInfo()));
        }
        // 需要客户端删除的旧文件路径列表：直接整体拷贝到 repeated 字段
        if (data.getDeletedFiles() != null) {
            builder.addAllDeletedFiles(data.getDeletedFiles());
        }
        // 配置文件热更条目：逐条转换后追加
        if (data.getConfigFiles() != null) {
            for (HotfixData.ConfigFileEntry file : data.getConfigFiles()) {
                builder.addConfigFiles(toProto(file));
            }
        }
        if (data.getPatchTool() != null) {
            builder.setPatchTool(toProto(data.getPatchTool()));
        }
        // 端侧攻略包（方案 E：客户端 Local First 校验元数据）
        if (data.getGuidePack() != null) {
            builder.setGuidePack(toProto(data.getGuidePack()));
        }
        return builder.build();
    }

    /**
     * 校验版本清单（manifest）数据完整性，供推送前预警。
     *
     * @param data 当前热修复配置
     * @return 问题描述列表；为空表示校验通过
     */
    public List<String> validateManifest(HotfixData data) {
        // 缺少 versionInfo 或其文件清单时视为无需校验，直接通过
        if (data == null || data.getVersionInfo() == null || data.getVersionInfo().getFiles() == null) {
            return Collections.emptyList();
        }
        List<String> issues = new java.util.ArrayList<>();
        // 逐文件检查路径与 hash：两者任一缺失/空白都会导致客户端无法差分校验
        for (HotfixData.ManifestFileEntry file : data.getVersionInfo().getFiles()) {
            if (file.getPath() == null || file.getPath().isBlank()) {
                issues.add("manifest file path is blank");
            }
            if (file.getHash() == null || file.getHash().isBlank()) {
                issues.add("manifest file hash missing: " + file.getPath());
            }
        }
        // 补丁工具的校验算法白名单：客户端仅支持 sha256/md5/sha1，其余判定为配置错误
        if (data.getPatchTool() != null && data.getPatchTool().getAlgorithm() != null) {
            String algo = data.getPatchTool().getAlgorithm().toLowerCase();
            if (!algo.equals("sha256") && !algo.equals("md5") && !algo.equals("sha1")) {
                issues.add("unsupported checksum algorithm: " + algo);
            }
        }
        return issues;
    }

    /**
     * 资源包信息 DTO → 协议消息：统一走 nullToEmpty 避免空指针，size 为原始字节数。
     */
    private VersionUpdateProto.ResourcePackInfo toProto(HotfixData.ResourcePackInfo pack) {
        return VersionUpdateProto.ResourcePackInfo.newBuilder()
                .setVersion(nullToEmpty(pack.getVersion()))
                .setUrl(nullToEmpty(pack.getUrl()))
                .setHash(nullToEmpty(pack.getHash()))
                .setSize(pack.getSize())
                .build();
    }

    /**
     * 语言包 DTO → 协议消息：locale 为语言标识（如 zh-CN），其余同资源包。
     */
    private VersionUpdateProto.LanguagePackInfo toProto(HotfixData.LanguagePackInfo pack) {
        return VersionUpdateProto.LanguagePackInfo.newBuilder()
                .setLocale(nullToEmpty(pack.getLocale()))
                .setVersion(nullToEmpty(pack.getVersion()))
                .setUrl(nullToEmpty(pack.getUrl()))
                .setHash(nullToEmpty(pack.getHash()))
                .setSize(pack.getSize())
                .build();
    }

    /**
     * 版本清单 DTO → 协议消息：manifest URL 与整体 hash 属于整包校验基础，文件条目逐个转换。
     */
    private VersionUpdateProto.VersionManifestInfo toProto(HotfixData.VersionManifestInfo info) {
        VersionUpdateProto.VersionManifestInfo.Builder builder = VersionUpdateProto.VersionManifestInfo.newBuilder()
                .setVersion(nullToEmpty(info.getVersion()))
                .setManifestUrl(nullToEmpty(info.getManifestUrl()))
                .setManifestHash(nullToEmpty(info.getManifestHash()));
        if (info.getFiles() != null) {
            for (HotfixData.ManifestFileEntry file : info.getFiles()) {
                builder.addFiles(VersionUpdateProto.ManifestFileEntry.newBuilder()
                        .setPath(nullToEmpty(file.getPath()))
                        .setHash(nullToEmpty(file.getHash()))
                        .setSize(file.getSize())
                        .build());
            }
        }
        return builder.build();
    }

    /**
     * 配置文件热更条目 DTO → 协议消息：name 为配置名，url/hash/size 供客户端下载校验。
     */
    private VersionUpdateProto.ConfigFileEntry toProto(HotfixData.ConfigFileEntry file) {
        return VersionUpdateProto.ConfigFileEntry.newBuilder()
                .setName(nullToEmpty(file.getName()))
                .setUrl(nullToEmpty(file.getUrl()))
                .setHash(nullToEmpty(file.getHash()))
                .setSize(file.getSize())
                .build();
    }

    /**
     * 补丁工具信息 DTO → 协议消息：算法（sha256 等）、差分补丁基址、校验工具地址、整包 manifest hash。
     */
    private VersionUpdateProto.PatchToolInfo toProto(HotfixData.PatchToolInfo tool) {
        return VersionUpdateProto.PatchToolInfo.newBuilder()
                .setAlgorithm(nullToEmpty(tool.getAlgorithm()))
                .setPatchBaseUrl(nullToEmpty(tool.getPatchBaseUrl()))
                .setVerifyToolUrl(nullToEmpty(tool.getVerifyToolUrl()))
                .setManifestHash(nullToEmpty(tool.getManifestHash()))
                .build();
    }

    /**
     * null 安全转换：protobuf 字符串字段不接受 null，统一转为空串。
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
