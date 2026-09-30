package cn.itcast.demo.mylunarcore.party;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 权威节点定期续期组队 Redis 租约；宕机后依赖 TTL 过期清理。
 */
@Component
public class PartyLeaseHeartbeatJob {

    private final PartyService partyService;

    public PartyLeaseHeartbeatJob(PartyService partyService) {
        this.partyService = partyService;
    }

    @Scheduled(fixedDelayString = "${lunarcore.party.lease-heartbeat-ms:30000}")
    public void tick() {
        partyService.heartbeatLocalParties();
    }
}
