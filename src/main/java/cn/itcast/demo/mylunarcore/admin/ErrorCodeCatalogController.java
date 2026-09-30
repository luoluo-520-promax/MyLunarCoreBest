package cn.itcast.demo.mylunarcore.admin;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 {@code docs/error-codes.md} 解析为 JSON，供 Admin 后台与联调查询（轻量 OpenAPI 替代）。
 */
@RestController
@RequestMapping("/api/admin/error-codes")
public class ErrorCodeCatalogController {

    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*(\\d+)\\s*\\|\\s*([^|]+)\\s*\\|?(.*)$");

    @GetMapping
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write') or isAuthenticated()")
    public Map<String, Object> catalog() {
        String md = readDoc();
        List<Map<String, Object>> codes = new ArrayList<>();
        String section = "general";
        if (md != null) {
            for (String line : md.split("\n")) {
                if (line.startsWith("## ")) {
                    section = line.substring(3).trim();
                    continue;
                }
                Matcher m = ROW.matcher(line.trim());
                if (!m.matches()) {
                    continue;
                }
                try {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("retcode", Integer.parseInt(m.group(1)));
                    row.put("meaning", m.group(2).trim());
                    row.put("hint", m.group(3) == null ? "" : m.group(3).replace("|", "").trim());
                    row.put("section", section);
                    codes.add(row);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("openapi", Map.of(
                "openapi", "3.0.3",
                "info", Map.of("title", "MyLunarCore Error Codes", "version", "1.0"),
                "paths", Map.of("/api/admin/error-codes", Map.of("get", Map.of(
                        "summary", "List business retcodes",
                        "responses", Map.of("200", Map.of("description", "OK")))))));
        out.put("codes", codes);
        out.put("count", codes.size());
        return out;
    }

    private String readDoc() {
        try {
            Path p = Path.of("docs/error-codes.md");
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
            ClassPathResource cpr = new ClassPathResource("docs/error-codes.md");
            if (cpr.exists()) {
                return new String(cpr.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
