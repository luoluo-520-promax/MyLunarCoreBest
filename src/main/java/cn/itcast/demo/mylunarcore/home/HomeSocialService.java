package cn.itcast.demo.mylunarcore.home;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园社交：访客点赞、繁荣度排行（可关联好友系统过滤）。
 */
@Service
public class HomeSocialService {

    public record RankEntry(int playerId, int prosperity, int likes) {}

    public record LikeResult(boolean success, int retcode, int likes) {}

    private final JdbcTemplate jdbc;
    private final HomeBaseService homeBaseService;
    /** hostPlayerId -> likerIds */
    private final Map<Integer, Set<Integer>> likes = new ConcurrentHashMap<>();

    public HomeSocialService(HomeBaseService homeBaseService, JdbcTemplate jdbc) {
        this.homeBaseService = homeBaseService;
        this.jdbc = jdbc;
    }

    public LikeResult like(int visitorId, int hostPlayerId) {
        if (visitorId <= 0 || hostPlayerId <= 0 || visitorId == hostPlayerId) {
            return new LikeResult(false, 1, 0);
        }
        Set<Integer> set = likes.computeIfAbsent(hostPlayerId, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(visitorId)) {
            return new LikeResult(false, 2, set.size()); // 已点赞
        }
        persistLike(hostPlayerId, visitorId);
        return new LikeResult(true, 0, set.size());
    }

    public int likeCount(int hostPlayerId) {
        Set<Integer> set = likes.get(hostPlayerId);
        if (set != null) {
            return set.size();
        }
        return loadLikeCount(hostPlayerId);
    }

    /** 繁荣度 = 设施等级和 ×10 + 家具数 ×5 + 点赞 ×3 */
    public int prosperity(int playerId) {
        HomeBaseService.HomeState state = homeBaseService.getOrCreate(playerId);
        int facilityScore = state.facilities().values().stream().mapToInt(Integer::intValue).sum() * 10;
        int furnitureScore = state.furniture().size() * 5;
        return facilityScore + furnitureScore + likeCount(playerId) * 3;
    }

    public List<RankEntry> topProsperity(int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<RankEntry> all = new ArrayList<>();
        // 内存家园 + 有点赞记录的宿主
        Set<Integer> candidates = ConcurrentHashMap.newKeySet();
        candidates.addAll(likes.keySet());
        try {
            List<Integer> ids = jdbc.queryForList("SELECT DISTINCT player_id FROM home_state LIMIT 500", Integer.class);
            candidates.addAll(ids);
        } catch (Exception ignored) {
            // home_state 表可能不存在
        }
        for (Integer pid : candidates) {
            all.add(new RankEntry(pid, prosperity(pid), likeCount(pid)));
        }
        all.sort(Comparator.comparingInt(RankEntry::prosperity).reversed());
        return all.size() <= n ? all : all.subList(0, n);
    }

    /** 好友家园榜：仅保留 friendIds 内的排名。 */
    public List<RankEntry> friendProsperityRank(Set<Integer> friendIds, int limit) {
        if (friendIds == null || friendIds.isEmpty()) {
            return List.of();
        }
        return topProsperity(200).stream()
                .filter(e -> friendIds.contains(e.playerId()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private void persistLike(int hostPlayerId, int visitorId) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO home_like (host_player_id, visitor_id, created_at)
                    VALUES (?, ?, NOW())
                    ON DUPLICATE KEY UPDATE created_at = VALUES(created_at)
                    """, hostPlayerId, visitorId);
        } catch (Exception ignored) {
            // 表未建时仅内存
        }
    }

    private int loadLikeCount(int hostPlayerId) {
        if (jdbc == null) {
            return 0;
        }
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM home_like WHERE host_player_id = ?", Integer.class, hostPlayerId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }
}
