package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.ClientResourceManifestService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端资源包版本门禁：低于阈值或落后于 Resource Manifest 则拒绝登录。
 */
@Service
public class ClientVersionGateService {

    private static final Logger log = LoggerFactory.getLogger(ClientVersionGateService.class);

    /** 与登录 retcode 对齐：客户端资源过旧 */
    public static final int ERR_CLIENT_TOO_OLD = 10;
    /** 资源 Manifest 哈希/版本落后，强制跳转更新页（RESOURCE_OUTDATED） */
    public static final int ERR_RESOURCE_OUTDATED = 11;
    /** 超过强制升级窗口仍未升级 */
    public static final int ERR_FORCE_UPGRADE = 12;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RequiredVersions(String minClientResVersion, String updateUrl, String minClientAppVersion,
                                   long forceUpgradeDeadline) {
        public RequiredVersions(String minClientResVersion, String updateUrl, String minClientAppVersion) {
            this(minClientResVersion, updateUrl, minClientAppVersion, 0L);
        }

        static RequiredVersions defaults() {
            return new RequiredVersions("1.0.0", "", "1.0.0", 0L);
        }
    }

    public record GateResult(boolean allowed, int retcode, String requiredResVersion, String updateUrl,
                             long forceUpgradeDeadline) {
        public static GateResult ok(String required) {
            return new GateResult(true, 0, required, "", 0L);
        }

        public static GateResult ok(String required, long deadline) {
            return new GateResult(true, 0, required, "", deadline);
        }

        public static GateResult reject(String required, String url) {
            return new GateResult(false, ERR_CLIENT_TOO_OLD, required, url == null ? "" : url, 0L);
        }

        public static GateResult resourceOutdated(String required, String url) {
            return new GateResult(false, ERR_RESOURCE_OUTDATED, required, url == null ? "" : url, 0L);
        }

        public static GateResult forceUpgrade(String required, String url, long deadline) {
            return new GateResult(false, ERR_FORCE_UPGRADE, required, url == null ? "" : url, deadline);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final AtomicReference<RequiredVersions> required = new AtomicReference<>(RequiredVersions.defaults());
    private final ObjectProvider<ClientResourceManifestService> manifestProvider;

    public ClientVersionGateService(ObjectMapper objectMapper,
                                    @Value("${lunarcore.data-dir:data}") String dataDir,
                                    ObjectProvider<ClientResourceManifestService> manifestProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.manifestProvider = manifestProvider;
    }

    /** 单测便捷构造：无 Manifest 强校验。 */
    public ClientVersionGateService(ObjectMapper objectMapper, String dataDir) {
        this(objectMapper, dataDir, null);
    }

    @PostConstruct
    public void load() {
        Path file = dataDir.resolve("ClientRequiredVersions.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RequiredVersions cfg = objectMapper.readValue(Files.readString(file), RequiredVersions.class);
            if (cfg != null && cfg.minClientResVersion() != null && !cfg.minClientResVersion().isBlank()) {
                required.set(cfg);
                log.info("ClientRequiredVersions loaded minRes={}", cfg.minClientResVersion());
            }
        } catch (Exception e) {
            log.warn("load ClientRequiredVersions failed: {}", e.toString());
        }
    }

    public GateResult check(String clientResVersion) {
        return check(clientResVersion, null);
    }

    /**
     * @param clientHashes 客户端上报的 path→hash；非空时做 Manifest 强校验，落后返回 RESOURCE_OUTDATED。
     */
    public GateResult check(String clientResVersion, Map<String, String> clientHashes) {
        RequiredVersions cfg = required.get();
        String min = cfg.minClientResVersion();
        if (min == null || min.isBlank()) {
            return GateResult.ok(min, cfg.forceUpgradeDeadline());
        }
        if (clientResVersion == null || clientResVersion.isBlank()) {
            return GateResult.reject(min, cfg.updateUrl());
        }
        if (compareSemver(clientResVersion.trim(), min) < 0) {
            long deadline = cfg.forceUpgradeDeadline();
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                return GateResult.forceUpgrade(min, cfg.updateUrl(), deadline);
            }
            return GateResult.reject(min, cfg.updateUrl());
        }
        var manifest = manifestProvider == null ? null : manifestProvider.getIfAvailable();
        if (manifest != null) {
            ClientResourceManifestService.LockResult lock = manifest.checkResourceLock(clientResVersion, clientHashes);
            if (!lock.ok()) {
                String req = lock.serverVersion() == null || lock.serverVersion().isBlank() ? min : lock.serverVersion();
                String url = cfg.updateUrl() == null || cfg.updateUrl().isBlank() ? lock.updateHint() : cfg.updateUrl();
                long deadline = cfg.forceUpgradeDeadline();
                if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                    return GateResult.forceUpgrade(req, url, deadline);
                }
                return GateResult.resourceOutdated(req, url);
            }
        }
        return GateResult.ok(min, cfg.forceUpgradeDeadline());
    }

    public RequiredVersions current() {
        return required.get();
    }

    /** 简易 semver 比较：a &lt; b → 负；相等 → 0；a &gt; b → 正。 */
    public static int compareSemver(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length && !pa[i].isBlank() ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isBlank() ? parseIntSafe(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
