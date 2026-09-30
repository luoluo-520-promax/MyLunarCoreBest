package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 战斗录像短时分享：Redis TTL 副本 + 好友直发 / 公会频道推送。
 */
@Service
public class BattleReplayShareService {

    private static final Logger log = LoggerFactory.getLogger(BattleReplayShareService.class);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "battle:replay:share:";

    public record ShareResult(boolean ok, int retcode, String shareCode, long expireAtMs) {}

    public record FetchResult(boolean ok, int retcode, String battleId, int ownerPlayerId,
                              byte[] payload, long expireAtMs) {}

    private record ShareBlob(String battleId, int ownerPlayerId, byte[] payload, long expireAtMs) {}

    private final ObjectProvider<BattleSnapshotService> snapshotProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ConcurrentHashMap<String, ShareBlob> local = new ConcurrentHashMap<>();

    public BattleReplayShareService(ObjectProvider<BattleSnapshotService> snapshotProvider,
                                    ObjectProvider<StringRedisTemplate> redisProvider,
                                    ObjectProvider<GameSessionManager> sessionProvider) {
        this.snapshotProvider = snapshotProvider;
        this.redisProvider = redisProvider;
        this.sessionProvider = sessionProvider;
    }

    public ShareResult share(int ownerPlayerId, String battleId, int targetFriendId,
                             boolean toGuild, String nickname) {
        if (ownerPlayerId <= 0) {
            return new ShareResult(false, 1, "", 0);
        }
        BattleSnapshotService snapshots = snapshotProvider.getIfAvailable();
        byte[] payload;
        String bid = battleId == null ? "" : battleId;
        if (snapshots != null) {
            long battleKey = 0L;
            try {
                if (!bid.isBlank()) {
                    battleKey = Long.parseLong(bid);
                }
            } catch (NumberFormatException ignored) {
            }
            List<?> deltas = battleKey > 0 ? snapshots.recentDeltas(battleKey) : List.of();
            StringBuilder sb = new StringBuilder();
            sb.append("owner=").append(ownerPlayerId).append(";battle=").append(bid).append(';');
            sb.append("deltas=").append(deltas.size());
            payload = sb.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            payload = ("owner=" + ownerPlayerId + ";battle=" + bid).getBytes(StandardCharsets.UTF_8);
        }
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long expireAt = System.currentTimeMillis() + TTL.toMillis();
        ShareBlob blob = new ShareBlob(bid, ownerPlayerId, payload, expireAt);
        local.put(code, blob);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                String encoded = Base64.getEncoder().encodeToString(payload);
                redis.opsForValue().set(KEY_PREFIX + code,
                        ownerPlayerId + "|" + bid + "|" + expireAt + "|" + encoded,
                        TTL.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("replay share redis skipped: {}", e.getMessage());
            }
        }
        if (targetFriendId > 0) {
            pushToPlayer(targetFriendId, code, ownerPlayerId, bid, nickname, false);
        }
        if (toGuild) {
            // 公会广播由上层传入在线成员列表更稳妥；此处推送给分享者自身作为回执示范，
            // Guild 频道广播可在 GuildNetty 扩展时复用 GuildReplayScNotify。
            pushToPlayer(ownerPlayerId, code, ownerPlayerId, bid, nickname, true);
        }
        return new ShareResult(true, 0, code, expireAt);
    }

    public FetchResult fetch(String shareCode) {
        if (shareCode == null || shareCode.isBlank()) {
            return new FetchResult(false, 2, "", 0, new byte[0], 0);
        }
        ShareBlob localBlob = local.get(shareCode);
        if (localBlob != null && localBlob.expireAtMs() > System.currentTimeMillis()) {
            return new FetchResult(true, 0, localBlob.battleId(), localBlob.ownerPlayerId(),
                    localBlob.payload(), localBlob.expireAtMs());
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return new FetchResult(false, 3, "", 0, new byte[0], 0);
        }
        try {
            String raw = redis.opsForValue().get(KEY_PREFIX + shareCode);
            if (raw == null || raw.isBlank()) {
                return new FetchResult(false, 3, "", 0, new byte[0], 0);
            }
            String[] parts = raw.split("\\|", 4);
            if (parts.length < 4) {
                return new FetchResult(false, 3, "", 0, new byte[0], 0);
            }
            int owner = Integer.parseInt(parts[0]);
            String battleId = parts[1];
            long expireAt = Long.parseLong(parts[2]);
            byte[] payload = Base64.getDecoder().decode(parts[3]);
            return new FetchResult(true, 0, battleId, owner, payload, expireAt);
        } catch (Exception e) {
            return new FetchResult(false, 5, "", 0, new byte[0], 0);
        }
    }

    public void notifyGuildMembers(Iterable<Integer> memberIds, String shareCode,
                                   int fromPlayerId, String battleId, String nickname) {
        for (Integer mid : memberIds) {
            if (mid == null || mid <= 0) {
                continue;
            }
            pushToPlayer(mid, shareCode, fromPlayerId, battleId, nickname, true);
        }
    }

    private void pushToPlayer(int playerId, String shareCode, int fromPlayerId,
                              String battleId, String nickname, boolean guildChannel) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        if (guildChannel) {
            QolSocialSystemProto.GuildReplayScNotify notify =
                    QolSocialSystemProto.GuildReplayScNotify.newBuilder()
                            .setShareCode(shareCode)
                            .setFromPlayerId(fromPlayerId)
                            .setBattleId(battleId == null ? "" : battleId)
                            .setNickname(nickname == null ? "" : nickname)
                            .build();
            session.send(new GamePacket(CmdIds.GUILD_REPLAY_SC_NOTIFY, notify.toByteArray()));
        }
    }
}
