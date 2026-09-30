package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.hall.FriendApplicationService;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.profile.PlayerProfileService;
import cn.itcast.demo.mylunarcore.profile.TitleService;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 跨节点好友在线状态：走 Redis {@code online_status_channel} 订阅关系推送，避免全服广播。
 */
@Service
public class FriendOnlineStatusService {

    public static final String CHANNEL = "online_status_channel";

    private static final Logger log = LoggerFactory.getLogger(FriendOnlineStatusService.class);

    private final FriendApplicationService friendApplicationService;
    private final GameSessionManager sessionManager;
    private final ObjectProvider<SceneManager> sceneManagerProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<PlayerProfileService> profileProvider;
    private final ObjectProvider<TitleService> titleProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FriendOnlineStatusService(FriendApplicationService friendApplicationService,
                                     GameSessionManager sessionManager,
                                     ObjectProvider<SceneManager> sceneManagerProvider,
                                     ObjectProvider<StringRedisTemplate> redisProvider) {
        this(friendApplicationService, sessionManager, sceneManagerProvider, redisProvider, null, null);
    }

    public FriendOnlineStatusService(FriendApplicationService friendApplicationService,
                                     GameSessionManager sessionManager,
                                     ObjectProvider<SceneManager> sceneManagerProvider,
                                     ObjectProvider<StringRedisTemplate> redisProvider,
                                     ObjectProvider<PlayerProfileService> profileProvider,
                                     ObjectProvider<TitleService> titleProvider) {
        this.friendApplicationService = friendApplicationService;
        this.sessionManager = sessionManager;
        this.sceneManagerProvider = sceneManagerProvider;
        this.redisProvider = redisProvider;
        this.profileProvider = profileProvider;
        this.titleProvider = titleProvider;
    }

    public void publishOnline(int playerId) {
        publish(playerId, true);
    }

    public void publishOffline(int playerId) {
        publish(playerId, false);
    }

    public void onRemotePayload(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            int playerId = ((Number) map.getOrDefault("playerId", 0)).intValue();
            boolean online = Boolean.parseBoolean(String.valueOf(map.getOrDefault("online", false)));
            int planeId = ((Number) map.getOrDefault("planeId", 0)).intValue();
            int floorId = ((Number) map.getOrDefault("floorId", 0)).intValue();
            String customStatus = String.valueOf(map.getOrDefault("customStatus", ""));
            String statusMessage = String.valueOf(map.getOrDefault("statusMessage", ""));
            String equippedTitle = String.valueOf(map.getOrDefault("equippedTitle", ""));
            notifyLocalFriends(playerId, online, planeId, floorId, customStatus, statusMessage, equippedTitle);
        } catch (Exception e) {
            log.debug("online_status payload ignored: {}", e.getMessage());
        }
    }

    private void publish(int playerId, boolean online) {
        int planeId = 0;
        int floorId = 0;
        SceneManager scenes = sceneManagerProvider == null ? null : sceneManagerProvider.getIfAvailable();
        if (scenes != null) {
            SceneContext ctx = scenes.getByPlayerUid(playerId);
            if (ctx != null) {
                planeId = ctx.getPlaneId();
                floorId = ctx.getFloorId();
            }
        }
        String customStatus = "ONLINE";
        String statusMessage = "";
        String equippedTitle = "";
        PlayerProfileService profileSvc = profileProvider == null ? null : profileProvider.getIfAvailable();
        if (profileSvc != null) {
            PlayerProfileService.Profile p = profileSvc.getOrDefault(playerId);
            customStatus = p.customStatus();
            statusMessage = p.statusMessage();
        }
        TitleService titles = titleProvider == null ? null : titleProvider.getIfAvailable();
        if (titles != null) {
            equippedTitle = titles.getEquippedDisplayName(playerId);
        }
        notifyLocalFriends(playerId, online, planeId, floorId, customStatus, statusMessage, equippedTitle);
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            Map<String, Object> payloadMap = new HashMap<>();
            payloadMap.put("playerId", playerId);
            payloadMap.put("online", online);
            payloadMap.put("planeId", planeId);
            payloadMap.put("floorId", floorId);
            payloadMap.put("atMs", System.currentTimeMillis());
            payloadMap.put("customStatus", customStatus == null ? "" : customStatus);
            payloadMap.put("statusMessage", statusMessage == null ? "" : statusMessage);
            payloadMap.put("equippedTitle", equippedTitle == null ? "" : equippedTitle);
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(payloadMap));
        } catch (Exception e) {
            log.debug("online_status publish skipped: {}", e.getMessage());
        }
    }

    private void notifyLocalFriends(int playerId, boolean online, int planeId, int floorId,
                                    String customStatus, String statusMessage, String equippedTitle) {
        Set<Integer> friendIds = friendIdsOf(playerId);
        HallSystemProto.FriendOnlineNotify notify = HallSystemProto.FriendOnlineNotify.newBuilder()
                .setFriendPlayerId(playerId)
                .setOnline(online)
                .setPlaneId(planeId)
                .setFloorId(floorId)
                .setAtMs(System.currentTimeMillis())
                .setCustomStatus(customStatus == null ? "" : customStatus)
                .setStatusMessage(statusMessage == null ? "" : statusMessage)
                .setEquippedTitle(equippedTitle == null ? "" : equippedTitle)
                .build();
        GamePacket packet = new GamePacket(CmdIds.FRIEND_ONLINE_SC_NOTIFY, notify.toByteArray());
        for (Integer fid : friendIds) {
            GameSession session = sessionManager.getOrNull(fid.longValue());
            if (session != null) {
                session.send(packet);
            }
        }
    }

    private Set<Integer> friendIdsOf(int playerId) {
        Set<Integer> ids = new LinkedHashSet<>();
        try {
            for (FriendEntity rel : friendApplicationService.listFriends(playerId)) {
                if (rel == null || rel.getStatus() != 1) {
                    continue;
                }
                int other = rel.getPlayerId1() == playerId ? rel.getPlayerId2() : rel.getPlayerId1();
                if (other > 0 && other != playerId) {
                    ids.add(other);
                }
            }
        } catch (Exception ignored) {
            // 好友表未就绪
        }
        return ids;
    }
}
