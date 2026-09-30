package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.ops.ServerClockService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 版本主题生命周期：主线章节 / 活动池 / 卡池 / 商店 / Boss 配置集，
 * 经 {@link ServerClockService} 统一切换，并支持预加载下一主题。
 */
@Service
public class VersionThemeService {

    private static final Logger log = LoggerFactory.getLogger(VersionThemeService.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ThemeConfig(String themeId, String displayName, long startEpochMs, long endEpochMs,
                              int chapterSetId, int activityPoolId, int bannerPoolId,
                              int shopSetId, int bossSetId, String preloadAssetPack,
                              List<String> miniGamePool) {
        public ThemeConfig {
            miniGamePool = miniGamePool == null ? List.of() : List.copyOf(miniGamePool);
        }

        /** 兼容旧 10 字段配置。 */
        public ThemeConfig(String themeId, String displayName, long startEpochMs, long endEpochMs,
                           int chapterSetId, int activityPoolId, int bannerPoolId,
                           int shopSetId, int bossSetId, String preloadAssetPack) {
            this(themeId, displayName, startEpochMs, endEpochMs, chapterSetId, activityPoolId,
                    bannerPoolId, shopSetId, bossSetId, preloadAssetPack, List.of());
        }
    }

    public record ThemeView(ThemeConfig current, ThemeConfig next, boolean preloadReady) {}

    private final ObjectMapper objectMapper;
    private final ServerClockService clock;
    private final GameSessionManager sessionManager;
    private final Path configPath;
    private final AtomicReference<List<ThemeConfig>> themes = new AtomicReference<>(List.of());
    private final AtomicReference<String> preloadThemeId = new AtomicReference<>("");

    public VersionThemeService(ObjectMapper objectMapper,
                               ObjectProvider<ServerClockService> clockProvider,
                               ObjectProvider<GameSessionManager> sessionProvider,
                               @Value("${lunarcore.theme.config:data/VersionThemeConfigs.json}") String configPath) {
        this.objectMapper = objectMapper;
        this.clock = clockProvider == null ? null : clockProvider.getIfAvailable();
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.configPath = Path.of(configPath);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public void reload() {
        try {
            if (!Files.exists(configPath)) {
                themes.set(List.of(
                        new ThemeConfig("1.0", "启程", 0, Long.MAX_VALUE / 4,
                                1, 1, 1, 1, 1, "pack_theme_1_0"),
                        new ThemeConfig("2.0", "新星球", Long.MAX_VALUE / 4, Long.MAX_VALUE / 2,
                                2, 2, 2, 2, 2, "pack_theme_2_0")
                ));
                return;
            }
            List<ThemeConfig> list = objectMapper.readValue(Files.readString(configPath),
                    new TypeReference<>() {});
            themes.set(list == null ? List.of() : List.copyOf(list));
        } catch (Exception e) {
            log.warn("version_theme_load_fail path={} err={}", configPath, e.getMessage());
        }
    }

    public ThemeView currentView() {
        long now = clock == null ? System.currentTimeMillis() : clock.now().toEpochMilli();
        ThemeConfig cur = null;
        ThemeConfig next = null;
        for (ThemeConfig t : themes.get()) {
            if (now >= t.startEpochMs() && now < t.endEpochMs()) {
                cur = t;
            } else if (cur != null && next == null && t.startEpochMs() >= cur.endEpochMs()) {
                next = t;
            } else if (cur == null && t.startEpochMs() > now && next == null) {
                next = t;
            }
        }
        if (cur == null && !themes.get().isEmpty()) {
            cur = themes.get().get(0);
            if (themes.get().size() > 1) {
                next = themes.get().get(1);
            }
        }
        boolean preload = next != null && next.themeId().equals(preloadThemeId.get());
        return new ThemeView(cur, next, preload);
    }

    public boolean preloadNext() {
        ThemeView v = currentView();
        if (v.next() == null) {
            return false;
        }
        preloadThemeId.set(v.next().themeId());
        broadcast(v);
        return true;
    }

    public void broadcast(ThemeView view) {
        if (sessionManager == null || view.current() == null) {
            return;
        }
        ThemeConfig c = view.current();
        String nextId = view.next() == null ? "" : view.next().themeId();
        String miniGames = c.miniGamePool() == null || c.miniGamePool().isEmpty()
                ? "[]"
                : "[\"" + String.join("\",\"", c.miniGamePool()) + "\"]";
        String json = "{\"themeId\":\"" + c.themeId() + "\",\"displayName\":\"" + c.displayName()
                + "\",\"chapterSetId\":" + c.chapterSetId()
                + ",\"activityPoolId\":" + c.activityPoolId()
                + ",\"bannerPoolId\":" + c.bannerPoolId()
                + ",\"shopSetId\":" + c.shopSetId()
                + ",\"bossSetId\":" + c.bossSetId()
                + ",\"miniGamePool\":" + miniGames
                + ",\"nextThemeId\":\"" + nextId + "\""
                + ",\"preloadAssetPack\":\"" + (view.next() == null ? "" : view.next().preloadAssetPack())
                + "\",\"preloadReady\":" + view.preloadReady() + "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        // 广播给在线玩家成本高时可由网关扇出；此处仅作骨架
        log.info("version_theme_broadcast theme={} next={} at={}", c.themeId(), nextId, Instant.now());
        for (GameSession ignored : List.<GameSession>of()) {
            ignored.send(new GamePacket(CmdIds.VERSION_THEME_SC_NOTIFY, payload));
        }
    }
}
