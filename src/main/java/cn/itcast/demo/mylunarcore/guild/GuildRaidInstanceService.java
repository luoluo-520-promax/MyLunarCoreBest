package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公会多人同屏讨伐实例：支持 10–20 人并行，多队 battleId 挂在同一 Raid 下。
 * 进本后先进入 30 秒策略准备期（READY_ROOM），再开战。
 */
@Service
public class GuildRaidInstanceService {

    public static final int MIN_PLAYERS = 10;
    public static final int MAX_PLAYERS = 20;
    public static final int STRATEGY_PREPARE_MS = 30_000;

    public record RaidInstance(String instanceId, long guildId, long bossHp, AtomicLong remainHp,
                               Map<Integer, Long> playerBattles, AtomicInteger playerCount,
                               long createdAtMs, AtomicLong strategyEndsAtMs, String phase) {}

    private final ConcurrentHashMap<String, RaidInstance> instances = new ConcurrentHashMap<>();
    private final BattleManager battleManager;
    private final GameSessionManager sessionManager;
    private final RaidRoleAssignmentService roleAssignmentService;

    public GuildRaidInstanceService(ObjectProvider<BattleManager> battleProvider,
                                    ObjectProvider<GameSessionManager> sessionProvider) {
        this(battleProvider, sessionProvider, null);
    }

    public GuildRaidInstanceService(ObjectProvider<BattleManager> battleProvider,
                                    ObjectProvider<GameSessionManager> sessionProvider,
                                    ObjectProvider<RaidRoleAssignmentService> roleProvider) {
        this.battleManager = battleProvider == null ? null : battleProvider.getIfAvailable();
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.roleAssignmentService = roleProvider == null ? null : roleProvider.getIfAvailable();
    }

    public RaidInstance create(long guildId, long bossHp) {
        String id = "guild-raid-" + UUID.randomUUID();
        RaidInstance inst = new RaidInstance(id, guildId, Math.max(1, bossHp),
                new AtomicLong(Math.max(1, bossHp)), new ConcurrentHashMap<>(),
                new AtomicInteger(), System.currentTimeMillis(),
                new AtomicLong(0), "created");
        instances.put(id, inst);
        broadcast(inst, "created");
        return inst;
    }

    public boolean join(String instanceId, int playerId, long battleId) {
        RaidInstance inst = instances.get(instanceId);
        if (inst == null || playerId <= 0) {
            return false;
        }
        if (inst.playerBattles().containsKey(playerId)) {
            return true;
        }
        if (inst.playerCount().get() >= MAX_PLAYERS) {
            return false;
        }
        inst.playerBattles().put(playerId, battleId);
        inst.playerCount().incrementAndGet();
        if (roleAssignmentService != null) {
            roleAssignmentService.recommend(instanceId, new ArrayList<>(inst.playerBattles().keySet()));
        }
        broadcast(inst, "joined");
        return true;
    }

    /** 人数达标后进入 30 秒策略准备期。 */
    public boolean enterStrategyPhase(String instanceId) {
        RaidInstance inst = instances.get(instanceId);
        if (inst == null || !canStart(instanceId)) {
            return false;
        }
        long ends = System.currentTimeMillis() + STRATEGY_PREPARE_MS;
        inst.strategyEndsAtMs().set(ends);
        RaidInstance updated = new RaidInstance(inst.instanceId(), inst.guildId(), inst.bossHp(),
                inst.remainHp(), inst.playerBattles(), inst.playerCount(), inst.createdAtMs(),
                inst.strategyEndsAtMs(), "READY_ROOM");
        instances.put(instanceId, updated);
        broadcastStrategy(updated);
        broadcast(updated, "READY_ROOM");
        return true;
    }

    public boolean assignRole(String instanceId, int playerId, String role) {
        if (roleAssignmentService == null) {
            return false;
        }
        return roleAssignmentService.assign(instanceId, playerId, role) != null;
    }

    public long applyDamage(String instanceId, int playerId, long damage) {
        RaidInstance inst = instances.get(instanceId);
        if (inst == null || damage <= 0 || !inst.playerBattles().containsKey(playerId)) {
            return -1;
        }
        long remain = inst.remainHp().addAndGet(-damage);
        if (remain < 0) {
            inst.remainHp().set(0);
            remain = 0;
        }
        broadcast(inst, remain == 0 ? "cleared" : "damage");
        return remain;
    }

    public RaidInstance get(String instanceId) {
        return instances.get(instanceId);
    }

    public List<RaidInstance> listByGuild(long guildId) {
        List<RaidInstance> out = new ArrayList<>();
        for (RaidInstance i : instances.values()) {
            if (i.guildId() == guildId) {
                out.add(i);
            }
        }
        return out;
    }

    public boolean canStart(String instanceId) {
        RaidInstance inst = instances.get(instanceId);
        return inst != null && inst.playerCount().get() >= MIN_PLAYERS;
    }

    private void broadcastStrategy(RaidInstance inst) {
        if (sessionManager == null) {
            return;
        }
        StringBuilder roles = new StringBuilder("[");
        if (roleAssignmentService != null) {
            boolean first = true;
            for (RaidRoleAssignmentService.Assignment a : roleAssignmentService.list(inst.instanceId())) {
                if (!first) {
                    roles.append(',');
                }
                first = false;
                roles.append("{\"playerId\":").append(a.playerId())
                        .append(",\"role\":\"").append(a.role()).append("\"}");
            }
        }
        roles.append(']');
        String json = "{\"instanceId\":\"" + inst.instanceId()
                + "\",\"phase\":\"READY_ROOM\",\"prepareMs\":" + STRATEGY_PREPARE_MS
                + ",\"strategyEndsAtMs\":" + inst.strategyEndsAtMs().get()
                + ",\"roles\":" + roles + "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Integer pid : inst.playerBattles().keySet()) {
            GameSession s = sessionManager.getOrNull(pid);
            if (s != null) {
                s.send(new GamePacket(CmdIds.RAID_STRATEGY_PHASE_SC_NOTIFY, payload));
            }
        }
    }

    private void broadcast(RaidInstance inst, String phase) {
        if (sessionManager == null) {
            return;
        }
        String json = "{\"instanceId\":\"" + inst.instanceId() + "\",\"guildId\":" + inst.guildId()
                + ",\"remainHp\":" + inst.remainHp().get() + ",\"bossHp\":" + inst.bossHp()
                + ",\"players\":" + inst.playerCount().get()
                + ",\"phase\":\"" + phase + "\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Integer pid : inst.playerBattles().keySet()) {
            GameSession s = sessionManager.getOrNull(pid);
            if (s != null) {
                s.send(new GamePacket(CmdIds.GUILD_RAID_STATE_SC_NOTIFY, payload));
            }
        }
    }
}
