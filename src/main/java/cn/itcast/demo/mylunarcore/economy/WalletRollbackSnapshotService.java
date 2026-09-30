package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钱包版本回滚基准点：大版本更新前打存盘点，刷钱事故时可回退到该节点货币状态。
 */
@Service
public class WalletRollbackSnapshotService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SYNC, WalletRollbackSnapshotService.class);

    private final JdbcTemplate jdbc;
    private final WalletApplicationService walletApplicationService;
    private final WalletWalService walletWalService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path snapshotDir;
    private final ConcurrentHashMap<String, Path> index = new ConcurrentHashMap<>();

    public WalletRollbackSnapshotService(JdbcTemplate jdbc,
                                         WalletApplicationService walletApplicationService,
                                         WalletWalService walletWalService,
                                         LunarCoreProperties properties) {
        this.jdbc = jdbc;
        this.walletApplicationService = walletApplicationService;
        this.walletWalService = walletWalService;
        this.snapshotDir = Path.of(properties.getDataDir(), "wallet-snapshots");
    }

    /**
     * 打基准点：先排空 WAL，再导出全服货币快照到 data/wallet-snapshots/{label}.json。
     */
    public Map<String, Object> createSnapshot(String label) {
        String name = (label == null || label.isBlank()) ? ("v-" + Instant.now().toEpochMilli()) : label.trim();
        try {
            Files.createDirectories(snapshotDir);
            while (walletWalService.flushOnce() > 0) {
                // drain
            }
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT uid, currency FROM player",
                    (rs, i) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("playerId", rs.getInt("uid"));
                        row.put("currencyJson", rs.getString("currency"));
                        return row;
                    });
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("label", name);
            payload.put("createdAt", Instant.now().toString());
            payload.put("playerCount", rows.size());
            payload.put("rows", rows);
            Path file = snapshotDir.resolve(name + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
            index.put(name, file);
            log.warn("wallet rollback snapshot created label={} players={} file={}", name, rows.size(), file);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("label", name);
            out.put("playerCount", rows.size());
            out.put("file", file.toString());
            return out;
        } catch (Exception e) {
            // 兼容无 player_wallet 表或列名差异：改为按已知在线缓存导出
            log.warn("full wallet snapshot via SQL failed, fallback empty: {}", e.toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("message", e.getMessage());
            out.put("hint", "ensure player.currency column exists or use per-uid snapshot");
            return out;
        }
    }

    /** 单玩家快照（运维定点）。 */
    public Map<String, Object> snapshotPlayer(int playerId, String label) {
        String name = (label == null || label.isBlank())
                ? ("p" + playerId + "-" + Instant.now().toEpochMilli())
                : label.trim();
        try {
            Files.createDirectories(snapshotDir);
            walletWalService.flushOnce();
            Map<Integer, Integer> bal = walletApplicationService.getBalance(playerId);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("label", name);
            payload.put("playerId", playerId);
            payload.put("createdAt", Instant.now().toString());
            payload.put("balance", bal);
            Path file = snapshotDir.resolve(name + ".json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
            index.put(name, file);
            return Map.of("ok", true, "label", name, "balance", bal, "file", file.toString());
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    /**
     * 回退到基准点：按快照覆盖各玩家货币（需配套公告补偿）。
     * dryRun=true 只报告差异不落库。
     */
    public Map<String, Object> rollbackTo(String label, boolean dryRun) {
        Path file = index.get(label);
        if (file == null) {
            file = snapshotDir.resolve(label + ".json");
        }
        if (!Files.isRegularFile(file)) {
            return Map.of("ok", false, "message", "snapshot_not_found");
        }
        try {
            while (walletWalService.flushOnce() > 0) {
            }
            Map<String, Object> payload = mapper.readValue(Files.readString(file), new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.getOrDefault("rows", List.of());
            int restored = 0;
            int skipped = 0;
            List<String> samples = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                int playerId = ((Number) row.get("playerId")).intValue();
                String currencyJson = String.valueOf(row.getOrDefault("currencyJson", "{}"));
                Map<Integer, Integer> target = parseCurrency(currencyJson);
                Map<Integer, Integer> current = walletApplicationService.getBalance(playerId);
                if (current.equals(target)) {
                    skipped++;
                    continue;
                }
                if (samples.size() < 5) {
                    samples.add("uid=" + playerId + " from=" + current + " to=" + target);
                }
                if (!dryRun) {
                    applyAbsolute(playerId, current, target);
                    restored++;
                } else {
                    restored++;
                }
            }
            // 单玩家快照格式
            if (rows.isEmpty() && payload.containsKey("playerId")) {
                int playerId = ((Number) payload.get("playerId")).intValue();
                @SuppressWarnings("unchecked")
                Map<String, Object> balMap = (Map<String, Object>) payload.getOrDefault("balance", Map.of());
                Map<Integer, Integer> target = new HashMap<>();
                balMap.forEach((k, v) -> target.put(Integer.parseInt(k), ((Number) v).intValue()));
                Map<Integer, Integer> current = walletApplicationService.getBalance(playerId);
                if (!dryRun) {
                    applyAbsolute(playerId, current, target);
                }
                restored = 1;
            }
            log.error("wallet rollback label={} dryRun={} restored={} skipped={}", label, dryRun, restored, skipped);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("label", label);
            out.put("dryRun", dryRun);
            out.put("restored", restored);
            out.put("skipped", skipped);
            out.put("samples", samples);
            return out;
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage() == null ? "error" : e.getMessage());
        }
    }

    public Map<String, Object> listSnapshots() {
        List<String> names = new ArrayList<>();
        try {
            if (Files.isDirectory(snapshotDir)) {
                try (var stream = Files.list(snapshotDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .forEach(p -> names.add(p.getFileName().toString().replace(".json", "")));
                }
            }
        } catch (Exception ignored) {
        }
        return Map.of("snapshots", names, "dir", snapshotDir.toString());
    }

    private void applyAbsolute(int playerId, Map<Integer, Integer> current, Map<Integer, Integer> target) {
        for (Map.Entry<Integer, Integer> e : target.entrySet()) {
            int currencyId = e.getKey();
            int want = e.getValue();
            int have = current.getOrDefault(currencyId, 0);
            int delta = want - have;
            if (delta > 0) {
                walletApplicationService.add(playerId, currencyId, delta, "rollback_restore");
            } else if (delta < 0) {
                walletApplicationService.forceDeductAllowNegative(playerId, currencyId, -delta, "rollback_restore");
            }
        }
        for (Integer currencyId : current.keySet()) {
            if (!target.containsKey(currencyId)) {
                int have = current.getOrDefault(currencyId, 0);
                if (have != 0) {
                    walletApplicationService.forceDeductAllowNegative(playerId, currencyId, have, "rollback_clear");
                }
            }
        }
    }

    private Map<Integer, Integer> parseCurrency(String json) {
        try {
            Map<String, Object> raw = mapper.readValue(json == null ? "{}" : json, new TypeReference<>() {});
            Map<Integer, Integer> out = new HashMap<>();
            raw.forEach((k, v) -> out.put(Integer.parseInt(k), ((Number) v).intValue()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
