package cn.itcast.demo.mylunarcore.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * 解析 {@code --key=value} / {@code --key value} 形式的命令行参数。
 */
final class FlowDemoArgs {

    private final Map<String, String> values = new HashMap<>();

    FlowDemoArgs(String[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if ("--help".equals(arg) || "-h".equals(arg)) {
                values.put("help", "true");
                continue;
            }
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                if (eq >= 0) {
                    values.put(body.substring(0, eq), body.substring(eq + 1));
                } else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                    values.put(body, args[++i]);
                } else {
                    values.put(body, "true");
                }
            }
        }
    }

    boolean helpRequested() {
        return "true".equalsIgnoreCase(values.get("help"));
    }

    String flow() {
        return values.getOrDefault("flow", "");
    }

    String get(String key, String defaultValue) {
        return values.getOrDefault(key, defaultValue);
    }

    long getLong(String key, long defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    int getInt(String key, int defaultValue) {
        String raw = values.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
