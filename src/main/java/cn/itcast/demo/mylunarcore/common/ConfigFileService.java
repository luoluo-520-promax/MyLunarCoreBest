// 运营配置文件读写服务：负责 data/ 目录内 JSON/CSV 的安全读写、回滚与合并
package cn.itcast.demo.mylunarcore.common;

// 全局配置：dataDir 路径来源
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// 活动配置对象，用于活动配置文件合并
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
// Jackson 泛型反序列化类型引用
import com.fasterxml.jackson.core.type.TypeReference;
// Jackson 对象映射器
import com.fasterxml.jackson.databind.ObjectMapper;
// SLF4J 日志
import org.slf4j.Logger;
// Spring 组件
import org.springframework.stereotype.Component;

// IO 异常
import java.io.IOException;
// UTF-8 编码
import java.nio.charset.StandardCharsets;
// 文件 API
import java.nio.file.Files;
// 路径 API
import java.nio.file.Path;
// 动态数组
import java.util.ArrayList;
// LinkedHashMap 保持插入顺序
import java.util.LinkedHashMap;
// 列表接口
import java.util.List;
// Map 接口
import java.util.Map;

/**
 * 读写 {@code data/} 目录下的 JSON/CSV 配置文件（运营导入落盘）。
 * <p>核心目标：
 * <ul>
 *   <li>路径必须被限制在 dataRoot 下，避免路径穿越/逃逸；</li>
 *   <li>写入采用「备份 → 临时文件 → 原子替换」流程，降低半写入风险；</li>
 *   <li>提供 JSON/CSV 的通用读写与若干业务合并方法，供活动导入与运营工具复用。</li>
 * </ul>
 * </p>
 */
@Component
public class ConfigFileService {

    // 本类日志：记录原子写入、回滚、路径异常等事件
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ConfigFileService.class);

    // 全局配置：决定 dataRoot 实际路径
    private final LunarCoreProperties properties;
    // Jackson：负责 JSON 解析/序列化
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造器注入全局配置。
     */
    public ConfigFileService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * @return data 目录的绝对规范化根路径
     */
    public Path dataRoot() {
        return Path.of(properties.getDataDir()).toAbsolutePath().normalize();
    }

    /**
     * 将相对路径解析为 dataRoot 下的绝对路径，并做路径逃逸校验。
     *
     * @param relativePath data 目录下的相对路径
     * @return 已规范化且经过边界检查的绝对路径
     */
    public Path resolve(String relativePath) {
        Path root = dataRoot();
        Path resolved = root.resolve(relativePath == null ? "" : relativePath).normalize();
        assertUnderDataRoot(root, resolved);
        return resolved;
    }

    /**
     * 拒绝路径逃逸：resolved 必须仍位于 dataRoot 之下。
     * <p>这里使用 startsWith(root) 做边界判断，避免诸如 {@code ../../etc/passwd}
     * 之类的路径逃逸写入到 data 目录之外。</p>
     */
    static void assertUnderDataRoot(Path dataRoot, Path resolved) {
        Path root = dataRoot.toAbsolutePath().normalize();
        Path target = resolved.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("config path escapes dataRoot: " + resolved);
        }
    }

    /**
     * 直接写文本文件，默认启用备份。
     */
    public void writeText(String relativePath, String content) throws IOException {
        writeTextAtomic(relativePath, content, true);
    }

    /**
     * 原子写入：先备份旧文件 → 写 {@code *.tmp} → move 覆盖目标。
     *
     * @param backup 是否在覆盖前备份到 {@code .bak}（供一键回滚）
     */
    public void writeTextAtomic(String relativePath, String content, boolean backup) throws IOException {
        Path target = resolve(relativePath);
        Files.createDirectories(target.getParent());
        if (backup && Files.isRegularFile(target)) {
            // 先把旧文件复制为 .bak，回滚时可直接恢复
            Path bak = target.resolveSibling(target.getFileName().toString() + ".bak");
            Files.copy(target, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        // 先写入临时文件，避免写到一半进程崩溃导致目标文件损坏
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 某些文件系统不支持原子移动，退化为普通覆盖，并打日志提示风险
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.warn("Atomic move unsupported, fell back to non-atomic replace: {}", target);
        }
        log.info("Wrote config file atomically: {}", target);
    }

    /**
     * 将 {@code .bak} 恢复为正式文件（若存在）。
     *
     * @return true 表示成功回滚
     */
    public boolean rollbackFromBackup(String relativePath) throws IOException {
        Path target = resolve(relativePath);
        Path bak = target.resolveSibling(target.getFileName().toString() + ".bak");
        if (!Files.isRegularFile(bak)) {
            return false;
        }
        Files.copy(bak, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("Rolled back config file from backup: {}", target);
        return true;
    }

    /**
     * 仅校验 JSON 可解析，不写盘。
     *
     * @param content JSON 文本内容
     * @throws IOException JSON 语法错误时抛出
     */
    public void validateJsonText(String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty json content");
        }
        objectMapper.readTree(content);
    }

    /**
     * 读取文本文件。
     *
     * @return 文件内容；文件不存在时返回 null
     */
    public String readText(String relativePath) throws IOException {
        Path target = resolve(relativePath);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /**
     * 读取 JSON 对象；空文件/不存在时返回 null。
     */
    public <T> T readJson(String relativePath, Class<T> type) throws IOException {
        String text = readText(relativePath);
        if (text == null || text.isBlank()) {
            return null;
        }
        return objectMapper.readValue(text, type);
    }

    /**
     * 读取 JSON 数组；空文件/不存在时返回空列表。
     */
    public <T> List<T> readJsonList(String relativePath, TypeReference<List<T>> typeRef) throws IOException {
        String text = readText(relativePath);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<T> list = objectMapper.readValue(text, typeRef);
        return list == null ? List.of() : list;
    }

    /**
     * 把对象序列化为格式化 JSON 再写盘。
     */
    public void writeJson(String relativePath, Object value) throws IOException {
        writeText(relativePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /**
     * 读取 CSV：按换行拆分，每行再按逗号拆列，保留尾部空字段。
     */
    public List<String[]> readCsv(String relativePath) throws IOException {
        String text = readText(relativePath);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            rows.add(line.split(",", -1));
        }
        return rows;
    }

    /**
     * 写 CSV：固定写入一行注释头，便于人工识别文件由导入工具生成。
     */
    public void writeCsv(String relativePath, List<String[]> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by config import\n");
        for (String[] row : rows) {
            sb.append(String.join(",", row)).append('\n');
        }
        writeText(relativePath, sb.toString());
    }

    /** 读取活动排期文件 {@code ActivityScheduling.json}。 */
    public List<Map<String, Object>> readScheduleEntries() throws IOException {
        return readJsonList("ActivityScheduling.json", new TypeReference<>() {});
    }

    /** 写入活动排期文件。 */
    public void writeScheduleEntries(List<Map<String, Object>> entries) throws IOException {
        writeJson("ActivityScheduling.json", entries);
    }

    /** 读取卡池/横幅配置文件 {@code Banners.json}。 */
    public List<Map<String, Object>> readBannerEntries() throws IOException {
        return readJsonList("Banners.json", new TypeReference<>() {});
    }

    /** 写入卡池/横幅配置文件。 */
    public void writeBannerEntries(List<Map<String, Object>> entries) throws IOException {
        writeJson("Banners.json", entries);
    }

    /**
     * 合并单条活动排期：按 activityId 去重后追加最新条目。
     */
    public void mergeScheduleEntry(Map<String, Object> entry) throws IOException {
        List<Map<String, Object>> list = new ArrayList<>(readScheduleEntries());
        Object activityId = entry.get("activityId");
        list.removeIf(e -> activityId != null && activityId.equals(e.get("activityId")));
        list.add(entry);
        writeScheduleEntries(list);
    }

    /**
     * 合并卡池/横幅条目：按 id 去重后追加。
     */
    public void mergeBannerEntries(List<Map<String, Object>> banners) throws IOException {
        if (banners == null || banners.isEmpty()) {
            return;
        }
        List<Map<String, Object>> list = new ArrayList<>(readBannerEntries());
        for (Map<String, Object> banner : banners) {
            Object id = banner.get("id");
            list.removeIf(e -> id != null && id.equals(e.get("id")));
            list.add(banner);
        }
        writeBannerEntries(list);
    }

    /**
     * 合并物品 CSV 行：按第一列 id 去重并更新；若原表为空则补一个默认表头。
     */
    public void mergeItemRows(List<String[]> newRows) throws IOException {
        if (newRows == null || newRows.isEmpty()) {
            return;
        }
        List<String[]> rows = new ArrayList<>(readCsv("items_config.csv"));
        if (rows.isEmpty()) {
            rows.add(new String[]{"id", "name", "stack"});
        }
        String[] header = rows.get(0);
        Map<String, Integer> indexById = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            indexById.put(rows.get(i)[0], i);
        }
        for (String[] row : newRows) {
            if (row.length == 0) {
                continue;
            }
            Integer idx = indexById.get(row[0]);
            if (idx == null) {
                rows.add(padRow(row, header.length));
                indexById.put(row[0], rows.size() - 1);
            } else {
                rows.set(idx, padRow(row, header.length));
            }
        }
        writeCsv("items_config.csv", rows);
    }

    /** 读取统一活动配置列表。 */
    public List<ActivityConfig> readActivityConfigs() throws IOException {
        return readJsonList("ActivityConfigs.json", new TypeReference<>() {});
    }

    /** 写入统一活动配置列表。 */
    public void writeActivityConfigs(List<ActivityConfig> configs) throws IOException {
        writeJson("ActivityConfigs.json", configs);
    }

    /**
     * 合并单条统一活动配置：按 activityId 去重后更新。
     */
    public void mergeActivityConfigEntry(ActivityConfig config) throws IOException {
        if (config == null || config.getActivityId() <= 0) {
            return;
        }
        List<ActivityConfig> list = new ArrayList<>(readActivityConfigs());
        list.removeIf(c -> c.getActivityId() == config.getActivityId());
        list.add(config);
        writeActivityConfigs(list);
    }

    /**
     * 把较短的 CSV 行补齐到指定列数，避免写回后列数不一致。
     */
    private static String[] padRow(String[] row, int length) {
        if (row.length >= length) {
            return row;
        }
        String[] padded = new String[length];
        System.arraycopy(row, 0, padded, 0, row.length);
        for (int i = row.length; i < length; i++) {
            padded[i] = "";
        }
        return padded;
    }
}
