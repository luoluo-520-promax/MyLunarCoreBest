package cn.itcast.demo.mylunarcore.equipment;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 光锥/遗器词条：随机属性池、强化时副词条成长、分解返还、套装加成汇总。
 * 与 {@link GameItemEntity#mainAffixId}/{@code subAffixesJson} 字段对齐。
 */
@Service
public class EquipmentAffixService {

    public record SubAffix(int id, String stat, double value) {}

    public record RollResult(int mainAffixId, String subAffixesJson, EquipmentBonus bonusPreview) {}

    public record DecomposeResult(int returnExp, int returnCurrency, String currencyReason) {}

    private final ObjectMapper objectMapper;
    private final List<JsonNode> mainPool = new ArrayList<>();
    private final List<JsonNode> subPool = new ArrayList<>();
    private final List<JsonNode> setConfigs = new ArrayList<>();
    private int initialSubCount = 3;
    private int maxSubCount = 4;
    private int enhanceLevelsPerSubUpgrade = 3;
    private double decomposeReturnRatio = 0.4;

    public EquipmentAffixService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void load() {
        loadAffixPool();
        loadSetConfigs();
    }

    private void loadAffixPool() {
        try {
            JsonNode root = readJson("data/EquipmentAffixPool.json");
            if (root == null) {
                return;
            }
            mainPool.clear();
            subPool.clear();
            root.path("mainAffixes").forEach(mainPool::add);
            root.path("subAffixes").forEach(subPool::add);
            JsonNode rules = root.path("rollRules");
            initialSubCount = rules.path("initialSubCount").asInt(3);
            maxSubCount = rules.path("maxSubCount").asInt(4);
            enhanceLevelsPerSubUpgrade = rules.path("enhanceLevelsPerSubUpgrade").asInt(3);
            decomposeReturnRatio = rules.path("decomposeReturnRatio").asDouble(0.4);
        } catch (Exception ignored) {
            // 配置缺失时使用空池，roll 返回零加成
        }
    }

    private void loadSetConfigs() {
        try {
            JsonNode root = readJson("data/RelicSetConfigs.json");
            setConfigs.clear();
            if (root != null && root.isArray()) {
                root.forEach(setConfigs::add);
            }
        } catch (Exception ignored) {
        }
    }

    /** 为新遗器/光锥抽取主词条 + 初始副词条。 */
    public RollResult rollNew(String slot, int itemId) {
        Random rng = ThreadLocalRandom.current();
        JsonNode main = pickMain(slot, rng);
        int mainId = main == null ? 0 : main.path("id").asInt();
        List<SubAffix> subs = new ArrayList<>();
        List<JsonNode> shuffled = new ArrayList<>(subPool);
        java.util.Collections.shuffle(shuffled, rng);
        int count = Math.min(initialSubCount, shuffled.size());
        for (int i = 0; i < count; i++) {
            JsonNode s = shuffled.get(i);
            double v = s.path("min").asDouble()
                    + rng.nextDouble() * (s.path("max").asDouble() - s.path("min").asDouble());
            subs.add(new SubAffix(s.path("id").asInt(), s.path("stat").asText(), round1(v)));
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(subs);
        } catch (Exception e) {
            json = "[]";
        }
        EquipmentBonus bonus = toBonus(main, subs).plus(setBonusForItem(itemId, 1));
        return new RollResult(mainId, json, bonus);
    }

    /** 强化跨阈值时升级一条副词条（或追加到 max）。 */
    public String maybeUpgradeSubOnEnhance(GameItemEntity item, int oldLevel, int newLevel) {
        if (item == null || subPool.isEmpty()) {
            return item == null ? "[]" : nullToEmpty(item.getSubAffixesJson());
        }
        int oldTier = oldLevel / Math.max(1, enhanceLevelsPerSubUpgrade);
        int newTier = newLevel / Math.max(1, enhanceLevelsPerSubUpgrade);
        if (newTier <= oldTier) {
            return nullToEmpty(item.getSubAffixesJson());
        }
        List<SubAffix> subs = parseSubs(item.getSubAffixesJson());
        Random rng = ThreadLocalRandom.current();
        if (subs.size() < maxSubCount) {
            JsonNode s = subPool.get(rng.nextInt(subPool.size()));
            double v = s.path("min").asDouble()
                    + rng.nextDouble() * (s.path("max").asDouble() - s.path("min").asDouble());
            subs.add(new SubAffix(s.path("id").asInt(), s.path("stat").asText(), round1(v)));
        } else if (!subs.isEmpty()) {
            int idx = rng.nextInt(subs.size());
            SubAffix cur = subs.get(idx);
            subs.set(idx, new SubAffix(cur.id(), cur.stat(), round1(cur.value() * 1.15)));
        }
        try {
            return objectMapper.writeValueAsString(subs);
        } catch (Exception e) {
            return nullToEmpty(item.getSubAffixesJson());
        }
    }

    public DecomposeResult decompose(GameItemEntity item) {
        if (item == null) {
            return new DecomposeResult(0, 0, "decompose");
        }
        int invested = (int) Math.max(0, item.getExp()) + item.getLevel() * 100;
        int back = (int) Math.floor(invested * decomposeReturnRatio);
        return new DecomposeResult(back, Math.max(10, back / 10), "equipment_decompose");
    }

    /** 汇总角色已装备光锥+遗器的面板加成（含套装）。 */
    public EquipmentBonus sumEquippedBonus(List<GameItemEntity> equipped) {
        EquipmentBonus total = EquipmentBonus.zero();
        if (equipped == null || equipped.isEmpty()) {
            return total;
        }
        Map<Integer, Integer> setCounts = new HashMap<>();
        for (GameItemEntity item : equipped) {
            if (item == null || item.isDiscarded()) {
                continue;
            }
            JsonNode main = findMain(item.getMainAffixId() == null ? 0 : item.getMainAffixId());
            List<SubAffix> subs = parseSubs(item.getSubAffixesJson());
            total = total.plus(toBonus(main, subs));
            // itemId 高三位约定为 setId（与 RelicSetConfigs 对齐的简化规则）
            int setId = item.getItemId() / 1000;
            if (item.getType() == 2 && setId > 0) {
                setCounts.merge(setId, 1, Integer::sum);
            }
            // 光锥等级线性 ATK
            if (item.getType() == 1) {
                total = total.plus(new EquipmentBonus(0, 20 + item.getLevel() * 4 + item.getRank() * 15,
                        0, 0, 0, 8.0 + item.getRank() * 2, 0, 0, 0));
            }
        }
        for (Map.Entry<Integer, Integer> e : setCounts.entrySet()) {
            total = total.plus(setBonus(e.getKey(), e.getValue()));
        }
        return total;
    }

    private EquipmentBonus setBonusForItem(int itemId, int pieces) {
        return setBonus(itemId / 1000, pieces);
    }

    private EquipmentBonus setBonus(int setId, int pieces) {
        EquipmentBonus b = EquipmentBonus.zero();
        for (JsonNode cfg : setConfigs) {
            if (cfg.path("setId").asInt() != setId) {
                continue;
            }
            for (JsonNode bonus : cfg.path("bonuses")) {
                if (pieces >= bonus.path("need").asInt()) {
                    b = b.plus(statToBonus(bonus.path("stat").asText(), bonus.path("value").asDouble()));
                }
            }
        }
        return b;
    }

    private EquipmentBonus toBonus(JsonNode main, List<SubAffix> subs) {
        EquipmentBonus b = EquipmentBonus.zero();
        if (main != null) {
            b = b.plus(statToBonus(main.path("stat").asText(), main.path("baseValue").asDouble()));
        }
        for (SubAffix s : subs) {
            b = b.plus(statToBonus(s.stat(), s.value()));
        }
        return b;
    }

    private EquipmentBonus statToBonus(String stat, double value) {
        if (stat == null) {
            return EquipmentBonus.zero();
        }
        return switch (stat) {
            case "HP_FLAT" -> new EquipmentBonus((int) value, 0, 0, 0, 0, 0, 0, 0, 0);
            case "ATK_FLAT" -> new EquipmentBonus(0, (int) value, 0, 0, 0, 0, 0, 0, 0);
            case "DEF_FLAT" -> new EquipmentBonus(0, 0, (int) value, 0, 0, 0, 0, 0, 0);
            case "SPD" -> new EquipmentBonus(0, 0, 0, (int) value, 0, 0, 0, 0, 0);
            case "HP_PCT" -> new EquipmentBonus(0, 0, 0, 0, value, 0, 0, 0, 0);
            case "ATK_PCT" -> new EquipmentBonus(0, 0, 0, 0, 0, value, 0, 0, 0);
            case "DEF_PCT" -> new EquipmentBonus(0, 0, 0, 0, 0, 0, value, 0, 0);
            case "CRIT_RATE" -> new EquipmentBonus(0, 0, 0, 0, 0, 0, 0, value, 0);
            case "CRIT_DMG" -> new EquipmentBonus(0, 0, 0, 0, 0, 0, 0, 0, value);
            default -> EquipmentBonus.zero();
        };
    }

    private JsonNode pickMain(String slot, Random rng) {
        List<JsonNode> candidates = new ArrayList<>();
        String slotKey = slot == null ? "BODY" : slot;
        for (JsonNode m : mainPool) {
            boolean ok = false;
            for (JsonNode s : m.path("slots")) {
                if (slotKey.equalsIgnoreCase(s.asText())) {
                    ok = true;
                    break;
                }
            }
            if (ok || m.path("slots").isEmpty()) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return mainPool.isEmpty() ? null : mainPool.get(rng.nextInt(mainPool.size()));
        }
        return candidates.get(rng.nextInt(candidates.size()));
    }

    private JsonNode findMain(int id) {
        for (JsonNode m : mainPool) {
            if (m.path("id").asInt() == id) {
                return m;
            }
        }
        return null;
    }

    private List<SubAffix> parseSubs(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SubAffix>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private JsonNode readJson(String path) throws Exception {
        Path p = Path.of(path);
        if (Files.exists(p)) {
            try (InputStream in = Files.newInputStream(p)) {
                return objectMapper.readTree(in);
            }
        }
        ClassPathResource cpr = new ClassPathResource(path.startsWith("data/") ? path.substring(5) : path);
        if (cpr.exists()) {
            try (InputStream in = cpr.getInputStream()) {
                return objectMapper.readTree(in);
            }
        }
        // 工作目录相对 data/
        Path alt = Path.of("data", path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path);
        if (Files.exists(alt)) {
            try (InputStream in = Files.newInputStream(alt)) {
                return objectMapper.readTree(in);
            }
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null || s.isBlank() ? "[]" : s;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
