package cn.itcast.demo.mylunarcore.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 公会搜索：名称前缀 LIKE，或公会 ID 后缀/精确匹配，分页返回。
 */
@Service
public class GuildSearchService {

    private static final Logger log = LoggerFactory.getLogger(GuildSearchService.class);

    public record GuildHit(long guildId, String name, int level, int memberCount) {}

    public record SearchPage(List<GuildHit> hits, int total) {}

    private final JdbcTemplate jdbc;

    public GuildSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SearchPage search(String query, int page, int pageSize) {
        String q = query == null ? "" : query.trim();
        int size = Math.min(Math.max(pageSize, 1), 50);
        int p = Math.max(page, 1);
        int offset = (p - 1) * size;
        if (q.isEmpty()) {
            return new SearchPage(List.of(), 0);
        }
        try {
            if (isNumeric(q)) {
                return searchById(q, size, offset);
            }
            return searchByNamePrefix(q, size, offset);
        } catch (Exception e) {
            log.debug("guild search failed: {}", e.getMessage());
            return new SearchPage(List.of(), 0);
        }
    }

    private SearchPage searchById(String q, int size, int offset) {
        long exact;
        try {
            exact = Long.parseLong(q);
        } catch (NumberFormatException e) {
            exact = -1L;
        }
        String suffix = "%" + q;
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(1) FROM guild
                WHERE guild_id=? OR CAST(guild_id AS VARCHAR) LIKE ?
                """, Integer.class, exact, suffix);
        int t = total == null ? 0 : total;
        List<GuildHit> hits = jdbc.query("""
                SELECT guild_id, name, level, member_count FROM guild
                WHERE guild_id=? OR CAST(guild_id AS VARCHAR) LIKE ?
                ORDER BY CASE WHEN guild_id=? THEN 0 ELSE 1 END, guild_id ASC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new GuildHit(
                        rs.getLong("guild_id"),
                        rs.getString("name"),
                        rs.getInt("level"),
                        rs.getInt("member_count")),
                exact, suffix, exact, size, offset);
        return new SearchPage(hits, t);
    }

    private SearchPage searchByNamePrefix(String q, int size, int offset) {
        String like = q + "%";
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM guild WHERE name LIKE ?", Integer.class, like);
        int t = total == null ? 0 : total;
        List<GuildHit> hits = jdbc.query("""
                SELECT guild_id, name, level, member_count FROM guild
                WHERE name LIKE ?
                ORDER BY name ASC, guild_id ASC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new GuildHit(
                        rs.getLong("guild_id"),
                        rs.getString("name"),
                        rs.getInt("level"),
                        rs.getInt("member_count")),
                like, size, offset);
        return new SearchPage(hits, t);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
