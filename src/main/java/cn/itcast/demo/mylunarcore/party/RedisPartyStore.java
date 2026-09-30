package cn.itcast.demo.mylunarcore.party;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis 跨节点组队状态：party JSON（含 ownerNodeId、version、心跳）+ uid→partyId。
 * 短租约 + 续期；节点宕机后 Redis 过期自动清理，避免幽灵队伍。
 */
@Component
public class RedisPartyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPartyStore.class);
    /** 组队租约；需由权威节点心跳续期。 */
    private static final Duration LEASE_TTL = Duration.ofSeconds(90);

    private final StringRedisTemplate redis;
    private final LunarCoreProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RedisPartyStore(ObjectProvider<StringRedisTemplate> redisProvider,
                           LunarCoreProperties properties) {
        this.redis = redisProvider.getIfAvailable();
        this.properties = properties;
    }

    public boolean available() {
        return redis != null && properties.getRedis().isEnabled();
    }

    public Duration leaseTtl() {
        return LEASE_TTL;
    }

    public void save(PartyService.Party party) {
        saveIfNewer(party);
    }

    /**
     * 仅当本地 version ≥ Redis 已存 version 时写入（最终一致性下的乐观冲突检测）。
     * @return false 表示版本冲突未写入
     */
    public boolean saveIfNewer(PartyService.Party party) {
        if (!available() || party == null) {
            return true;
        }
        String partyKey = partyKey(party.partyId());
        try {
            PartyService.Party existing = getByPartyId(party.partyId());
            if (existing != null && existing.version() > party.version()) {
                log.debug("party version conflict id={} local={} remote={}",
                        party.partyId(), party.version(), existing.version());
                return false;
            }
            Map<String, Object> payload = toPayload(party);
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(partyKey, json, LEASE_TTL);
            for (Long uid : party.memberUids()) {
                redis.opsForValue().set(uidKey(uid), party.partyId(), LEASE_TTL);
            }
            return true;
        } catch (JsonProcessingException e) {
            log.warn("party redis serialize failed: {}", party.partyId(), e);
            return false;
        }
    }

    public void renewLease(PartyService.Party party) {
        if (!available() || party == null) {
            return;
        }
        try {
            Map<String, Object> payload = toPayload(party);
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(partyKey(party.partyId()), json, LEASE_TTL);
            for (Long uid : party.memberUids()) {
                redis.opsForValue().set(uidKey(uid), party.partyId(), LEASE_TTL);
            }
        } catch (JsonProcessingException e) {
            log.warn("party lease renew failed: {}", party.partyId(), e);
        }
    }

    public void remove(PartyService.Party party) {
        if (!available() || party == null) {
            return;
        }
        redis.delete(partyKey(party.partyId()));
        for (Long uid : party.memberUids()) {
            redis.delete(uidKey(uid));
        }
    }

    public void clearUid(long uid) {
        if (!available()) {
            return;
        }
        String partyId = redis.opsForValue().get(uidKey(uid));
        redis.delete(uidKey(uid));
        if (partyId != null) {
            PartyService.Party party = getByPartyId(partyId);
            if (party != null) {
                List<Long> members = new ArrayList<>(party.memberUids());
                members.remove(uid);
                if (members.isEmpty()) {
                    redis.delete(partyKey(partyId));
                } else {
                    long leader = party.leaderUid() == uid ? members.get(0) : party.leaderUid();
                    save(party.withMembers(leader, members));
                }
            }
        }
    }

    public PartyService.Party getByUid(long uid) {
        if (!available()) {
            return null;
        }
        String partyId = redis.opsForValue().get(uidKey(uid));
        return partyId == null ? null : getByPartyId(partyId);
    }

    @SuppressWarnings("unchecked")
    public PartyService.Party getByPartyId(String partyId) {
        if (!available() || partyId == null) {
            return null;
        }
        String json = redis.opsForValue().get(partyKey(partyId));
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            List<Number> membersRaw = (List<Number>) map.get("memberUids");
            List<Long> members = membersRaw == null ? List.of()
                    : membersRaw.stream().map(Number::longValue).toList();
            Object owner = map.get("ownerNodeId");
            long version = map.get("version") instanceof Number n ? n.longValue() : 1L;
            long hb = map.get("lastHeartbeatMs") instanceof Number n ? n.longValue() : System.currentTimeMillis();
            return new PartyService.Party(
                    String.valueOf(map.get("partyId")),
                    ((Number) map.get("leaderUid")).longValue(),
                    members,
                    ((Number) map.get("createdAtMillis")).longValue(),
                    owner == null ? properties.getCenter().getLocalNodeId() : String.valueOf(owner),
                    version,
                    hb);
        } catch (Exception e) {
            log.warn("party redis deserialize failed: {}", partyId, e);
            return null;
        }
    }

    private Map<String, Object> toPayload(PartyService.Party party) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partyId", party.partyId());
        payload.put("leaderUid", party.leaderUid());
        payload.put("memberUids", party.memberUids());
        payload.put("createdAtMillis", party.createdAtMillis());
        payload.put("ownerNodeId", party.ownerNodeId());
        payload.put("version", party.version());
        payload.put("lastHeartbeatMs", party.lastHeartbeatMs());
        return payload;
    }

    private String partyKey(String partyId) {
        return properties.getRedis().getPartyKeyPrefix() + partyId;
    }

    private String uidKey(long uid) {
        return properties.getRedis().getPartyUidKeyPrefix() + uid;
    }
}
