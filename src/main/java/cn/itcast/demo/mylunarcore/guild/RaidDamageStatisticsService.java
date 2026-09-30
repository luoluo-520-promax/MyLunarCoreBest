package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.social.VoiceSignalingService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Raid 实时伤害统计 + 进本自动加入语音房间。
 * 按秒推送 DPS 排名，可作为结算权重之一。
 */
@Service
public class RaidDamageStatisticsService {

    public record PlayerDps(int playerId, long totalDamage, double dps, int rank) {}

    private final Map<String, Map<Integer, AtomicLong>> damageByRaid = new ConcurrentHashMap<>();
    private final Map<String, Long> raidStartMs = new ConcurrentHashMap<>();
    private final GuildRaidInstanceService raidInstances;
    private final VoiceSignalingService voice;
    private final GameSessionManager sessionManager;

    public RaidDamageStatisticsService(ObjectProvider<GuildRaidInstanceService> raidProvider,
                                       ObjectProvider<VoiceSignalingService> voiceProvider,
                                       ObjectProvider<GameSessionManager> sessionProvider) {
        this.raidInstances = raidProvider == null ? null : raidProvider.getIfAvailable();
        this.voice = voiceProvider == null ? null : voiceProvider.getIfAvailable();
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    /** 玩家加入 Raid：记伤起点 + 自动进语音房。 */
    public void onPlayerJoin(String instanceId, int playerId) {
        damageByRaid.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, k -> new AtomicLong());
        raidStartMs.putIfAbsent(instanceId, System.currentTimeMillis());
        if (voice != null) {
            voice.joinOrCreate(playerId, "guild-raid-" + instanceId);
        }
        if (raidInstances != null) {
            // 确保实例存在时已登记（join 可能由外部先调）
            raidInstances.get(instanceId);
        }
    }

    public void recordDamage(String instanceId, int playerId, long damage) {
        if (damage <= 0) {
            return;
        }
        damageByRaid.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, k -> new AtomicLong())
                .addAndGet(damage);
        if (raidInstances != null) {
            raidInstances.applyDamage(instanceId, playerId, damage);
        }
    }

    public List<PlayerDps> rankings(String instanceId) {
        Map<Integer, AtomicLong> map = damageByRaid.getOrDefault(instanceId, Map.of());
        long start = raidStartMs.getOrDefault(instanceId, System.currentTimeMillis());
        double elapsedSec = Math.max(1.0, (System.currentTimeMillis() - start) / 1000.0);
        List<PlayerDps> list = new ArrayList<>();
        for (Map.Entry<Integer, AtomicLong> e : map.entrySet()) {
            long total = e.getValue().get();
            list.add(new PlayerDps(e.getKey(), total, total / elapsedSec, 0));
        }
        list.sort(Comparator.comparingLong(PlayerDps::totalDamage).reversed());
        List<PlayerDps> ranked = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            PlayerDps p = list.get(i);
            ranked.add(new PlayerDps(p.playerId(), p.totalDamage(), p.dps(), i + 1));
        }
        return ranked;
    }

    @Scheduled(fixedDelay = 1_000L)
    public void pushAll() {
        if (sessionManager == null) {
            return;
        }
        for (String instanceId : damageByRaid.keySet()) {
            push(instanceId);
        }
    }

    public void push(String instanceId) {
        if (sessionManager == null) {
            return;
        }
        List<PlayerDps> ranks = rankings(instanceId);
        StringBuilder sb = new StringBuilder("{\"instanceId\":\"").append(instanceId)
                .append("\",\"rankings\":[");
        for (int i = 0; i < ranks.size(); i++) {
            PlayerDps p = ranks.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"playerId\":").append(p.playerId())
                    .append(",\"total\":").append(p.totalDamage())
                    .append(",\"dps\":").append(String.format("%.1f", p.dps()))
                    .append(",\"rank\":").append(p.rank()).append('}');
        }
        sb.append("]}");
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        Map<Integer, AtomicLong> map = damageByRaid.get(instanceId);
        if (map == null) {
            return;
        }
        for (Integer pid : map.keySet()) {
            GameSession s = sessionManager.getOrNull(pid);
            if (s != null) {
                s.send(new GamePacket(CmdIds.RAID_DAMAGE_STATS_SC_NOTIFY, payload));
            }
        }
    }
}
