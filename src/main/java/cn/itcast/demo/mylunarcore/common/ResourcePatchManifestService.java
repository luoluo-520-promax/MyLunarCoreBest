package cn.itcast.demo.mylunarcore.common;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 资源增量补丁 Manifest：记录文件 MD5 与差异块（bsdiff/xdelta），配合 ResourcePatchScNotify.resumeOffset 断点续传。
 */
@Service
public class ResourcePatchManifestService {

    public record DiffBlock(long offset, long length, String algo, String patchUrl, String patchMd5) {}

    public record FilePatch(String path, String md5, long size, String fullUrl,
                            List<DiffBlock> blocks) {}

    public record PatchManifest(String fromVersion, String toVersion, List<FilePatch> files) {}

    private final Map<String, PatchManifest> manifests = new ConcurrentHashMap<>();
    private final AtomicLong preheatCount = new AtomicLong();

    public void putManifest(PatchManifest manifest) {
        if (manifest == null || manifest.fromVersion() == null || manifest.toVersion() == null) {
            return;
        }
        manifests.put(key(manifest.fromVersion(), manifest.toVersion()), manifest);
    }

    public PatchManifest get(String fromVersion, String toVersion) {
        return manifests.get(key(fromVersion, toVersion));
    }

    /**
     * 按 resumeOffset 裁剪未完成的差异块，仅返回仍需下载的部分。
     */
    public List<Map<String, Object>> resumeBlocks(FilePatch file, long resumeOffset) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (file == null) {
            return out;
        }
        long offset = Math.max(0L, resumeOffset);
        if (file.blocks() == null || file.blocks().isEmpty()) {
            Map<String, Object> full = new LinkedHashMap<>();
            full.put("path", file.path());
            full.put("md5", file.md5());
            full.put("size", file.size());
            full.put("url", file.fullUrl());
            full.put("resumeOffset", Math.min(offset, file.size()));
            full.put("algo", "full");
            out.add(full);
            return out;
        }
        for (DiffBlock b : file.blocks()) {
            long end = b.offset() + b.length();
            if (end <= offset) {
                continue;
            }
            long skip = Math.max(0L, offset - b.offset());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("path", file.path());
            m.put("blockOffset", b.offset());
            m.put("length", b.length() - skip);
            m.put("resumeOffset", skip);
            m.put("algo", b.algo() == null ? "bsdiff" : b.algo());
            m.put("patchUrl", b.patchUrl());
            m.put("patchMd5", b.patchMd5());
            out.add(m);
        }
        return out;
    }

    /** CDN 边缘预热计数（活动更新前调用）。 */
    public long markCdnPreheat(String version, int edgeNodes) {
        long n = preheatCount.addAndGet(Math.max(1, edgeNodes));
        return n;
    }

    public long preheatCount() {
        return preheatCount.get();
    }

    private static String key(String from, String to) {
        return (from == null ? "" : from) + "->" + (to == null ? "" : to);
    }
}
