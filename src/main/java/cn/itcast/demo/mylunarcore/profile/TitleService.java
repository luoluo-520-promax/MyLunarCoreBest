package cn.itcast.demo.mylunarcore.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 称号：成就领取时解锁、装备到名片/社交资料，展示名同步 {@link PlayerCardService}。
 */
@Service
public class TitleService {

    private static final Logger log = LoggerFactory.getLogger(TitleService.class);

    public record TitleDef(String titleId, String displayName, String sourceAchievement) {}

    public record TitleView(String titleId, String displayName, String sourceAchievement,
                            boolean unlocked, boolean equipped) {}

    public record EquipResult(boolean ok, int retcode, String titleId, String displayName) {
        static EquipResult fail(int retcode) {
            return new EquipResult(false, retcode, "", "");
        }
    }

    /** 成就 id → 称号。 */
    private static final Map<String, TitleDef> BY_ACHIEVEMENT = new LinkedHashMap<>();
    private static final Map<String, TitleDef> BY_TITLE_ID = new LinkedHashMap<>();

    static {
        register(new TitleDef("beginner", "初心者", "gacha_10"));
        register(new TitleDef("gacha_veteran", "百抽达人", "gacha_100"));
        register(new TitleDef("rogue_explorer", "模拟宇宙探索者", "rogue_floor_5"));
        register(new TitleDef("battle_ace", "百战精锐", "battle_win_20"));
        register(new TitleDef("handbook_starter", "图鉴初学者", "handbook_any"));
        register(new TitleDef("avatar_collector", "角色收藏家", "handbook_avatar"));
        register(new TitleDef("cone_collector", "光锥收藏家", "handbook_light_cone"));
        register(new TitleDef("enemy_scholar", "敌影学者", "handbook_enemy"));
    }

    private static void register(TitleDef def) {
        BY_ACHIEVEMENT.put(def.sourceAchievement(), def);
        BY_TITLE_ID.put(def.titleId(), def);
    }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlayerCardService> cardProvider;

    public TitleService(JdbcTemplate jdbc, ObjectProvider<PlayerCardService> cardProvider) {
        this.jdbc = jdbc;
        this.cardProvider = cardProvider;
    }

    /** 成就领取成功后调用：按映射解锁对应称号。 */
    public boolean unlockFromAchievement(int playerId, String achievementId) {
        if (playerId <= 0 || achievementId == null || achievementId.isBlank()) {
            return false;
        }
        TitleDef def = BY_ACHIEVEMENT.get(achievementId);
        if (def == null) {
            return false;
        }
        try {
            jdbc.update("""
                    INSERT IGNORE INTO player_title (player_id, title_id, unlocked_at)
                    VALUES (?, ?, ?)
                    """, playerId, def.titleId(), Instant.now().toString());
            return true;
        } catch (Exception e) {
            log.debug("unlock title skipped: {}", e.getMessage());
            return false;
        }
    }

    public List<TitleView> listForPlayer(int playerId) {
        String equipped = getEquippedTitleId(playerId);
        java.util.Set<String> unlocked = loadUnlocked(playerId);
        List<TitleView> out = new ArrayList<>();
        for (TitleDef def : BY_TITLE_ID.values()) {
            boolean own = unlocked.contains(def.titleId());
            out.add(new TitleView(def.titleId(), def.displayName(), def.sourceAchievement(),
                    own, own && def.titleId().equals(equipped)));
        }
        return out;
    }

    public EquipResult equip(int playerId, String titleId) {
        if (playerId <= 0) {
            return EquipResult.fail(1);
        }
        String id = titleId == null ? "" : titleId.trim();
        if (id.isEmpty()) {
            persistEquipped(playerId, "");
            syncCardTitle(playerId, "");
            return new EquipResult(true, 0, "", "");
        }
        TitleDef def = BY_TITLE_ID.get(id);
        if (def == null) {
            return EquipResult.fail(2);
        }
        if (!loadUnlocked(playerId).contains(id)) {
            return EquipResult.fail(3);
        }
        persistEquipped(playerId, id);
        syncCardTitle(playerId, def.displayName());
        return new EquipResult(true, 0, id, def.displayName());
    }

    public String getEquippedDisplayName(int playerId) {
        String id = getEquippedTitleId(playerId);
        if (id.isEmpty()) {
            return "";
        }
        TitleDef def = BY_TITLE_ID.get(id);
        return def == null ? "" : def.displayName();
    }

    public List<TitleDef> catalog() {
        return List.copyOf(BY_TITLE_ID.values());
    }

    private String getEquippedTitleId(int playerId) {
        try {
            List<String> list = jdbc.query("""
                    SELECT equipped_title_id FROM player_social_profile WHERE player_id=?
                    """, (rs, i) -> {
                String v = rs.getString("equipped_title_id");
                return v == null ? "" : v;
            }, playerId);
            return list.isEmpty() ? "" : list.get(0);
        } catch (Exception e) {
            return "";
        }
    }

    private Set<String> loadUnlocked(int playerId) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        try {
            jdbc.query("SELECT title_id FROM player_title WHERE player_id=?", rs -> {
                while (rs.next()) {
                    String tid = rs.getString("title_id");
                    if (tid != null && !tid.isBlank()) {
                        set.add(tid);
                    }
                }
                return null;
            }, playerId);
        } catch (Exception ignored) {
        }
        return set;
    }

    private void persistEquipped(int playerId, String titleId) {
        try {
            jdbc.update("""
                    INSERT INTO player_social_profile
                    (player_id, signature, status_message, custom_status, equipped_title_id, updated_at)
                    VALUES (?, '', '', 'ONLINE', ?, ?)
                    ON DUPLICATE KEY UPDATE equipped_title_id=VALUES(equipped_title_id),
                      updated_at=VALUES(updated_at)
                    """, playerId, titleId, Instant.now().toString());
        } catch (Exception e) {
            log.debug("equip title persist skipped: {}", e.getMessage());
        }
    }

    private void syncCardTitle(int playerId, String displayName) {
        PlayerCardService cards = cardProvider.getIfAvailable();
        if (cards == null) {
            return;
        }
        try {
            cards.updateShowcase(playerId, null, displayName, null);
        } catch (Exception e) {
            log.debug("sync card title skipped: {}", e.getMessage());
        }
    }

}
