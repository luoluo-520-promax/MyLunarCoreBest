package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端资源版本调度中心：维护 AssetBundle/PAK Manifest，登录时下发 CDN 差异列表。
 */
@Service
public class ClientResourceManifestService {

    public record ManifestEntry(String path, String hash, long size, String cdnUrl, String locale) {}

    public record DiffResult(String clientVersion, String serverVersion, List<ManifestEntry> download,
                             List<String> delete) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private final LunarCoreProperties properties;
    private final HotfixDataService hotfixDataService;
    private volatile String serverVersion = "0.0.0";
    private final ConcurrentHashMap<String, ManifestEntry> entries = new ConcurrentHashMap<>();

    public ClientResourceManifestService(LunarCoreProperties properties, HotfixDataService hotfixDataService) {
        this.properties = properties;
        this.hotfixDataService = hotfixDataService;
        syncFromHotfix();
    }

    public synchronized void syncFromHotfix() {
        HotfixData data = hotfixDataService.current();
        if (data == null) {
            return;
        }
        serverVersion = data.getHotfixVersion() == null ? serverVersion : data.getHotfixVersion();
        HotfixData.VersionManifestInfo info = data.getVersionInfo();
        if (info != null && info.getFiles() != null) {
            String base = data.getClientResourceBaseUrl() == null ? "" : data.getClientResourceBaseUrl();
            for (HotfixData.ManifestFileEntry f : info.getFiles()) {
                if (f == null || f.getPath() == null || f.getPath().isBlank()) {
                    continue;
                }
                String url = base.isBlank() ? f.getPath() : base.replaceAll("/$", "") + "/" + f.getPath().replaceAll("^/", "");
                entries.put(f.getPath(), new ManifestEntry(f.getPath(),
                        f.getHash() == null ? "" : f.getHash(),
                        f.getSize(), url, ""));
            }
        }
    }

    public synchronized DiffResult diffForClient(String clientVersion, Map<String, String> clientHashes) {
        List<ManifestEntry> download = new ArrayList<>();
        Map<String, String> client = clientHashes == null ? Map.of() : clientHashes;
        for (ManifestEntry e : entries.values()) {
            String ch = client.get(e.path());
            if (ch == null || !ch.equalsIgnoreCase(e.hash())) {
                download.add(e);
            }
        }
        List<String> delete = new ArrayList<>();
        HotfixData data = hotfixDataService.current();
        if (data != null && data.getDeletedFiles() != null) {
            delete.addAll(data.getDeletedFiles());
        }
        return new DiffResult(clientVersion == null ? "" : clientVersion, serverVersion, download, delete);
    }

    /**
     * 资源版本锁：客户端版本落后于服务端 Manifest，或关键资源哈希不匹配时拦截登录。
     * @param softPatchAllowed true 时返回 ok=false 但仍提示可走差量补丁（保持会话），由 ResourcePatchService 下发清单。
     */
    public record LockResult(boolean ok, String serverVersion, int missingOrMismatch, String updateHint,
                             boolean softPatchAllowed) {
        public static LockResult pass(String ver) {
            return new LockResult(true, ver, 0, "", false);
        }

        public static LockResult fail(String ver, int mismatch, String hint) {
            return new LockResult(false, ver, mismatch, hint == null ? "" : hint, false);
        }

        public static LockResult softFail(String ver, int mismatch) {
            return new LockResult(false, ver, mismatch, "RESOURCE_PATCH", true);
        }
    }

    public synchronized LockResult checkResourceLock(String clientVersion, Map<String, String> clientHashes) {
        return checkResourceLock(clientVersion, clientHashes, false);
    }

    public synchronized LockResult checkResourceLock(String clientVersion, Map<String, String> clientHashes,
                                                     boolean allowSoftPatchKeepSession) {
        if (entries.isEmpty()) {
            return LockResult.pass(serverVersion);
        }
        if (clientHashes == null || clientHashes.isEmpty()) {
            if (clientVersion == null || clientVersion.isBlank()) {
                return allowSoftPatchKeepSession
                        ? LockResult.softFail(serverVersion, entries.size())
                        : LockResult.fail(serverVersion, entries.size(), "RESOURCE_OUTDATED");
            }
            if (cn.itcast.demo.mylunarcore.player.ClientVersionGateService.compareSemver(clientVersion, serverVersion) < 0) {
                return allowSoftPatchKeepSession
                        ? LockResult.softFail(serverVersion, entries.size())
                        : LockResult.fail(serverVersion, entries.size(), "RESOURCE_OUTDATED");
            }
            return LockResult.pass(serverVersion);
        }
        DiffResult diff = diffForClient(clientVersion, clientHashes);
        if (!diff.download().isEmpty()) {
            return allowSoftPatchKeepSession
                    ? LockResult.softFail(serverVersion, diff.download().size())
                    : LockResult.fail(serverVersion, diff.download().size(), "RESOURCE_OUTDATED");
        }
        return LockResult.pass(serverVersion);
    }

    public String serverVersion() {
        return serverVersion;
    }

    public synchronized void upsert(ManifestEntry entry) {
        if (entry == null || entry.path() == null || entry.path().isBlank()) {
            return;
        }
        entries.put(entry.path(), entry);
        serverVersion = Instant.now().toString();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("serverVersion", serverVersion);
        m.put("count", entries.size());
        m.put("entries", List.copyOf(entries.values()));
        return m;
    }

    public synchronized Path persistToDataDir() throws Exception {
        Path dir = Path.of(properties.getDataDir());
        Files.createDirectories(dir);
        Path file = dir.resolve("ClientResourceManifest.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot());
        return file;
    }
}
