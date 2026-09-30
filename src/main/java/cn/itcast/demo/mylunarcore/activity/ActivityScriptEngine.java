package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.SimpleBindings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 活动独有计算逻辑的轻量脚本引擎（Groovy JSR-223）。
 * 脚本目录：{@code data/scripts/activity/*.groovy}，/reload 后重新编译生效。
 */
@Service
public class ActivityScriptEngine {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityScriptEngine.class);

    public record ScriptResult(boolean success, int retcode, Object value, String message) {
        public static ScriptResult fail(int code, String msg) {
            return new ScriptResult(false, code, null, msg == null ? "" : msg);
        }

        public static ScriptResult ok(Object value) {
            return new ScriptResult(true, 0, value, "ok");
        }
    }

    private final Path scriptsDir;
    private final ConcurrentHashMap<String, CompiledScript> compiled = new ConcurrentHashMap<>();
    private volatile ScriptEngine engine;

    public ActivityScriptEngine(LunarCoreProperties properties) {
        this.scriptsDir = Path.of(properties.getDataDir(), "scripts", "activity");
    }

    @PostConstruct
    public void init() {
        reloadAll();
    }

    public synchronized int reloadAll() {
        compiled.clear();
        ScriptEngineManager manager = new ScriptEngineManager();
        ScriptEngine groovy = manager.getEngineByName("groovy");
        if (groovy == null) {
            groovy = manager.getEngineByExtension("groovy");
        }
        this.engine = groovy;
        if (groovy == null) {
            log.warn("Groovy ScriptEngine unavailable; activity scripts disabled");
            return 0;
        }
        int loaded = 0;
        try {
            Files.createDirectories(scriptsDir);
            if (!Files.isDirectory(scriptsDir)) {
                return 0;
            }
            try (Stream<Path> stream = Files.list(scriptsDir)) {
                for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".groovy")).toList()) {
                    if (compileFile(file)) {
                        loaded++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("activity script reload failed: {}", e.toString());
        }
        log.info("Activity scripts loaded count={} dir={}", loaded, scriptsDir);
        return loaded;
    }

    public boolean hasScript(String scriptId) {
        return scriptId != null && !scriptId.isBlank() && compiled.containsKey(normalizeId(scriptId));
    }

    /**
     * 执行脚本入口函数 {@code compute(bindings)}，bindings 由调用方注入玩家/活动上下文。
     */
    public ScriptResult invoke(String scriptId, Map<String, Object> context) {
        String id = normalizeId(scriptId);
        CompiledScript script = compiled.get(id);
        if (script == null) {
            return ScriptResult.fail(404, "script_not_found:" + id);
        }
        try {
            Bindings bindings = new SimpleBindings();
            if (context != null) {
                bindings.putAll(context);
            }
            Object result = script.eval(bindings);
            if (result instanceof Map<?, ?> map && map.containsKey("retcode")) {
                Object code = map.get("retcode");
                int ret = code instanceof Number n ? n.intValue() : 0;
                if (ret != 0) {
                    Object msg = map.containsKey("message") ? map.get("message") : "script_error";
                    return ScriptResult.fail(ret, String.valueOf(msg));
                }
                Object value = map.containsKey("value") ? map.get("value") : map;
                return ScriptResult.ok(value);
            }
            return ScriptResult.ok(result);
        } catch (Exception e) {
            log.warn("activity script invoke failed id={}: {}", id, e.toString());
            return ScriptResult.fail(500, e.getMessage());
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", scriptsDir.toString());
        out.put("engineReady", engine != null);
        out.put("scripts", compiled.keySet().stream().sorted().toList());
        return out;
    }

    private boolean compileFile(Path file) {
        try {
            String name = file.getFileName().toString();
            String id = normalizeId(name.endsWith(".groovy") ? name.substring(0, name.length() - 7) : name);
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (!(engine instanceof Compilable compilable)) {
                log.warn("script engine not Compilable, skip {}", name);
                return false;
            }
            CompiledScript cs = compilable.compile(source);
            compiled.put(id, cs);
            return true;
        } catch (Exception e) {
            log.warn("compile activity script {} failed: {}", file, e.toString());
            return false;
        }
    }

    private static String normalizeId(String scriptId) {
        String s = scriptId == null ? "" : scriptId.trim();
        if (s.endsWith(".groovy")) {
            s = s.substring(0, s.length() - 7);
        }
        return s;
    }
}
