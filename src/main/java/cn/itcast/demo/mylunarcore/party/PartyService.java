package cn.itcast.demo.mylunarcore.party;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 开放世界组队：创建节点为权威（ownerNodeId）；跨节点变更须路由至 owner。
 * Redis 侧带版本号 + 心跳租约，节点故障后由 TTL/过期清理避免幽灵队伍。
 */
@Component
public class PartyService {

    public record Party(String partyId, long leaderUid, List<Long> memberUids, long createdAtMillis,
                        String ownerNodeId, long version, long lastHeartbeatMs) {
        public Party {
            ownerNodeId = ownerNodeId == null || ownerNodeId.isBlank() ? "local" : ownerNodeId;
            if (version < 1L) {
                version = 1L;
            }
            if (lastHeartbeatMs <= 0L) {
                lastHeartbeatMs = System.currentTimeMillis();
            }
        }

        /** 兼容旧 5 参构造。 */
        public Party(String partyId, long leaderUid, List<Long> memberUids, long createdAtMillis,
                     String ownerNodeId) {
            this(partyId, leaderUid, memberUids, createdAtMillis, ownerNodeId, 1L, System.currentTimeMillis());
        }

        public boolean contains(long uid) {
            return memberUids.contains(uid);
        }

        public Party withMembers(long leaderUid, List<Long> members) {
            return new Party(partyId, leaderUid, List.copyOf(members), createdAtMillis, ownerNodeId,
                    version + 1L, System.currentTimeMillis());
        }

        public Party touch() {
            return new Party(partyId, leaderUid, memberUids, createdAtMillis, ownerNodeId,
                    version, System.currentTimeMillis());
        }
    }

    public enum PartyResultCode {
        OK,
        ALREADY_IN_PARTY,
        NOT_IN_PARTY,
        NOT_LEADER,
        TARGET_BUSY,
        PARTY_FULL,
        NOT_FOUND,
        /** 本节点非权威，客户端/网关应转发到 ownerNodeId。 */
        NOT_OWNER_NODE,
        /** 版本冲突（并发写）。 */
        VERSION_CONFLICT
    }

    public record PartyResult(PartyResultCode code, Party party) {
        public static PartyResult of(PartyResultCode code, Party party) {
            return new PartyResult(code, party);
        }
    }

    private static final int MAX_MEMBERS = 4;
    private static final long INVITE_MANY_COOLDOWN_MS = 5_000L;

    private final Map<String, Party> parties = new ConcurrentHashMap<>();
    private final Map<Long, String> uidToParty = new ConcurrentHashMap<>();
    private final Map<Long, Long> inviteManyCooldown = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final RedisPartyStore redisPartyStore;
    private final LunarCoreProperties properties;

    public PartyService(RedisPartyStore redisPartyStore) {
        this(redisPartyStore, new LunarCoreProperties());
    }

    public PartyService(RedisPartyStore redisPartyStore, LunarCoreProperties properties) {
        this.redisPartyStore = redisPartyStore;
        this.properties = properties;
    }

    public String localNodeId() {
        return properties.getCenter().getLocalNodeId();
    }

    public boolean isOwner(Party party) {
        return party != null && localNodeId().equals(party.ownerNodeId());
    }

    public PartyResult create(long leaderUid) {
        if (resolveParty(leaderUid) != null) {
            return PartyResult.of(PartyResultCode.ALREADY_IN_PARTY, getByUid(leaderUid));
        }
        String partyId = "p" + seq.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
        List<Long> members = new ArrayList<>();
        members.add(leaderUid);
        long now = System.currentTimeMillis();
        Party party = new Party(partyId, leaderUid, List.copyOf(members), now, localNodeId(), 1L, now);
        parties.put(partyId, party);
        uidToParty.put(leaderUid, partyId);
        redisPartyStore.save(party);
        return PartyResult.of(PartyResultCode.OK, party);
    }

    public PartyResult invite(long leaderUid, long targetUid) {
        Party party = requireLocalOwner(leaderUid);
        if (party == null) {
            Party remote = getByUid(leaderUid);
            if (remote == null) {
                return PartyResult.of(PartyResultCode.NOT_IN_PARTY, null);
            }
            if (!isOwner(remote)) {
                return PartyResult.of(PartyResultCode.NOT_OWNER_NODE, remote);
            }
            return PartyResult.of(PartyResultCode.NOT_IN_PARTY, null);
        }
        if (party.leaderUid() != leaderUid) {
            return PartyResult.of(PartyResultCode.NOT_LEADER, party);
        }
        if (resolveParty(targetUid) != null) {
            return PartyResult.of(PartyResultCode.TARGET_BUSY, party);
        }
        if (party.memberUids().size() >= MAX_MEMBERS) {
            return PartyResult.of(PartyResultCode.PARTY_FULL, party);
        }
        List<Long> members = new ArrayList<>(party.memberUids());
        members.add(targetUid);
        Party updated = party.withMembers(party.leaderUid(), members);
        if (!redisPartyStore.saveIfNewer(updated)) {
            return PartyResult.of(PartyResultCode.VERSION_CONFLICT, party);
        }
        parties.put(party.partyId(), updated);
        uidToParty.put(targetUid, party.partyId());
        return PartyResult.of(PartyResultCode.OK, updated);
    }

    public record InviteManyResult(int invited, int skipped, PartyResultCode code, Party party) {}

    /**
     * 一键邀请多人；队长侧 5s 冷却，冷却中全部计入 skipped。
     */
    public InviteManyResult inviteMany(long leaderUid, List<Long> targets) {
        if (targets == null || targets.isEmpty()) {
            return new InviteManyResult(0, 0, PartyResultCode.OK, getByUid(leaderUid));
        }
        long now = System.currentTimeMillis();
        Long last = inviteManyCooldown.get(leaderUid);
        if (last != null && now - last < INVITE_MANY_COOLDOWN_MS) {
            return new InviteManyResult(0, targets.size(), PartyResultCode.OK, getByUid(leaderUid));
        }
        inviteManyCooldown.put(leaderUid, now);
        int invited = 0;
        int skipped = 0;
        Party lastParty = getByUid(leaderUid);
        for (Long target : targets) {
            if (target == null || target <= 0 || target == leaderUid) {
                skipped++;
                continue;
            }
            PartyResult r = invite(leaderUid, target);
            if (r.code() == PartyResultCode.OK) {
                invited++;
                lastParty = r.party();
            } else {
                skipped++;
                if (r.party() != null) {
                    lastParty = r.party();
                }
                if (r.code() == PartyResultCode.PARTY_FULL
                        || r.code() == PartyResultCode.NOT_LEADER
                        || r.code() == PartyResultCode.NOT_IN_PARTY
                        || r.code() == PartyResultCode.NOT_OWNER_NODE) {
                    break;
                }
            }
        }
        return new InviteManyResult(invited, skipped, PartyResultCode.OK, lastParty);
    }

    public PartyResult leave(long uid) {
        Party existing = getByUid(uid);
        if (existing == null) {
            uidToParty.remove(uid);
            redisPartyStore.clearUid(uid);
            return PartyResult.of(PartyResultCode.NOT_IN_PARTY, null);
        }
        if (!isOwner(existing)) {
            return PartyResult.of(PartyResultCode.NOT_OWNER_NODE, existing);
        }
        Party local = parties.get(existing.partyId());
        if (local == null) {
            parties.put(existing.partyId(), existing);
            for (Long m : existing.memberUids()) {
                uidToParty.put(m, existing.partyId());
            }
            local = existing;
        }
        String partyId = local.partyId();
        uidToParty.remove(uid);
        List<Long> members = new ArrayList<>(local.memberUids());
        members.remove(uid);
        if (members.isEmpty()) {
            parties.remove(partyId);
            redisPartyStore.remove(local);
            return PartyResult.of(PartyResultCode.OK, null);
        }
        long newLeader = local.leaderUid() == uid ? members.get(0) : local.leaderUid();
        Party updated = local.withMembers(newLeader, members);
        if (!redisPartyStore.saveIfNewer(updated)) {
            return PartyResult.of(PartyResultCode.VERSION_CONFLICT, local);
        }
        parties.put(partyId, updated);
        redisPartyStore.clearUid(uid);
        return PartyResult.of(PartyResultCode.OK, updated);
    }

    public PartyResult disband(long leaderUid) {
        Party party = getByUid(leaderUid);
        if (party == null) {
            return PartyResult.of(PartyResultCode.NOT_IN_PARTY, null);
        }
        if (!isOwner(party)) {
            return PartyResult.of(PartyResultCode.NOT_OWNER_NODE, party);
        }
        if (party.leaderUid() != leaderUid) {
            return PartyResult.of(PartyResultCode.NOT_LEADER, party);
        }
        for (Long member : party.memberUids()) {
            uidToParty.remove(member);
        }
        parties.remove(party.partyId());
        redisPartyStore.remove(party);
        return PartyResult.of(PartyResultCode.OK, null);
    }

    /** 权威节点心跳续期：刷新 lastHeartbeat 与 Redis TTL。 */
    public void heartbeatLocalParties() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Party> e : parties.entrySet()) {
            Party p = e.getValue();
            if (!isOwner(p)) {
                continue;
            }
            Party touched = p.touch();
            parties.put(p.partyId(), touched);
            redisPartyStore.renewLease(touched);
        }
        // 清理本地过期幽灵
        long leaseMs = redisPartyStore.leaseTtl().toMillis();
        parties.entrySet().removeIf(entry -> {
            Party p = entry.getValue();
            if (isOwner(p)) {
                return false;
            }
            return now - p.lastHeartbeatMs() > leaseMs * 2;
        });
    }

    public Party getByUid(long uid) {
        Party local = resolveLocal(uid);
        if (local != null) {
            return local;
        }
        return redisPartyStore.getByUid(uid);
    }

    private Party requireLocalOwner(long uid) {
        Party local = resolveLocal(uid);
        if (local != null && isOwner(local)) {
            return local;
        }
        return null;
    }

    private Party resolveParty(long uid) {
        return getByUid(uid);
    }

    private Party resolveLocal(long uid) {
        String partyId = uidToParty.get(uid);
        return partyId == null ? null : parties.get(partyId);
    }
}
