package cn.itcast.demo.mylunarcore.character;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 角色命座/星魂配置：data/CharacterConstellationConfig.json。
 */
@Repository
public class CharacterConstellationConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(CharacterConstellationConfigRepository.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Modifier(String stat, double value, int skillSlot, int targetSkillId) {
        public Modifier {
            stat = stat == null ? "" : stat;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Layer(int layer, String desc, String effectType, List<Modifier> modifiers) {
        public Layer {
            desc = desc == null ? "" : desc;
            effectType = effectType == null ? "STAT_BOOST" : effectType;
            modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CharacterCfg(int maxLayer, List<Layer> layers) {
        public CharacterCfg {
            maxLayer = maxLayer <= 0 ? 6 : maxLayer;
            layers = layers == null ? List.of() : List.copyOf(layers);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<Integer, CharacterCfg> byCharacterId = new ConcurrentHashMap<>();

    public CharacterConstellationConfigRepository(ObjectMapper objectMapper,
                                                  @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("CharacterConstellationConfig.json");
        if (!Files.isRegularFile(file)) {
            log.warn("CharacterConstellationConfig.json missing");
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            JsonNode chars = root.path("characters");
            byCharacterId.clear();
            if (chars.isObject()) {
                chars.fields().forEachRemaining(e -> {
                    try {
                        int id = Integer.parseInt(e.getKey());
                        CharacterCfg cfg = objectMapper.treeToValue(e.getValue(), CharacterCfg.class);
                        byCharacterId.put(id, cfg);
                    } catch (Exception ignored) {
                        // skip
                    }
                });
            }
            log.info("CharacterConstellationConfig loaded characters={}", byCharacterId.size());
            return true;
        } catch (Exception e) {
            log.warn("load CharacterConstellationConfig failed: {}", e.toString());
            return false;
        }
    }

    public CharacterCfg find(int characterId) {
        return byCharacterId.get(characterId);
    }

    public List<Layer> unlockedLayers(int characterId, int currentLayer) {
        CharacterCfg cfg = find(characterId);
        if (cfg == null) {
            return List.of();
        }
        List<Layer> out = new ArrayList<>();
        for (Layer layer : cfg.layers()) {
            if (layer.layer() > 0 && layer.layer() <= currentLayer) {
                out.add(layer);
            }
        }
        return List.copyOf(out);
    }

    public int maxLayer(int characterId) {
        CharacterCfg cfg = find(characterId);
        return cfg == null ? 6 : cfg.maxLayer();
    }

    public Map<Integer, CharacterCfg> snapshot() {
        return Collections.unmodifiableMap(byCharacterId);
    }
}
