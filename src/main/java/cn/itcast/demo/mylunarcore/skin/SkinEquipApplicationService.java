package cn.itcast.demo.mylunarcore.skin; // 皮肤穿戴与衣柜应用服务所在包

import cn.itcast.demo.mylunarcore.model.AvatarEntity; // 角色实体，含 equippedSkinId 字段
import cn.itcast.demo.mylunarcore.model.PlayerData; // 在线聚合，用于同步内存角色穿戴
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService; // 在线时在聚合事务内改穿戴
import cn.itcast.demo.mylunarcore.repo.AvatarRepository; // 角色仓储，查询归属并更新 equippedSkinId
import org.springframework.stereotype.Service; // 注册为 Spring 业务服务
import org.springframework.transaction.annotation.Transactional; // 离线穿戴路径包事务

import java.util.List; // 衣柜条目列表
import java.util.function.Function; // 聚合 commit 回调类型

/**
 * 皮肤穿戴/卸下应用服务：校验角色归属与皮肤拥有后写入 equippedSkinId，并提供衣柜列表查询。
 * retcode：0 成功，2 无角色，3 皮肤不存在或不匹配角色，4 未拥有。
 */
@Service // 注册为 Spring 单例，供 Netty 协议层调用
public class SkinEquipApplicationService {

    /**
     * 穿戴操作结果：是否成功、错误码、角色 ID、最终装备的 skinId（默认皮写库可为 0）。
     *
     * @param success         是否穿戴成功
     * @param retcode         协议错误码，0 表示成功
     * @param avatarId        操作的角色 ID
     * @param equippedSkinId  写入后的装备皮肤 ID
     */
    public record EquipResult(boolean success, int retcode, int avatarId, int equippedSkinId) {
        /**
         * 构造成功结果。
         *
         * @param avatarId       角色 ID
         * @param equippedSkinId 已写入的皮肤 ID
         * @return 成功 EquipResult
         */
        public static EquipResult ok(int avatarId, int equippedSkinId) {
            return new EquipResult(true, 0, avatarId, equippedSkinId); // retcode=0 表示成功
        } // ok 结束

        /**
         * 构造失败结果，附带当前应回显的装备皮肤 ID。
         *
         * @param retcode        业务错误码
         * @param avatarId       角色 ID
         * @param equippedSkinId 失败时回显的当前装备皮肤
         * @return 失败 EquipResult
         */
        public static EquipResult fail(int retcode, int avatarId, int equippedSkinId) {
            return new EquipResult(false, retcode, avatarId, equippedSkinId); // success=false，由 retcode 说明原因
        } // fail 结束
    } // EquipResult 结束

    /**
     * 衣柜单条展示数据：配置展示字段 + 是否拥有/是否当前穿戴。
     *
     * @param skinId      皮肤 ID
     * @param owned       玩家是否已拥有
     * @param equipped    是否当前穿戴（含 equipped=0 时默认皮视为穿戴）
     * @param name        展示名
     * @param rarity      稀有度
     * @param resourceKey 客户端资源键
     * @param previewIcon 预览图标
     * @param obtainTips  获取途径说明
     * @param isDefault   是否默认皮肤
     */
    public record WardrobeEntry(
            int skinId,
            boolean owned,
            boolean equipped,
            String name,
            int rarity,
            String resourceKey,
            String previewIcon,
            String obtainTips,
            boolean isDefault
    ) {
    } // WardrobeEntry 结束

    /**
     * 衣柜查询结果：成功时带当前装备皮肤与全部启用皮肤条目。
     *
     * @param success         查询是否成功
     * @param retcode         错误码，无角色为 2
     * @param avatarId        角色 ID
     * @param equippedSkinId  当前装备皮肤（库中 &lt;=0 规范化为 0）
     * @param skins           该角色启用中的皮肤列表
     */
    public record WardrobeResult(
            boolean success,
            int retcode,
            int avatarId,
            int equippedSkinId,
            List<WardrobeEntry> skins
    ) {
    } // WardrobeResult 结束

    /**
     * 皮肤静态配置仓储。
     */
    private final SkinConfigRepository skinConfigRepository;
    /**
     * 拥有判定服务，穿戴前校验玩家是否已有该皮肤。
     */
    private final SkinOwnershipService ownershipService;
    /**
     * 角色仓储，校验玩家拥有该角色并更新装备皮肤字段。
     */
    private final AvatarRepository avatarRepository;
    /**
     * 玩家聚合服务，在线时保证库与内存角色列表同时更新。
     */
    private final PlayerAggregateService playerAggregateService;

    /**
     * 构造注入配置、拥有判定、角色仓储与聚合服务。
     *
     * @param skinConfigRepository   皮肤配置
     * @param ownershipService       拥有判定
     * @param avatarRepository       角色读写
     * @param playerAggregateService 在线聚合提交
     */
    public SkinEquipApplicationService(SkinConfigRepository skinConfigRepository,
                                       SkinOwnershipService ownershipService,
                                       AvatarRepository avatarRepository,
                                       PlayerAggregateService playerAggregateService) {
        this.skinConfigRepository = skinConfigRepository; // 保存配置仓储
        this.ownershipService = ownershipService; // 保存拥有判定
        this.avatarRepository = avatarRepository; // 保存角色仓储
        this.playerAggregateService = playerAggregateService; // 保存聚合服务
    } // 构造结束

    /**
     * 查询指定角色的皮肤衣柜：校验角色归属后，组装每条皮肤的拥有与穿戴状态。
     *
     * @param playerId 玩家 ID
     * @param avatarId 角色 ID
     * @return 衣柜结果；无该角色时 retcode=2
     */
    public WardrobeResult getWardrobe(int playerId, int avatarId) {
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId); // 确认角色属于该玩家
        if (avatar == null) { // 角色不存在或不属于玩家
            return new WardrobeResult(false, 2, avatarId, 0, List.of()); // retcode=2，空列表
        } // 角色校验结束
        int equipped = resolveEquipped(avatar); // 规范化当前装备皮肤（&lt;=0 视为 0）
        List<WardrobeEntry> entries = new java.util.ArrayList<>(); // 可变列表收集衣柜条目
        for (SkinConfigRepository.SkinConfig skin : skinConfigRepository.listEnabledByAvatarId(avatarId)) { // 仅展示已启用皮肤
            boolean owned = ownershipService.owns(playerId, skin.skinId()); // 按配置规则判定是否拥有
            boolean isEquipped = skin.skinId() == equipped // 显式装备了该 skinId
                    || (equipped == 0 && skin.isDefault()); // 或库中为 0 时把默认皮标为当前穿戴
            entries.add(new WardrobeEntry( // 组装客户端展示字段
                    skin.skinId(), // 皮肤 ID
                    owned, // 是否拥有
                    isEquipped, // 是否穿戴中
                    skin.name(), // 名称
                    skin.rarity(), // 稀有度
                    skin.resourceKey(), // 资源键
                    skin.previewIcon(), // 预览图
                    skin.obtainTips(), // 获取提示
                    skin.isDefault())); // 是否默认
        } // 启用皮肤遍历结束
        return new WardrobeResult(true, 0, avatarId, equipped, List.copyOf(entries)); // 不可变副本返回
    } // getWardrobe 结束

    /**
     * 穿戴皮肤。skinId&lt;=0 时还原该角色默认皮肤；成功后写 equippedSkinId（默认皮可写 0）。
     * retcode：2 无角色，3 皮肤不存在/未启用/角色不匹配，4 未拥有。
     *
     * @param playerId 玩家 ID
     * @param avatarId 角色 ID
     * @param skinId   目标皮肤；&lt;=0 表示还原默认
     * @return 穿戴结果
     */
    public EquipResult equip(int playerId, int avatarId, int skinId) {
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId); // 校验角色归属
        if (avatar == null) { // 无角色
            return EquipResult.fail(2, avatarId, 0); // retcode=2
        } // 角色校验结束
        int targetSkinId; // 最终写入库的装备皮肤 ID
        if (skinId <= 0) { // 客户端传 0 或负数表示还原默认外观
            SkinConfigRepository.SkinConfig def = skinConfigRepository.findDefault(avatarId); // 查角色默认皮肤配置
            targetSkinId = def == null ? 0 : def.skinId(); // 无默认配置则写 0
        } else { // 指定皮肤 ID 穿戴
            SkinConfigRepository.SkinConfig skin = skinConfigRepository.find(skinId); // 按 ID 取配置
            if (skin == null || !skin.isEnabled() || skin.avatarId() != avatarId) { // 不存在、下架或绑定角色不符
                return EquipResult.fail(3, avatarId, resolveEquipped(avatar)); // retcode=3，回显当前装备
            } // 配置匹配校验结束
            if (!ownershipService.owns(playerId, skinId)) { // 未拥有不可穿
                return EquipResult.fail(4, avatarId, resolveEquipped(avatar)); // retcode=4
            } // 拥有校验结束
            targetSkinId = skin.isDefault() ? 0 : skin.skinId(); // 默认皮存 0，付费皮存真实 skinId
        } // 目标皮肤解析结束

        EquipResult online = playerAggregateService.commit(playerId, // 优先在线聚合路径
                (Function<PlayerData, EquipResult>) data ->
                        equipOnline(data, playerId, avatarId, targetSkinId)); // 写库并同步内存角色
        if (online != null) { // 非 null 表示玩家在线且已执行回调
            return online; // 直接返回在线结果
        } // 在线路径结束
        return equipOffline(playerId, avatarId, targetSkinId); // 离线仅更新数据库
    } // equip 结束

    /**
     * 在线穿戴：更新库中 equippedSkinId，并同步内存角色列表中的同名字段。
     *
     * @param data           在线玩家数据
     * @param playerId       玩家 ID
     * @param avatarId       角色 ID
     * @param equippedSkinId 待写入的装备皮肤
     * @return 穿戴结果；更新行数为 0 时视为角色不存在
     */
    private EquipResult equipOnline(PlayerData data, int playerId, int avatarId, int equippedSkinId) {
        if (avatarRepository.updateEquippedSkin(playerId, avatarId, equippedSkinId) <= 0) { // 影响行数为 0 表示未更新到角色
            return EquipResult.fail(2, avatarId, 0); // 当作无角色失败
        } // 写库失败判断结束
        syncMemoryAvatar(data, avatarId, equippedSkinId); // 同步内存中该角色的 equippedSkinId
        return EquipResult.ok(avatarId, equippedSkinId); // 返回成功
    } // equipOnline 结束

    /**
     * 离线穿戴：事务内仅更新数据库装备字段。
     *
     * @param playerId       玩家 ID
     * @param avatarId       角色 ID
     * @param equippedSkinId 待写入的装备皮肤
     * @return 穿戴结果
     */
    @Transactional // 保证离线更新在事务中提交
    protected EquipResult equipOffline(int playerId, int avatarId, int equippedSkinId) {
        if (avatarRepository.updateEquippedSkin(playerId, avatarId, equippedSkinId) <= 0) { // 未更新到行
            return EquipResult.fail(2, avatarId, 0); // 无角色或更新失败
        } // 写库失败判断结束
        return EquipResult.ok(avatarId, equippedSkinId); // 离线成功，无内存可同步
    } // equipOffline 结束

    /**
     * 将角色实体上的装备皮肤规范化：&lt;=0 统一视为未显式装备（默认外观，值为 0）。
     *
     * @param avatar 角色实体
     * @return 规范化后的 equippedSkinId
     */
    private static int resolveEquipped(AvatarEntity avatar) {
        return avatar.getEquippedSkinId() <= 0 ? 0 : avatar.getEquippedSkinId(); // 负数/0 均按 0 处理
    } // resolveEquipped 结束

    /**
     * 在内存角色列表中找到对应 avatarId，更新其 equippedSkinId，供后续数据同步推送。
     *
     * @param data           玩家聚合
     * @param avatarId       角色 ID
     * @param equippedSkinId 新装备皮肤
     */
    private static void syncMemoryAvatar(PlayerData data, int avatarId, int equippedSkinId) {
        if (data == null || data.getAvatars() == null) { // 无聚合或无角色列表则跳过
            return; // 仅库已更新
        } // 空数据判断结束
        for (AvatarEntity avatar : data.getAvatars()) { // 扫描内存角色
            if (avatar != null && avatar.getAvatarId() == avatarId) { // 命中目标角色
                avatar.setEquippedSkinId(equippedSkinId); // 同步穿戴字段
                return; // 找到即结束，角色 ID 唯一
            } // 单条匹配结束
        } // 角色列表扫描结束
    } // syncMemoryAvatar 结束
}
