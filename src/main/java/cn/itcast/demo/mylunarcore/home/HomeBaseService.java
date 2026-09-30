package cn.itcast.demo.mylunarcore.home;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园玩法：基建升级、产出循环、家具摆放、好友互访。
 */
@Service
public class HomeBaseService {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FacilityConfig(int facilityId, String name, int maxLevel, int staminaPerHour, int slotCount,
                                 int produceItemId, int producePerHour) {
        public FacilityConfig {
            name = name == null ? "" : name;
        }
    }

    public record FurnitureItem(int furnitureId, float x, float y, float z, int rotateY) {}

    public record HomeState(int playerId, int stamina, int staminaCap, Map<Integer, Integer> facilities,
                            Map<Integer, Integer> stationedAvatars, Map<Integer, Integer> pendingProduce,
                            List<FurnitureItem> furniture, long lastRecoverAtMs, long lastProduceAtMs) {
        public HomeState {
            facilities = facilities == null ? Map.of() : Map.copyOf(facilities);
            stationedAvatars = stationedAvatars == null ? Map.of() : Map.copyOf(stationedAvatars);
            pendingProduce = pendingProduce == null ? Map.of() : Map.copyOf(pendingProduce);
            furniture = furniture == null ? List.of() : List.copyOf(furniture);
        }
    }

    public record OpResult(boolean success, int retcode, HomeState state) {}

    public record VisitResult(boolean success, int retcode, HomeState hostState) {}

    private static final int DEFAULT_STAMINA_CAP = 120;
    private static final int DEFAULT_STAMINA = 100;
    private static final int MAX_FURNITURE = 64;

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final JdbcTemplate jdbc;
    private final Map<Integer, FacilityConfig> catalog = new ConcurrentHashMap<>();
    private final Map<Integer, HomeState> homes = new ConcurrentHashMap<>();

    public HomeBaseService(ObjectMapper objectMapper,
                           @Value("${lunarcore.data-dir:data}") String dataDir) {
        this(objectMapper, null, dataDir);
    }

    public HomeBaseService(ObjectMapper objectMapper,
                           JdbcTemplate jdbc,
                           @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reloadCatalog();
    }

    public boolean reloadCatalog() {
        Path file = dataDir.resolve("HomeFacilityConfigs.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<FacilityConfig> list = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            catalog.clear();
            for (FacilityConfig c : list) {
                if (c != null && c.facilityId() > 0) {
                    catalog.put(c.facilityId(), c);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public HomeState getOrCreate(int playerId) {
        return homes.computeIfAbsent(playerId, this::loadOrDefault);
    }

    /** 放置/升级基建。 */
    public OpResult placeFacility(int playerId, int facilityId, int level) {
        FacilityConfig cfg = catalog.get(facilityId);
        if (cfg == null) {
            return new OpResult(false, 1, getOrCreate(playerId));
        }
        int lv = Math.max(1, Math.min(level, cfg.maxLevel()));
        HomeState cur = tickProduction(recoverStamina(playerId));
        Map<Integer, Integer> fac = new LinkedHashMap<>(cur.facilities());
        fac.put(facilityId, lv);
        HomeState next = withFacilities(cur, fac);
        persist(next);
        return new OpResult(true, 0, next);
    }

    /** 角色入驻互动槽。 */
    public OpResult stationAvatar(int playerId, int facilityId, int avatarId) {
        FacilityConfig cfg = catalog.get(facilityId);
        HomeState cur = tickProduction(recoverStamina(playerId));
        if (cfg == null || !cur.facilities().containsKey(facilityId)) {
            return new OpResult(false, 2, cur);
        }
        Map<Integer, Integer> stationed = new LinkedHashMap<>(cur.stationedAvatars());
        long occupied = stationed.values().stream().filter(a -> a != null && a > 0).count();
        if (occupied >= cfg.slotCount() && !stationed.containsKey(facilityId)) {
            return new OpResult(false, 3, cur);
        }
        stationed.put(facilityId, avatarId);
        HomeState next = new HomeState(playerId, cur.stamina(), cur.staminaCap(), cur.facilities(),
                stationed, cur.pendingProduce(), cur.furniture(), cur.lastRecoverAtMs(), cur.lastProduceAtMs());
        persist(next);
        return new OpResult(true, 0, next);
    }

    /** 摆放家具（装饰）。 */
    public OpResult placeFurniture(int playerId, int furnitureId, float x, float y, float z, int rotateY) {
        if (furnitureId <= 0) {
            return new OpResult(false, 1, getOrCreate(playerId));
        }
        HomeState cur = getOrCreate(playerId);
        if (cur.furniture().size() >= MAX_FURNITURE) {
            return new OpResult(false, 4, cur);
        }
        List<FurnitureItem> list = new java.util.ArrayList<>(cur.furniture());
        list.add(new FurnitureItem(furnitureId, x, y, z, rotateY));
        HomeState next = new HomeState(playerId, cur.stamina(), cur.staminaCap(), cur.facilities(),
                cur.stationedAvatars(), cur.pendingProduce(), list, cur.lastRecoverAtMs(), cur.lastProduceAtMs());
        persist(next);
        return new OpResult(true, 0, next);
    }

    /** 领取待产出资源到 pending→调用方背包（此处仅清空并返回数量）。 */
    public OpResult claimProduction(int playerId) {
        HomeState cur = tickProduction(recoverStamina(playerId));
        if (cur.pendingProduce().isEmpty()) {
            return new OpResult(false, 5, cur);
        }
        HomeState next = new HomeState(playerId, cur.stamina(), cur.staminaCap(), cur.facilities(),
                cur.stationedAvatars(), Map.of(), cur.furniture(), cur.lastRecoverAtMs(), cur.lastProduceAtMs());
        persist(next);
        return new OpResult(true, 0, cur);
    }

    /** 好友互访：记录访问并返回主人家园只读快照。 */
    public VisitResult visit(int visitorId, int hostPlayerId) {
        if (visitorId <= 0 || hostPlayerId <= 0 || visitorId == hostPlayerId) {
            return new VisitResult(false, 2, null);
        }
        HomeState host = tickProduction(recoverStamina(hostPlayerId));
        try {
            jdbc.update("""
                    INSERT INTO home_visit_log (host_player_id, visitor_id) VALUES (?, ?)
                    """, hostPlayerId, visitorId);
        } catch (Exception ignored) {
            // 表未就绪时仍允许互访
        }
        return new VisitResult(true, 0, host);
    }

    public record AssistResult(boolean success, int retcode, int reducedCdMs, boolean claimedOverflow,
                               int overflowItemId, int overflowCount) {
        static AssistResult fail(int retcode) {
            return new AssistResult(false, retcode, 0, false, 0, 0);
        }
    }

    private final Map<String, Long> assistCooldown = new ConcurrentHashMap<>();
    private static final int OVERFLOW_THRESHOLD = 20;
    private static final long ASSIST_REDUCE_MS = 180_000L; // 1 小时 CD 的 5%

    /** 好友助产：减少剩余产出 CD 的 5%，每日每设施一次。 */
    public AssistResult harvestAssist(int visitorId, int hostPlayerId, int facilityId) {
        if (visitorId <= 0 || hostPlayerId <= 0 || visitorId == hostPlayerId || facilityId <= 0) {
            return AssistResult.fail(2);
        }
        HomeState host = tickProduction(recoverStamina(hostPlayerId));
        if (!host.facilities().containsKey(facilityId)) {
            return AssistResult.fail(4);
        }
        String key = visitorId + ":" + hostPlayerId + ":" + facilityId;
        long now = System.currentTimeMillis();
        Long last = assistCooldown.get(key);
        if (last != null && now - last < 86_400_000L) {
            return AssistResult.fail(5);
        }
        assistCooldown.put(key, now);
        HomeState next = new HomeState(host.playerId(), host.stamina(), host.staminaCap(), host.facilities(),
                host.stationedAvatars(), host.pendingProduce(), host.furniture(),
                host.lastRecoverAtMs(), Math.max(0L, host.lastProduceAtMs() - ASSIST_REDUCE_MS));
        persist(next);
        int overflowItem = 0;
        int overflowCount = 0;
        for (Map.Entry<Integer, Integer> e : next.pendingProduce().entrySet()) {
            if (e.getValue() != null && e.getValue() >= OVERFLOW_THRESHOLD) {
                overflowItem = e.getKey();
                overflowCount = e.getValue();
                break;
            }
        }
        return new AssistResult(true, 0, (int) ASSIST_REDUCE_MS, overflowCount > 0, overflowItem, overflowCount);
    }

    public record OverflowHint(int playerId, int itemId, int count) {}

    public OverflowHint overflowIfAny(int playerId) {
        HomeState state = tickProduction(recoverStamina(playerId));
        for (Map.Entry<Integer, Integer> e : state.pendingProduce().entrySet()) {
            if (e.getValue() != null && e.getValue() >= OVERFLOW_THRESHOLD) {
                return new OverflowHint(playerId, e.getKey(), e.getValue());
            }
        }
        return null;
    }

    public HomeState recoverStamina(int playerId) {
        HomeState cur = getOrCreate(playerId);
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0L, now - cur.lastRecoverAtMs());
        if (elapsedMs < 60_000L || cur.stamina() >= cur.staminaCap()) {
            return cur;
        }
        int perHour = cur.facilities().entrySet().stream()
                .mapToInt(e -> {
                    FacilityConfig c = catalog.get(e.getKey());
                    return c == null ? 0 : c.staminaPerHour() * Math.max(1, e.getValue());
                })
                .sum();
        if (perHour <= 0) {
            perHour = 10;
        }
        int gain = (int) Math.min(cur.staminaCap() - cur.stamina(),
                (elapsedMs / 3_600_000.0) * perHour);
        if (gain <= 0) {
            return cur;
        }
        HomeState next = new HomeState(playerId, cur.stamina() + gain, cur.staminaCap(),
                cur.facilities(), cur.stationedAvatars(), cur.pendingProduce(), cur.furniture(), now, cur.lastProduceAtMs());
        persist(next);
        return next;
    }

    public HomeState tickProduction(HomeState cur) {
        long now = System.currentTimeMillis();
        long elapsedMs = Math.max(0L, now - cur.lastProduceAtMs());
        if (elapsedMs < 60_000L || cur.facilities().isEmpty()) {
            return cur;
        }
        Map<Integer, Integer> pending = new LinkedHashMap<>(cur.pendingProduce());
        for (Map.Entry<Integer, Integer> e : cur.facilities().entrySet()) {
            FacilityConfig c = catalog.get(e.getKey());
            if (c == null || c.produceItemId() <= 0 || c.producePerHour() <= 0) {
                continue;
            }
            int gain = (int) ((elapsedMs / 3_600_000.0) * c.producePerHour() * Math.max(1, e.getValue()));
            if (gain > 0) {
                pending.merge(c.produceItemId(), gain, Integer::sum);
            }
        }
        if (pending.equals(cur.pendingProduce())) {
            return cur;
        }
        HomeState next = new HomeState(cur.playerId(), cur.stamina(), cur.staminaCap(), cur.facilities(),
                cur.stationedAvatars(), pending, cur.furniture(), cur.lastRecoverAtMs(), now);
        persist(next);
        return next;
    }

    public Map<Integer, FacilityConfig> catalogSnapshot() {
        return Map.copyOf(catalog);
    }

    private HomeState withFacilities(HomeState cur, Map<Integer, Integer> fac) {
        return new HomeState(cur.playerId(), cur.stamina(), cur.staminaCap(), fac,
                cur.stationedAvatars(), cur.pendingProduce(), cur.furniture(),
                cur.lastRecoverAtMs(), cur.lastProduceAtMs());
    }

    private HomeState loadOrDefault(int playerId) {
        if (jdbc == null) {
            return defaultState(playerId);
        }
        try {
            HomeState loaded = jdbc.query("""
                    SELECT stamina, stamina_cap, facilities_json, stationed_json, furniture_json,
                           production_json, last_recover_at_ms, last_produce_at_ms
                    FROM home_state WHERE player_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new HomeState(
                        playerId,
                        rs.getInt(1),
                        rs.getInt(2),
                        readIntMap(rs.getString(3)),
                        readIntMap(rs.getString(4)),
                        readIntMap(rs.getString(6)),
                        readFurniture(rs.getString(5)),
                        rs.getLong(7),
                        rs.getLong(8));
            }, playerId);
            return loaded == null ? defaultState(playerId) : loaded;
        } catch (Exception e) {
            return defaultState(playerId);
        }
    }

    private HomeState defaultState(int playerId) {
        long now = System.currentTimeMillis();
        return new HomeState(playerId, DEFAULT_STAMINA, DEFAULT_STAMINA_CAP,
                Map.of(), Map.of(), Map.of(), List.of(), now, now);
    }

    private void persist(HomeState state) {
        homes.put(state.playerId(), state);
        if (jdbc == null) {
            return;
        }
        try {
            String fac = objectMapper.writeValueAsString(state.facilities());
            String stationed = objectMapper.writeValueAsString(state.stationedAvatars());
            String furniture = objectMapper.writeValueAsString(state.furniture());
            String produce = objectMapper.writeValueAsString(state.pendingProduce());
            jdbc.update("""
                    INSERT INTO home_state (player_id, stamina, stamina_cap, facilities_json, stationed_json,
                        furniture_json, production_json, last_recover_at_ms, last_produce_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      stamina=VALUES(stamina), stamina_cap=VALUES(stamina_cap),
                      facilities_json=VALUES(facilities_json), stationed_json=VALUES(stationed_json),
                      furniture_json=VALUES(furniture_json), production_json=VALUES(production_json),
                      last_recover_at_ms=VALUES(last_recover_at_ms), last_produce_at_ms=VALUES(last_produce_at_ms)
                    """, state.playerId(), state.stamina(), state.staminaCap(), fac, stationed, furniture, produce,
                    state.lastRecoverAtMs(), state.lastProduceAtMs());
        } catch (Exception ignored) {
            // DB 未迁移时仅内存
        }
    }

    private Map<Integer, Integer> readIntMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Integer> raw = objectMapper.readValue(json, new TypeReference<>() {});
            Map<Integer, Integer> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(Integer.parseInt(k), v));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<FurnitureItem> readFurniture(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
