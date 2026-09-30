package cn.itcast.demo.mylunarcore.guild;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公会讨伐（Raid）排期默认实现（进程内骨架）。
 * <p>
 * 业务含义：为公会预约一场限时讨伐活动（如公会 BOSS、公会副本开团），
 * 只负责“排期”契约——创建场次、查询进行中的场次、取消未开始的场次。
 * 本实现为内存态骨架：未接入真实副本战斗结算，OpenAPI/调度层可在此基础上
 * 扩展为“对齐活动日历 + 落库 + 战斗结算”的完整方案。
 * <p>
 * 排期规则：未指定开放时间则默认 1 小时后开、持续 2 小时；每场最多 3 次尝试。
 */
@Service
public class InMemoryGuildRaidScheduler implements GuildRaidScheduler {

    /** 场次 ID 自增发生器（进程内，配合内存 map 使用）。 */
    private final AtomicLong seq = new AtomicLong(1);
    /** 场次缓存：raidId → 场次信息（进程内存态，重启即失效）。 */
    private final ConcurrentHashMap<Long, RaidSlot> slots = new ConcurrentHashMap<>();

    /**
     * 为公会预约下一场 Raid。
     * 校验请求参数（公会 ID 必填）；开放时间未指定时默认“1 小时后”开，
     * 持续时间固定 2 小时、可尝试次数 3 次，落缓存并返回。
     *
     * @param request 排期请求（公会 ID / 活动类型 / 期望开放时间）
     * @return 排期成功返回场次；请求非法返回空
     */
    @Override
    public Optional<RaidSlot> schedule(ScheduleRequest request) {
        if (request == null || request.guildId() <= 0) {
            return Optional.empty();
        }
        Instant open = request.preferredOpenAt() == null ? Instant.now().plus(1, ChronoUnit.HOURS) : request.preferredOpenAt();
        RaidSlot slot = new RaidSlot(
                seq.getAndIncrement(),
                request.guildId(),
                request.raidType() == null || request.raidType().isBlank() ? "guild_raid" : request.raidType(),
                open,
                open.plus(2, ChronoUnit.HOURS),
                3);
        slots.put(slot.raidId(), slot);
        return Optional.of(slot);
    }

    /**
     * 查询公会当前仍在进行中的场次（closeAt 未到即视为有效）。
     * 用于客户端展示“本公会可进入的讨伐副本”列表。
     */
    @Override
    public List<RaidSlot> listActive(long guildId) {
        Instant now = Instant.now();
        List<RaidSlot> out = new ArrayList<>();
        for (RaidSlot s : slots.values()) {
            if (s.guildId() == guildId && !now.isAfter(s.closeAt())) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 取消一场尚未开始的讨伐排期。
     * 校验：场次存在、属于该公会、且当前时间早于开放时间（已开打的场次不可取消）。
     */
    @Override
    public boolean cancel(long raidId, long guildId) {
        RaidSlot s = slots.get(raidId);
        if (s == null || s.guildId() != guildId) {
            return false;
        }
        if (Instant.now().isAfter(s.openAt())) {
            return false;
        }
        return slots.remove(raidId, s);
    }
}
