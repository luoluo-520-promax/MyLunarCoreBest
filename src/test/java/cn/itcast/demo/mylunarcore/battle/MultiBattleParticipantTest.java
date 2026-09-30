package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多人共战：{@link BattleContext} 参战者索引可被 {@link BattleManager#findActiveByPlayerId} 命中；
 * {@link PartyService} 在 Redis 不可用时用本地组队，供开战扇入成员列表。
 */
@DisplayName("多人共战参与者索引测试")
class MultiBattleParticipantTest {

    /**
     * 房主 10 开战后 addParticipant(20/30)，manager 对 10/20/30 均返回同一 ctx；
     * participant 集合大小为 3（含房主）。
     */
    @Test
    @DisplayName("参战成员应被 findActiveByPlayerId 命中")
    void participantVisibleInBattleManager() {
        BattleContext ctx = BattleContext.createNew(99L, 10, 1, 100, 1L, List.of());
        ctx.addParticipant(20);
        ctx.addParticipant(30);

        BattleManager manager = new BattleManager(org.mockito.Mockito.mock(BattleSnapshotService.class));
        manager.put(ctx);

        assertEquals(ctx, manager.findActiveByPlayerId(10));
        assertEquals(ctx, manager.findActiveByPlayerId(20));
        assertEquals(ctx, manager.findActiveByPlayerId(30));
        assertTrue(ctx.isParticipant(20));
        assertEquals(3, ctx.getParticipantPlayerIds().size());
    }

    /**
     * mock Redis 不可用 → 走内存 Party：create+两次 invite 后成员 3 人且含 101。
     */
    @Test
    @DisplayName("Party 成员列表可供开战扇入")
    void partyMembersAvailableForEnroll() {
        RedisPartyStore store = mock(RedisPartyStore.class);
        when(store.available()).thenReturn(false);
        PartyService partyService = new PartyService(store);
        assertEquals(PartyService.PartyResultCode.OK, partyService.create(100L).code());
        assertEquals(PartyService.PartyResultCode.OK, partyService.invite(100L, 101L).code());
        assertEquals(PartyService.PartyResultCode.OK, partyService.invite(100L, 102L).code());
        PartyService.Party party = partyService.getByUid(100L);
        assertEquals(3, party.memberUids().size());
        assertTrue(party.contains(101L));
    }
}
