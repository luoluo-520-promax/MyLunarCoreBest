package cn.itcast.demo.mylunarcore.guild;

import java.util.List;

/**
 * 公会贡献排行接口（预留）。
 * <p>
 * 业务含义：贡献值是公会内部的核心社交货币，贡献排行用于：
 * <ul>
 *   <li>公会内“周贡献榜”展示（本周贡献最高成员的荣誉与激励）；</li>
 *   <li>后续可扩展驱动：商店折扣档位、讨伐（Raid）门票资格、赛季排行榜等。</li>
 * </ul>
 * 默认实现见 {@link DbGuildContributionRank}（优先读 DB 周贡献，失败回退内存累计）。
 * 公会贡献的数据写入入口在 {@link GuildService#contribute}。
 */
public interface GuildContributionRank {

    /**
     * 排行条目。
     *
     * @param playerId     玩家 ID
     * @param contribution 该玩家本周期贡献值
     * @param rank         名次（1 起）
     */
    record RankEntry(int playerId, int contribution, int rank) {}

    /** 刷新公会内周贡献榜（返回前 topN 名）。 */
    List<RankEntry> weeklyRank(long guildId, int topN);

    /** 记录一次贡献增量（可与 {@link GuildService#addContribution} 联动）。 */
    void recordContribution(long guildId, int playerId, int delta);
}
