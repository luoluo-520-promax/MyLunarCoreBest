package cn.itcast.demo.mylunarcore.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 活动类型目录：配置化登记可生成的活动玩法模板（签到/限时挑战/爬塔/联机等）。
 */
@Component
public class ActivityTypeCatalog {

    public record TypeDef(String type, String displayName, String description,
                          List<String> requiredFields, String handlerHint, Integer exampleActivityId) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private volatile Map<String, TypeDef> byType = Map.of();

    public ActivityTypeCatalog(ObjectMapper objectMapper,
                               @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public synchronized void reload() {
        Path file = dataDir.resolve("ActivityTypeCatalog.json");
        if (!Files.isRegularFile(file)) {
            byType = Map.of();
            return;
        }
        try {
            List<TypeDef> list = objectMapper.readValue(Files.readString(file), new TypeReference<>() {});
            Map<String, TypeDef> map = new LinkedHashMap<>();
            for (TypeDef def : list) {
                if (def.type() != null && !def.type().isBlank()) {
                    map.put(def.type(), def);
                }
            }
            byType = Collections.unmodifiableMap(map);
        } catch (Exception e) {
            byType = Map.of();
        }
    }

    public Optional<TypeDef> find(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byType.get(type));
    }

    public Map<String, TypeDef> all() {
        return byType;
    }

    public boolean isKnown(String type) {
        return find(type).isPresent();
    }
}
