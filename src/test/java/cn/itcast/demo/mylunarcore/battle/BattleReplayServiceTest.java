package cn.itcast.demo.mylunarcore.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BattleReplayService 重连快进")
class BattleReplayServiceTest {

    @Test
    void reconnectPacketContainsMissedTicks() {
        BattleSnapshotService snaps = mock(BattleSnapshotService.class);
        when(snaps.load(9L)).thenReturn(Optional.empty());
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);

        BattleReplayService replay = new BattleReplayService(new ObjectMapper(), snaps, redis);
        replay.append(9L, 1, "SKILL", java.util.Map.of("skillId", 101));
        replay.append(9L, 2, "SKILL", java.util.Map.of("skillId", 202));
        replay.markSeen(9L, 1, 1L);

        Optional<BattleReplayService.ReplayPacket> pkt = replay.buildReconnectPacket(9L, 1);
        assertTrue(pkt.isPresent());
        assertEquals(1, pkt.get().actions().size());
        assertEquals(2L, pkt.get().actions().get(0).seq());
        assertEquals(2, pkt.get().actions().get(0).actorPlayerId());
    }
}
