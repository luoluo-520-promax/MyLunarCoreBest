package cn.itcast.demo.mylunarcore.skin; // 皮肤业务单元测试所在包

import cn.itcast.demo.mylunarcore.model.AvatarEntity; // 构造测试用角色实体
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService; // Mock 在线聚合，强制走离线路径
import cn.itcast.demo.mylunarcore.repo.AvatarRepository; // Mock 角色仓储
import cn.itcast.demo.mylunarcore.repo.ItemRepository; // Mock 道具仓储
import org.junit.jupiter.api.BeforeEach; // 每个用例前重置 Mock 与服务实例
import org.junit.jupiter.api.DisplayName; // 中文用例标题
import org.junit.jupiter.api.Test; // 标记测试方法

import java.util.List; // 构造皮肤配置列表
import java.util.function.Function; // commit 回调类型匹配

import static org.junit.jupiter.api.Assertions.assertEquals; // 断言相等
import static org.junit.jupiter.api.Assertions.assertFalse; // 断言为假
import static org.junit.jupiter.api.Assertions.assertTrue; // 断言为真
import static org.mockito.ArgumentMatchers.any; // 任意参数匹配
import static org.mockito.ArgumentMatchers.anyLong; // 任意 long（playerId）
import static org.mockito.ArgumentMatchers.eq; // 精确匹配参数
import static org.mockito.Mockito.mock; // 创建 Mock 对象
import static org.mockito.Mockito.never; // 验证某方法未被调用
import static org.mockito.Mockito.verify; // 验证交互
import static org.mockito.Mockito.when; // 桩返回值

/**
 * SkinOwnershipService 与 SkinEquipApplicationService 的单元测试：
 * 覆盖默认皮拥有、付费皮背包判定、未拥有穿戴失败、已拥有穿戴成功、还原默认与衣柜标记。
 */
@DisplayName("SkinOwnershipService / SkinEquipApplicationService 皮肤拥有与穿戴测试")
class SkinApplicationServiceTest {

    /**
     * 测试玩家 ID。
     */
    private static final int PLAYER_ID = 1001;
    /**
     * 测试角色 ID，与皮肤配置中的 avatarId 一致。
     */
    private static final int AVATAR_ID = 1001;

    /**
     * 皮肤配置 Mock。
     */
    private SkinConfigRepository skinConfigRepository;
    /**
     * 道具仓储 Mock，用于付费皮拥有判定与写库验证。
     */
    private ItemRepository itemRepository;
    /**
     * 聚合服务 Mock；commit 返回 null 强制走离线写库路径。
     */
    private PlayerAggregateService playerAggregateService;
    /**
     * 角色仓储 Mock。
     */
    private AvatarRepository avatarRepository;
    /**
     * 被测拥有判定服务。
     */
    private SkinOwnershipService ownershipService;
    /**
     * 被测穿戴应用服务。
     */
    private SkinEquipApplicationService equipService;

    /**
     * 角色默认皮肤配置样例（isDefault=true）。
     */
    private SkinConfigRepository.SkinConfig defaultSkin;
    /**
     * 付费皮肤配置样例（isDefault=false）。
     */
    private SkinConfigRepository.SkinConfig paidSkin;

    /**
     * 每个用例前：重建 Mock、桩配置查询，并实例化两个被测服务。
     */
    @BeforeEach
    void setUp() {
        skinConfigRepository = mock(SkinConfigRepository.class); // Mock 配置仓储
        itemRepository = mock(ItemRepository.class); // Mock 道具仓储
        playerAggregateService = mock(PlayerAggregateService.class); // Mock 聚合
        avatarRepository = mock(AvatarRepository.class); // Mock 角色仓储
        when(playerAggregateService.commit(anyLong(), any(Function.class))).thenReturn(null); // 模拟玩家不在线

        defaultSkin = new SkinConfigRepository.SkinConfig( // 构造默认皮
                8001001, AVATAR_ID, 8101001, "默认外观", 3, true, // skinId/avatar/item/名称/稀有度/默认
                "avatar/1001/skin_default", "ui/skin/8001001", // 资源键与预览图
                List.of("DEFAULT"), "", true); // 标签、获取提示、启用
        paidSkin = new SkinConfigRepository.SkinConfig( // 构造付费皮
                8001002, AVATAR_ID, 8101002, "星海礼赞", 5, false, // 非默认、更高稀有度
                "avatar/1001/skin_star_ocean", "ui/skin/8001002",
                List.of("LIMITED"), "皮肤商店", true); // 限时标签与商店获取提示

        when(skinConfigRepository.find(8001001)).thenReturn(defaultSkin); // 按 skinId 查默认皮
        when(skinConfigRepository.find(8001002)).thenReturn(paidSkin); // 按 skinId 查付费皮
        when(skinConfigRepository.findByItemId(8101001)).thenReturn(defaultSkin); // 道具反查默认皮
        when(skinConfigRepository.findByItemId(8101002)).thenReturn(paidSkin); // 道具反查付费皮
        when(skinConfigRepository.findDefault(AVATAR_ID)).thenReturn(defaultSkin); // 角色默认皮
        when(skinConfigRepository.listByAvatarId(AVATAR_ID)).thenReturn(List.of(defaultSkin, paidSkin)); // 该角色全部皮
        when(skinConfigRepository.listEnabledByAvatarId(AVATAR_ID)).thenReturn(List.of(defaultSkin, paidSkin)); // 启用列表

        ownershipService = new SkinOwnershipService(skinConfigRepository, itemRepository, playerAggregateService); // 注入被测拥有服务
        equipService = new SkinEquipApplicationService(
                skinConfigRepository, ownershipService, avatarRepository, playerAggregateService); // 注入被测穿戴服务
    } // setUp 结束

    /**
     * 默认皮肤应始终视为已拥有，且不应查询背包道具。
     */
    @Test
    @DisplayName("默认皮肤应始终视为已拥有")
    void ownsShouldTreatDefaultAsOwned() {
        assertTrue(ownershipService.owns(PLAYER_ID, 8001001)); // 默认皮 owns 必须为 true
        verify(itemRepository, never()).existsActiveItemByItemId(PLAYER_ID, 8101001); // 不应查默认皮道具
    } // ownsShouldTreatDefaultAsOwned 结束

    /**
     * 付费皮肤拥有完全依赖背包是否存在对应 itemId 的活跃道具。
     */
    @Test
    @DisplayName("付费皮肤以背包道具判定拥有")
    void ownsShouldCheckBagForPaidSkin() {
        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 8101002)).thenReturn(true); // 模拟已拥有
        assertTrue(ownershipService.owns(PLAYER_ID, 8001002)); // 应返回 true

        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 8101002)).thenReturn(false); // 模拟未拥有
        assertFalse(ownershipService.owns(PLAYER_ID, 8001002)); // 应返回 false
    } // ownsShouldCheckBagForPaidSkin 结束

    /**
     * 未拥有付费皮肤时穿戴必须失败，retcode=4，且不得调用 updateEquippedSkin。
     */
    @Test
    @DisplayName("未拥有皮肤穿戴应失败 retcode=4")
    void equipShouldFailWhenNotOwned() {
        AvatarEntity avatar = new AvatarEntity(); // 准备归属该玩家的角色
        avatar.setAvatarId(AVATAR_ID); // 角色 ID
        avatar.setEquippedSkinId(0); // 当前默认外观
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar); // 角色存在
        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 8101002)).thenReturn(false); // 未拥有付费皮

        SkinEquipApplicationService.EquipResult result = equipService.equip(PLAYER_ID, AVATAR_ID, 8001002); // 尝试穿戴

        assertFalse(result.success()); // 必须失败
        assertEquals(4, result.retcode()); // 未拥有错误码
        verify(avatarRepository, never()).updateEquippedSkin(eq(PLAYER_ID), eq(AVATAR_ID), org.mockito.ArgumentMatchers.anyInt()); // 禁止写库
    } // equipShouldFailWhenNotOwned 结束

    /**
     * 已拥有付费皮肤时穿戴应成功写库，equippedSkinId 为真实 skinId。
     */
    @Test
    @DisplayName("已拥有皮肤穿戴应写库成功")
    void equipShouldSucceedWhenOwned() {
        AvatarEntity avatar = new AvatarEntity(); // 角色实体
        avatar.setAvatarId(AVATAR_ID);
        avatar.setEquippedSkinId(0); // 当前未显式装备付费皮
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar); // 角色存在
        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 8101002)).thenReturn(true); // 已拥有
        when(avatarRepository.updateEquippedSkin(PLAYER_ID, AVATAR_ID, 8001002)).thenReturn(1); // 写库成功 1 行

        SkinEquipApplicationService.EquipResult result = equipService.equip(PLAYER_ID, AVATAR_ID, 8001002); // 穿戴

        assertTrue(result.success()); // 成功
        assertEquals(0, result.retcode()); // 成功码
        assertEquals(8001002, result.equippedSkinId()); // 回传付费 skinId
        verify(avatarRepository).updateEquippedSkin(PLAYER_ID, AVATAR_ID, 8001002); // 确认写库参数
    } // equipShouldSucceedWhenOwned 结束

    /**
     * skinId=0 应解析为默认皮肤 ID 并写库（本样例默认 skinId=8001001）。
     */
    @Test
    @DisplayName("skinId=0 应还原默认皮肤")
    void equipZeroShouldRestoreDefault() {
        AvatarEntity avatar = new AvatarEntity();
        avatar.setAvatarId(AVATAR_ID);
        avatar.setEquippedSkinId(8001002); // 当前穿着付费皮
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);
        when(avatarRepository.updateEquippedSkin(PLAYER_ID, AVATAR_ID, 8001001)).thenReturn(1); // 期望写入默认 skinId

        SkinEquipApplicationService.EquipResult result = equipService.equip(PLAYER_ID, AVATAR_ID, 0); // 传 0 还原

        assertTrue(result.success()); // 成功
        assertEquals(8001001, result.equippedSkinId()); // 结果应为默认皮 ID
    } // equipZeroShouldRestoreDefault 结束

    /**
     * 衣柜列表应把默认皮标为已拥有且当前穿戴，未拥有的付费皮 owned=false。
     */
    @Test
    @DisplayName("衣柜应标记默认已拥有与当前穿戴")
    void wardrobeShouldMarkOwnedAndEquipped() {
        AvatarEntity avatar = new AvatarEntity();
        avatar.setAvatarId(AVATAR_ID);
        avatar.setEquippedSkinId(0); // 库中 0 表示默认外观
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);
        when(itemRepository.existsActiveItemByItemId(PLAYER_ID, 8101002)).thenReturn(false); // 付费皮未拥有

        SkinEquipApplicationService.WardrobeResult result = equipService.getWardrobe(PLAYER_ID, AVATAR_ID); // 查衣柜

        assertTrue(result.success()); // 查询成功
        assertEquals(2, result.skins().size()); // 默认 + 付费两条
        SkinEquipApplicationService.WardrobeEntry def = result.skins().get(0); // 第一条为默认皮
        assertTrue(def.owned()); // 默认皮始终已拥有
        assertTrue(def.equipped()); // equipped=0 时默认皮视为穿戴中
        assertFalse(result.skins().get(1).owned()); // 付费皮未拥有
    } // wardrobeShouldMarkOwnedAndEquipped 结束
}
