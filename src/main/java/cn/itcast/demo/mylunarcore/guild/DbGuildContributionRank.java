package cn.itcast.demo.mylunarcore.guild;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公会贡献排行实现：优先查询 MySQL 中 guild_member.weekly_contrib 生成周榜；
 * 数据库查询失败（如启动期表未建好）时回退到进程内内存累计，保证接口可用。
 *
 * <p>贡献增量由 {@link GuildService#contribute} 调用 {@link #recordContribution} 写入，
 * 内存态与 DB 态双写：DB 为主数据源，内存仅作兜底。
 */
@Service
public class DbGuildContributionRank implements GuildContributionRank {

    /** JDBC 模板，周贡献榜的主数据源。 */
    private final JdbcTemplate jdbc;
    /** 内存兜底累计表：key = "guildId:playerId"，value = 累计贡献增量（DB 不可用时使用）。 */
    private final Map<String, Integer> memoryFallback = new ConcurrentHashMap<>();

    public DbGuildContributionRank(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询公会内本周贡献榜 TopN。
     * 优先按 guild_member.weekly_contrib 降序 + player_id 升序取前 N；
     * 查询失败时改用内存累计值排序（见 {@link #memoryTop}）。
     *
     * @param guildId 公会 ID
     * @param topN    榜单条数（自动收敛到 1~100）
     * @return 从第 1 名开始的排行条目列表
     */
    @Override
    public List<RankEntry> weeklyRank(long guildId, int topN) {
        int limit = Math.max(1, Math.min(topN, 100));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT player_id, weekly_contrib
                    FROM guild_member
                    WHERE guild_id = ?
                    ORDER BY weekly_contrib DESC, player_id ASC
                    LIMIT ?
                    """, guildId, limit);
            List<RankEntry> out = new ArrayList<>(rows.size());
            int rank = 1;
            for (Map<String, Object> row : rows) {
                int playerId = ((Number) row.get("player_id")).intValue();
                int contrib = ((Number) row.get("weekly_contrib")).intValue();
                out.add(new RankEntry(playerId, contrib, rank++));
            }
            return out;
        } catch (Exception ignored) {
            return memoryTop(guildId, limit);
        }
    }

    /**
     * 记录一次贡献增量。
     * 将增量累加到内存兜底表（key = guildId:playerId）；delta 为 0 时忽略。
     * DB 中的周贡献由 {@link GuildService#contribute} 直接更新，本方法不重复写库。
     */
    @Override
    public void recordContribution(long guildId, int playerId, int delta) {
        if (delta == 0) {
            return;
        }
        memoryFallback.merge(guildId + ":" + playerId, delta, Integer::sum);
    }

    /**
     * 内存兜底榜单：过滤出指定公会的累计贡献，按贡献降序取前 limit 名。
     * 仅在 DB 查询失败时被调用，保证排行功能在数据库不可用阶段仍能对外服务。
     */
    private List<RankEntry> memoryTop(long guildId, int limit) {
        String prefix = guildId + ":";
        List<RankEntry> tmp = new ArrayList<>();
        for (Map.Entry<String, Integer> e : memoryFallback.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            int playerId = Integer.parseInt(e.getKey().substring(prefix.length()));
            tmp.add(new RankEntry(playerId, e.getValue(), 0));
        }
        tmp.sort((a, b) -> Integer.compare(b.contribution(), a.contribution()));
        List<RankEntry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, tmp.size()); i++) {
            RankEntry e = tmp.get(i);
            out.add(new RankEntry(e.playerId(), e.contribution(), i + 1));
        }
        return out;
    }
}
