package cn.itcast.demo.mylunarcore.cutscene;

import cn.itcast.demo.mylunarcore.settings.PlayerSettings;
import cn.itcast.demo.mylunarcore.settings.PlayerSettingsApplicationService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 剧情过场触发器服务。
 *
 * <p>职责：
 * <ul>
 *   <li>从 <code>CutsceneConfigs.json</code> 读取过场配置（cutsceneId、timelineAsset、unlockCgId、skippable）；</li>
 *   <li>依据玩家设置 <code>skipStoryCutscene</code> 判断是否自动跳过；</li>
 *   <li>处理客户端的播放/跳过/完成上报，并把状态落库到 <code>cutscene_progress</code>；</li>
 *   <li>对外提供 hasCompleted() 查询某过场是否已完成或被跳过。</li>
 * </ul>
 */
@Service
public class CutsceneTriggerService {

    /**
     * 过场配置。
     *
     * @param cutsceneId    过场唯一 ID
     * @param timelineAsset 时间轴资源名/路径
     * @param unlockCgId    解锁的 CG 编号（配置中可能用来控制播放前置）
     * @param skippable     该过场是否允许跳过
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CutsceneConfig(String cutsceneId, String timelineAsset, int unlockCgId, boolean skippable) {
        public CutsceneConfig {
            // 防止配置里出现 null，统一规整为非空字符串，减少后续空指针判断
            cutsceneId = cutsceneId == null ? "" : cutsceneId;
            timelineAsset = timelineAsset == null ? "" : timelineAsset;
        }
    }

    /**
     * 触发/跳过/完成接口的统一返回结构。
     *
     * @param ok           是否成功
     * @param retcode       错误码：0=成功，1=配置不存在，2=不可跳过（仅 skip 时使用）
     * @param config       对应过场配置
     * @param autoSkipped  是否因玩家设置而自动跳过
     */
    public record TriggerResult(boolean ok, int retcode, CutsceneConfig config, boolean autoSkipped) {
        public TriggerResult(boolean ok, CutsceneConfig config) {
            this(ok, ok ? 0 : 1, config, false);
        }
    }

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;
    /** 配置目录（通常是 data/）。 */
    private final Path dataDir;
    /** 数据库访问模板，用于写入/查询 cutscene_progress。 */
    private final JdbcTemplate jdbc;
    /** 玩家设置服务提供器：为了在配置缺失或依赖未就绪时保持轻量可用。 */
    private final ObjectProvider<PlayerSettingsApplicationService> settingsProvider;
    /** 过场配置缓存：cutsceneId → 配置。 */
    private final Map<String, CutsceneConfig> configs = new ConcurrentHashMap<>();
    /** 过场状态缓存：playerId:cutsceneId → 状态（PLAYING/DONE/SKIPPED）。 */
    private final Map<String, String> progress = new ConcurrentHashMap<>();

    /**
     * 轻量构造器：仅设置 JSON 与 data 目录，不接数据库与玩家设置服务。
     * 主要用于离线工具或测试场景。
     */
    public CutsceneTriggerService(ObjectMapper objectMapper,
                                  @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.jdbc = null;
        this.settingsProvider = new ObjectProvider<>() {
            @Override
            public PlayerSettingsApplicationService getObject() {
                return null;
            }

            @Override
            public PlayerSettingsApplicationService getObject(Object... args) {
                return null;
            }

            @Override
            public PlayerSettingsApplicationService getIfAvailable() {
                return null;
            }

            @Override
            public PlayerSettingsApplicationService getIfUnique() {
                return null;
            }
        };
        this.dataDir = Path.of(dataDir);
    }

    /**
     * 完整构造器：提供数据库与玩家设置依赖。
     */
    public CutsceneTriggerService(ObjectMapper objectMapper,
                                  JdbcTemplate jdbc,
                                  ObjectProvider<PlayerSettingsApplicationService> settingsProvider,
                                  @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.settingsProvider = settingsProvider;
        this.dataDir = Path.of(dataDir);
    }

    /** 应用启动后自动加载过场配置。 */
    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * 重新加载 CutsceneConfigs.json。
     *
     * <p>如果文件不存在则清空配置缓存并返回 false；若解析成功则覆盖整个 configs 缓存。
     *
     * @return 是否成功载入配置
     */
    public boolean reload() {
        Path file = dataDir.resolve("CutsceneConfigs.json");
        if (!Files.isRegularFile(file)) {
            configs.clear();
            return false;
        }
        try {
            List<CutsceneConfig> list = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            configs.clear();
            for (CutsceneConfig c : list) {
                if (c != null && !c.cutsceneId().isBlank()) {
                    configs.put(c.cutsceneId(), c);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 触发某段过场。
     *
     * <p>流程：先查配置是否存在；再读取玩家设置 skipStoryCutscene，若该设置开启且当前过场允许跳过，
     * 则直接标记为 SKIPPED 并返回 autoSkipped=true；否则标记为 PLAYING。
     *
     * @param playerId   玩家 ID
     * @param cutsceneId 过场 ID
     * @return 触发结果
     */
    public TriggerResult trigger(int playerId, String cutsceneId) {
        CutsceneConfig config = configs.get(cutsceneId);
        if (config == null) {
            return new TriggerResult(false, 1, null, false);
        }
        boolean preferSkip = false;
        PlayerSettingsApplicationService settings = settingsProvider.getIfAvailable();
        if (settings != null) {
            try {
                PlayerSettings ps = settings.getOrCreate(playerId);
                preferSkip = ps.getGameplay() != null && ps.getGameplay().isSkipStoryCutscene();
            } catch (Exception ignored) {
                // 玩家设置读取失败时不影响过场触发
            }
        }
        if (preferSkip && config.skippable()) {
            mark(playerId, cutsceneId, "SKIPPED", true);
            return new TriggerResult(true, 0, config, true);
        }
        mark(playerId, cutsceneId, "PLAYING", false);
        return new TriggerResult(true, 0, config, false);
    }

    /**
     * 客户端跳过请求。
     *
     * <p>仅当过场配置允许跳过时才会成功，否则返回 retcode=2。
     */
    public TriggerResult skip(int playerId, String cutsceneId) {
        CutsceneConfig config = configs.get(cutsceneId);
        if (config == null) {
            return new TriggerResult(false, 1, null, false);
        }
        if (!config.skippable()) {
            return new TriggerResult(false, 2, config, false);
        }
        mark(playerId, cutsceneId, "SKIPPED", true);
        return new TriggerResult(true, 0, config, true);
    }

    /** 客户端上报播放完成。 */
    public TriggerResult complete(int playerId, String cutsceneId) {
        CutsceneConfig config = configs.get(cutsceneId);
        if (config == null) {
            return new TriggerResult(false, 1, null, false);
        }
        mark(playerId, cutsceneId, "DONE", false);
        return new TriggerResult(true, 0, config, false);
    }

    /**
     * 查询某过场是否已经完成（播放结束或已跳过）。
     *
     * <p>先查内存 progress 缓存；若缓存没有，再查数据库 cutscene_progress 表中 status 为
     * DONE/SKIPPED 的记录。
     */
    public boolean hasCompleted(int playerId, String cutsceneId) {
        String st = progress.get(playerId + ":" + cutsceneId);
        if ("DONE".equals(st) || "SKIPPED".equals(st)) {
            return true;
        }
        try {
            Integer n = jdbc.query("""
                    SELECT 1 FROM cutscene_progress
                    WHERE player_id=? AND cutscene_id=? AND status IN ('DONE','SKIPPED')
                    """, rs -> rs.next() ? 1 : null, playerId, cutsceneId);
            return n != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 直接返回过场配置快照，供客户端或其他业务查询。 */
    public CutsceneConfig find(String cutsceneId) {
        return configs.get(cutsceneId);
    }

    public record ReplayResult(boolean ok, int retcode, String replayTicket, String timelineAsset) {
        public static ReplayResult fail(int retcode) {
            return new ReplayResult(false, retcode, "", "");
        }
    }

    /**
     * 剧情回放：仅存储已播 ID，校验通过后下发允许播放密钥，不依赖客户端本地录像。
     */
    public ReplayResult issueReplay(int playerId, String cutsceneId) {
        if (cutsceneId == null || cutsceneId.isBlank()) {
            return ReplayResult.fail(3);
        }
        CutsceneConfig config = configs.get(cutsceneId);
        if (config == null) {
            return ReplayResult.fail(3);
        }
        if (!hasCompleted(playerId, cutsceneId)) {
            return ReplayResult.fail(2);
        }
        String ticket = playerId + ":" + cutsceneId + ":" + Long.toHexString(System.currentTimeMillis());
        return new ReplayResult(true, 0, ticket, config.timelineAsset());
    }

    /**
     * 将某过场状态写入内存与数据库。
     *
     * <p>数据库采用 INSERT ... ON DUPLICATE KEY UPDATE 维护最新状态，
     * skipped 字段用 1/0 保存布尔值。
     */
    private void mark(int playerId, String cutsceneId, String status, boolean skipped) {
        progress.put(playerId + ":" + cutsceneId, status);
        try {
            jdbc.update("""
                    INSERT INTO cutscene_progress (player_id, cutscene_id, status, skipped)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE status=VALUES(status), skipped=VALUES(skipped)
                    """, playerId, cutsceneId, status, skipped ? 1 : 0);
        } catch (Exception ignored) {
            // memory only
        }
    }
}
