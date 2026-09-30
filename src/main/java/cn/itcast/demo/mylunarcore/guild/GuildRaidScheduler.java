package cn.itcast.demo.mylunarcore.guild;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 公会讨伐/公会战排期接口（预留）。
 * <p>
 * 公会商店已落地；战/副本是社交粘性核心，本接口定义开服活动洪峰下的调度契约。
 * <p>
 * 业务含义：公会需要像副本一样预约“讨伐”场次（开服时段集中开启），
 * 本接口抽象了排期的三个基础能力：预约、查询进行中场次、取消未开场次。
 * 默认实现见 {@link InMemoryGuildRaidScheduler}（内存骨架），
 * 后续可替换为基于活动日历 + 数据库的正式实现。
 */
public interface GuildRaidScheduler {

    /**
     * 一场讨伐的完整时间窗信息。
     *
     * @param raidId      场次唯一 ID
     * @param guildId     发起讨伐的公会 ID
     * @param raidType    讨伐类型标识（如 guild_raid / guild_boss）
     * @param openAt      场次开放（开打）时间
     * @param closeAt     场次关闭（结束）时间
     * @param maxAttempts 每名成员允许的最大尝试次数
     */
    record RaidSlot(long raidId, long guildId, String raidType, Instant openAt, Instant closeAt, int maxAttempts) {}

    /**
     * 预约讨伐的请求参数。
     *
     * @param guildId         发起公会 ID
     * @param raidType        讨伐类型标识
     * @param preferredOpenAt 期望开放时间；为空时由实现方决定默认值
     */
    record ScheduleRequest(long guildId, String raidType, Instant preferredOpenAt) {}

    /** 为公会预约下一场 Raid；实现方可按活动日历对齐。 */
    Optional<RaidSlot> schedule(ScheduleRequest request);

    /** 查询公会当前有效的 Raid 场次。 */
    List<RaidSlot> listActive(long guildId);

    /** 取消未开始的场次。 */
    boolean cancel(long raidId, long guildId);
}
