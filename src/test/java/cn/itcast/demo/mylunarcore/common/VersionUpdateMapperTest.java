package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VersionUpdateMapper 版本更新映射测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code VersionUpdateMapperTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("VersionUpdateMapper 版本更新映射测试")
class VersionUpdateMapperTest {

    private final VersionUpdateMapper mapper = new VersionUpdateMapper();

    /**
     * 验证点：toNotify 应映射完整版本字段。
     * <p>测试方法 {@code toNotifyShouldMapAllFields}：
     * <ul>
     *   <li>{@code assertEquals("https://cdn.test/", notify.getClientResourceBaseUrl());}</li>
     *   <li>{@code assertEquals("2.0.0", notify.getHotfixVersion());}</li>
     *   <li>{@code assertEquals(5L, notify.getPatchVersion());}</li>
     *   <li>{@code assertEquals("2.0.0", notify.getGameResourcePack().getVersion());}</li>
     *   <li>{@code assertEquals(1, notify.getDeletedFilesCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("toNotify 应映射完整版本字段")
    void toNotifyShouldMapAllFields() {
        HotfixData data = new HotfixData();
        data.setClientResourceBaseUrl("https://cdn.test/");
        data.setHotfixVersion("2.0.0");
        data.setPatchVersion(5);
        HotfixData.ResourcePackInfo pack = new HotfixData.ResourcePackInfo();
        pack.setVersion("2.0.0");
        pack.setUrl("https://cdn.test/game.zip");
        pack.setHash("abc123");
        data.setGameResourcePack(pack);
        data.setDeletedFiles(List.of("old/file.png"));

        var notify = mapper.toNotify(data);
        assertEquals("https://cdn.test/", notify.getClientResourceBaseUrl());
        assertEquals("2.0.0", notify.getHotfixVersion());
        assertEquals(5L, notify.getPatchVersion());
        assertEquals("2.0.0", notify.getGameResourcePack().getVersion());
        assertEquals(1, notify.getDeletedFilesCount());
    }

    /**
     * 验证点：validateManifest 应对缺失 hash 给出告警。
     * <p>测试方法 {@code validateManifestShouldReportMissingHash}：
     * <ul>
     *   <li>{@code assertTrue(issues.stream().anyMatch(s -> s.contains("hash missing")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("validateManifest 应对缺失 hash 给出告警")
    void validateManifestShouldReportMissingHash() {
        HotfixData data = new HotfixData();
        HotfixData.VersionManifestInfo info = new HotfixData.VersionManifestInfo();
        HotfixData.ManifestFileEntry file = new HotfixData.ManifestFileEntry();
        file.setPath("config/test.json");
        info.setFiles(List.of(file));
        data.setVersionInfo(info);

        List<String> issues = mapper.validateManifest(data);
        assertTrue(issues.stream().anyMatch(s -> s.contains("hash missing")));
    }
}
