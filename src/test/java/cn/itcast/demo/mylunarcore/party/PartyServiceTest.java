package cn.itcast.demo.mylunarcore.party;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PartyService 组队雏形测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PartyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PartyService 组队雏形测试")
class PartyServiceTest {

    private PartyService partyService;

    @BeforeEach
    void setUp() {
        RedisPartyStore store = org.mockito.Mockito.mock(RedisPartyStore.class);
        org.mockito.Mockito.when(store.available()).thenReturn(false);
        org.mockito.Mockito.when(store.saveIfNewer(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
        partyService = new PartyService(store);
    }

    /**
     * 验证点：创建并邀请队员。
     * <p>测试方法 {@code createAndInvite}：
     * <ul>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, created.code());}</li>
     *   <li>{@code assertNotNull(created.party());}</li>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, invited.code());}</li>
     *   <li>{@code assertTrue(invited.party().contains(2L));}</li>
     *   <li>{@code assertEquals(1L, invited.party().leaderUid());}</li>
     * </ul>
     */
    @Test
    @DisplayName("创建并邀请队员")
    void createAndInvite() {
        PartyService.PartyResult created = partyService.create(1L);
        assertEquals(PartyService.PartyResultCode.OK, created.code());
        assertNotNull(created.party());

        PartyService.PartyResult invited = partyService.invite(1L, 2L);
        assertEquals(PartyService.PartyResultCode.OK, invited.code());
        assertTrue(invited.party().contains(2L));
        assertEquals(1L, invited.party().leaderUid());
    }

    /**
     * 验证点：队长解散后成员应离队。
     * <p>测试方法 {@code disbandClearsMembers}：
     * <ul>
     *   <li>{@code assertEquals(PartyService.PartyResultCode.OK, disbanded.code());}</li>
     *   <li>{@code assertNull(partyService.getByUid(10L));}</li>
     *   <li>{@code assertNull(partyService.getByUid(11L));}</li>
     * </ul>
     */
    @Test
    @DisplayName("队长解散后成员应离队")
    void disbandClearsMembers() {
        partyService.create(10L);
        partyService.invite(10L, 11L);
        PartyService.PartyResult disbanded = partyService.disband(10L);
        assertEquals(PartyService.PartyResultCode.OK, disbanded.code());
        assertNull(partyService.getByUid(10L));
        assertNull(partyService.getByUid(11L));
    }
}
