package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigGrayAutoRollbackService;
import cn.itcast.demo.mylunarcore.common.HotReloadCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 配置 Git 版本管理：data/ 目录提交关联 config_release_id，支持一键回退并触发灰度自动回滚。
 */
@RestController
@RequestMapping("/api/admin/config-git")
public class ConfigGitVersionController {

    private final HotReloadCoordinator hotReload;
    private final ConfigGrayAutoRollbackService grayRollback;
    private final Path repoRoot;

    public ConfigGitVersionController(ObjectProvider<HotReloadCoordinator> hotReloadProvider,
                                      ObjectProvider<ConfigGrayAutoRollbackService> grayProvider) {
        this.hotReload = hotReloadProvider == null ? null : hotReloadProvider.getIfAvailable();
        this.grayRollback = grayProvider == null ? null : grayProvider.getIfAvailable();
        this.repoRoot = Path.of("").toAbsolutePath();
    }

    @GetMapping("/log")
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> log(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = Math.max(1, Math.min(100, limit));
        List<String> lines = runGit("log", "--oneline", "-n", String.valueOf(n), "--", "data");
        out.put("ok", true);
        out.put("commits", lines);
        return out;
    }

    @PostMapping("/commit-import")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> commitImport(@RequestParam String configReleaseId,
                                            @RequestParam(defaultValue = "config import") String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        String msg = "[config_release_id=" + configReleaseId + "] " + message;
        runGit("add", "data");
        List<String> commit = runGit("commit", "-m", msg);
        out.put("ok", true);
        out.put("configReleaseId", configReleaseId);
        out.put("commit", commit);
        return out;
    }

    @PostMapping("/rollback")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> rollback(@RequestParam String commitSha) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (commitSha == null || !commitSha.matches("[0-9a-fA-F]{7,40}")) {
            out.put("ok", false);
            out.put("message", "invalid_commit");
            return out;
        }
        List<String> checkout = runGit("checkout", commitSha, "--", "data");
        if (hotReload != null) {
            hotReload.reloadAll();
        }
        if (grayRollback != null) {
            grayRollback.snapshotStable();
            grayRollback.recordRequest(true);
            grayRollback.evaluate();
        }
        out.put("ok", true);
        out.put("commitSha", commitSha);
        out.put("checkout", checkout);
        out.put("hint", "已回退 data/ 并触发 ConfigGrayAutoRollback 评估");
        return out;
    }

    private List<String> runGit(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        List<String> lines = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            }
            p.waitFor(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            lines.add("git_error:" + e.getMessage());
        }
        return lines;
    }
}
