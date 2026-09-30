package cn.itcast.demo.mylunarcore.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActivityTypeCatalog} 测试：从目录加载 ActivityTypeCatalog.json，
 * 校验已知类型、缺失文件容错，以及自定义目录热加载。
 */
@DisplayName("活动类型目录")
class ActivityTypeCatalogTest {

    /** JUnit 临时目录，用于「无文件」与「自定义 JSON」用例，测完自动清理。 */
    @TempDir
    Path tempDir;

    /**
     * 指向仓库根下 {@code data/}，reload 后应识别 signin/tower/coop/seasonal 等，
     * 未知类型返回 false；signin 的中文 displayName 为「签到」；条目数 ≥5。
     */
    @Test
    @DisplayName("加载仓库 data 目录中的 ActivityTypeCatalog.json")
    void loadsRepoCatalog() {
        ActivityTypeCatalog catalog = new ActivityTypeCatalog(new ObjectMapper(), "data");
        catalog.reload();
        assertTrue(catalog.isKnown("signin"));
        assertTrue(catalog.isKnown("tower"));
        assertTrue(catalog.isKnown("coop"));
        assertTrue(catalog.isKnown("seasonal"));
        assertFalse(catalog.isKnown("unknown_type_xyz"));
        assertEquals("签到", catalog.find("signin").orElseThrow().displayName());
        assertTrue(catalog.all().size() >= 5);
    }

    /**
     * 目录存在但无 ActivityTypeCatalog.json 时：reload 不抛异常，all 为空，isKnown 全 false。
     */
    @Test
    @DisplayName("缺失文件时目录为空且不抛异常")
    void missingFileYieldsEmpty() {
        ActivityTypeCatalog catalog = new ActivityTypeCatalog(new ObjectMapper(), tempDir.toString());
        catalog.reload();
        assertTrue(catalog.all().isEmpty());
        assertFalse(catalog.isKnown("signin"));
    }

    /**
     * 向临时目录写入仅含 boss_rush 的 JSON，reload 后应能按 type 查找且 displayName 正确。
     */
    @Test
    @DisplayName("自定义目录可热加载新类型")
    void reloadCustomCatalog() throws Exception {
        Files.writeString(tempDir.resolve("ActivityTypeCatalog.json"), """
                [{"type":"boss_rush","displayName":"Boss 突袭","description":"d","requiredFields":["stages"],"handlerHint":"H","exampleActivityId":1}]
                """);
        ActivityTypeCatalog catalog = new ActivityTypeCatalog(new ObjectMapper(), tempDir.toString());
        catalog.reload();
        assertTrue(catalog.isKnown("boss_rush"));
        assertEquals("Boss 突袭", catalog.find("boss_rush").orElseThrow().displayName());
    }
}
