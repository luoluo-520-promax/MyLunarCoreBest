package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组队语音信令完善：房间成员、过期清理、下发 VoiceSignalingScNotify；对接声网等第三方 RTC。
 */
@Service
public class VoiceSignalingService {

    public record RoomTicket(String roomId, String token, Instant expireAt, String turnHint,
                             String rtcAppId, String channelName) {}

    public record SignalResult(boolean success, int retcode, RoomTicket ticket) {}

    private final ConcurrentHashMap<String, RoomTicket> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Integer>> members = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;
    private final String turnHint;
    private final String rtcAppId;
    private final long ticketTtlSeconds;

    public VoiceSignalingService(ObjectProvider<GameSessionManager> sessionProvider,
                                 @Value("${lunarcore.voice.turn-hint:turn:relay.example.local:3478}") String turnHint,
                                 @Value("${lunarcore.voice.rtc-app-id:}") String rtcAppId,
                                 @Value("${lunarcore.voice.ticket-ttl-seconds:7200}") long ticketTtlSeconds) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.turnHint = turnHint == null || turnHint.isBlank() ? "turn:relay.example.local:3478" : turnHint;
        this.rtcAppId = rtcAppId == null ? "" : rtcAppId;
        this.ticketTtlSeconds = Math.max(60, ticketTtlSeconds);
    }

    /** 兼容旧单测构造。 */
    public VoiceSignalingService() {
        this(null, "turn:relay.example.local:3478", "", 7200);
    }

    public SignalResult joinOrCreate(int playerId, String roomKey) {
        if (playerId <= 0 || roomKey == null || roomKey.isBlank()) {
            return new SignalResult(false, 1, null);
        }
        String id = "voice:" + roomKey.trim();
        Instant now = Instant.now();
        RoomTicket existing = rooms.get(id);
        if (existing != null && existing.expireAt().isAfter(now)) {
            members.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(playerId);
            pushTicket(playerId, existing);
            return new SignalResult(true, 0, existing);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        RoomTicket ticket = new RoomTicket(
                id,
                token,
                now.plusSeconds(ticketTtlSeconds),
                turnHint,
                rtcAppId,
                roomKey.trim());
        rooms.put(id, ticket);
        members.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        pushTicket(playerId, ticket);
        return new SignalResult(true, 0, ticket);
    }

    public boolean validate(String roomId, String token) {
        RoomTicket t = rooms.get(roomId);
        return t != null && t.token().equals(token) && t.expireAt().isAfter(Instant.now());
    }

    public void leave(String roomId, int playerId) {
        Set<Integer> set = members.get(roomId);
        if (set != null) {
            set.remove(playerId);
            if (set.isEmpty()) {
                members.remove(roomId);
                rooms.remove(roomId);
            }
        } else if (playerId <= 0) {
            rooms.remove(roomId);
            members.remove(roomId);
        }
    }

    public Map<String, Object> describe(String roomId) {
        RoomTicket t = rooms.get(roomId);
        if (t == null) {
            return Map.of("exists", false);
        }
        Set<Integer> set = members.getOrDefault(roomId, Set.of());
        return Map.of(
                "exists", true,
                "roomId", t.roomId(),
                "expireAt", t.expireAt().toString(),
                "turnHint", t.turnHint(),
                "rtcAppId", t.rtcAppId(),
                "members", set.size());
    }

    @Scheduled(fixedDelay = 60_000L)
    public void cleanupExpired() {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, RoomTicket>> it = rooms.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, RoomTicket> e = it.next();
            if (e.getValue().expireAt().isBefore(now)) {
                it.remove();
                members.remove(e.getKey());
            }
        }
    }

    private void pushTicket(int playerId, RoomTicket ticket) {
        if (sessionManager == null) {
            return;
        }
        String json = "{\"roomId\":\"" + ticket.roomId() + "\",\"token\":\"" + ticket.token()
                + "\",\"expireAt\":\"" + ticket.expireAt() + "\",\"turnHint\":\"" + ticket.turnHint()
                + "\",\"rtcAppId\":\"" + ticket.rtcAppId() + "\",\"channel\":\"" + ticket.channelName() + "\"}";
        GameSession s = sessionManager.getOrNull(playerId);
        if (s != null) {
            s.send(new GamePacket(CmdIds.VOICE_SIGNALING_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
