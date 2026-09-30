package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源差量补丁：基于 Manifest 差异生成可断点续传的下载清单，更新期间保持登录。
 * 支持 bsdiff/xdelta 差异块与 resumeOffset。
 */
@Service
public class ResourcePatchService {

    public record PatchFile(String path, String md5, long size, String url, long resumeOffset,
                            String algo, List<Map<String, Object>> blocks) {
        public PatchFile(String path, String md5, long size, String url, long resumeOffset) {
            this(path, md5, size, url, resumeOffset, "full", List.of());
        }
    }

    public record PatchPlan(String clientVersion, String serverVersion, List<PatchFile> files,
                            List<String> delete, boolean keepSession, String hint) {}

    private final ClientResourceManifestService manifestService;
    private final GameSessionManager sessionManager;
    private final ResourcePatchManifestService patchManifestService;

    public ResourcePatchService(ClientResourceManifestService manifestService,
                                ObjectProvider<GameSessionManager> sessionProvider) {
        this(manifestService, sessionProvider, null);
    }

    public ResourcePatchService(ClientResourceManifestService manifestService,
                                ObjectProvider<GameSessionManager> sessionProvider,
                                ObjectProvider<ResourcePatchManifestService> patchManifestProvider) {
        this.manifestService = manifestService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.patchManifestService = patchManifestProvider == null ? null : patchManifestProvider.getIfAvailable();
    }

    /**
     * @param resumeOffsets 客户端已下载字节偏移（path → offset），用于断点续传
     */
    public PatchPlan buildPatch(String clientVersion, Map<String, String> clientHashes,
                                Map<String, Long> resumeOffsets, boolean keepSession) {
        ClientResourceManifestService.DiffResult diff = manifestService.diffForClient(clientVersion, clientHashes);
        Map<String, Long> offsets = resumeOffsets == null ? Map.of() : resumeOffsets;
        ResourcePatchManifestService.PatchManifest delta =
                patchManifestService == null ? null : patchManifestService.get(clientVersion, diff.serverVersion());
        List<PatchFile> files = new ArrayList<>();
        for (ClientResourceManifestService.ManifestEntry e : diff.download()) {
            long offset = Math.max(0L, offsets.getOrDefault(e.path(), 0L));
            if (offset > e.size()) {
                offset = 0L;
            }
            ResourcePatchManifestService.FilePatch fp = findFile(delta, e.path());
            if (fp != null && patchManifestService != null) {
                List<Map<String, Object>> blocks = patchManifestService.resumeBlocks(fp, offset);
                files.add(new PatchFile(e.path(), e.hash(), e.size(), e.cdnUrl(), offset, "bsdiff", blocks));
            } else {
                files.add(new PatchFile(e.path(), e.hash(), e.size(), e.cdnUrl(), offset));
            }
        }
        return new PatchPlan(diff.clientVersion(), diff.serverVersion(), files, diff.delete(),
                keepSession, files.isEmpty() ? "up_to_date" : "RESOURCE_PATCH");
    }

    private static ResourcePatchManifestService.FilePatch findFile(ResourcePatchManifestService.PatchManifest m,
                                                                   String path) {
        if (m == null || m.files() == null) {
            return null;
        }
        for (ResourcePatchManifestService.FilePatch f : m.files()) {
            if (f != null && path != null && path.equals(f.path())) {
                return f;
            }
        }
        return null;
    }

    /** 下发差量清单；keepSession=true 时不踢登录。 */
    public PatchPlan notifyPatch(long uid, String clientVersion, Map<String, String> clientHashes,
                                 Map<String, Long> resumeOffsets) {
        PatchPlan plan = buildPatch(clientVersion, clientHashes, resumeOffsets, true);
        if (sessionManager == null) {
            return plan;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"serverVersion\":\"").append(plan.serverVersion())
                .append("\",\"keepSession\":").append(plan.keepSession())
                .append(",\"files\":[");
        for (int i = 0; i < plan.files().size(); i++) {
            PatchFile f = plan.files().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":\"").append(f.path()).append("\",\"md5\":\"").append(f.md5())
                    .append("\",\"size\":").append(f.size()).append(",\"url\":\"").append(f.url())
                    .append("\",\"resumeOffset\":").append(f.resumeOffset())
                    .append(",\"algo\":\"").append(f.algo() == null ? "full" : f.algo()).append("\"}");
        }
        sb.append("],\"delete\":[");
        for (int i = 0; i < plan.delete().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(plan.delete().get(i)).append('"');
        }
        sb.append("]}");
        GameSession s = sessionManager.getOrNull(uid);
        if (s != null) {
            s.send(new GamePacket(CmdIds.RESOURCE_PATCH_SC_NOTIFY, sb.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return plan;
    }

    public Map<String, Object> toMap(PatchPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientVersion", plan.clientVersion());
        m.put("serverVersion", plan.serverVersion());
        m.put("keepSession", plan.keepSession());
        m.put("hint", plan.hint());
        m.put("files", plan.files());
        m.put("delete", plan.delete());
        return m;
    }
}
