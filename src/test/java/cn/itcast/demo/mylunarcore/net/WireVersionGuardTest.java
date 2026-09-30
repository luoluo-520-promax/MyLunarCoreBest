package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强制：PROTOCOL_WIRE_VERSION 与 docs 中声明一致，且 ≥2（角色号段迁移后）。
 * CI 在 buf breaking 失败时须同步升级本常量。
 */
@DisplayName("WireVersion 守卫")
class WireVersionGuardTest {

    @Test
    @DisplayName("wire_version 至少为 2（角色号段 160–173）")
    void wireVersionAtLeastTwo() {
        assertTrue(CmdIds.PROTOCOL_WIRE_VERSION >= 2,
                "incompatible proto changes must bump PROTOCOL_WIRE_VERSION");
    }

    @Test
    @DisplayName("迁移指南文档存在且提及 wire_version")
    void migrationGuidePresent() throws Exception {
        Path guide = Path.of("docs/protocol-migration-guide.md");
        assertTrue(Files.isRegularFile(guide), "missing docs/protocol-migration-guide.md");
        String text = Files.readString(guide);
        assertTrue(text.contains("PROTOCOL_WIRE_VERSION") || text.contains("wire_version"));
        assertTrue(text.contains("CHARACTER_V2") || text.contains("160"));
    }
}
