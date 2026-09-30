package cn.itcast.demo.mylunarcore.skin; // 皮肤拥有判定与发放逻辑所在包

import cn.itcast.demo.mylunarcore.model.GameItemEntity; // 背包道具实体，用于内存侧写入皮肤道具
import cn.itcast.demo.mylunarcore.model.PlayerData; // 在线玩家聚合数据，含内存背包列表
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService; // 在线玩家事务提交入口，保证内存与落库一致
import cn.itcast.demo.mylunarcore.repo.ItemRepository; // 道具仓储，查询/写入玩家皮肤道具
import org.springframework.stereotype.Service; // 注册为 Spring 业务服务

import java.sql.Timestamp; // 构造道具创建/更新时间戳
import java.util.ArrayList; // 可变列表，保证可向内存背包追加道具
import java.util.List; // 道具集合类型
import java.util.function.Function; // 聚合 commit 回调签名

/**
 * 皮肤拥有判定服务：默认皮肤视为永久拥有；付费皮肤以背包中 type=SKIN 的道具为准。
 * 同时负责创角授予默认皮肤、幂等发放付费皮肤，以及在线/离线两种写入路径。
 */
@Service // 注册为 Spring 单例服务，供穿戴与发奖链路注入
public class SkinOwnershipService {

    /**
     * 皮肤道具类型常量，与策划文档 type=SKIN / 数值 7 对齐，写入背包时使用。
     */
    public static final int SKIN_ITEM_TYPE = 7;

    /**
     * 皮肤静态配置仓储，按 skinId / itemId / avatarId 查询配置。
     */
    private final SkinConfigRepository skinConfigRepository;
    /**
     * 道具仓储，判断玩家是否已有未丢弃的皮肤道具，以及落库新增道具。
     */
    private final ItemRepository itemRepository;
    /**
     * 玩家聚合服务：玩家在线时通过 commit 在内存聚合内发道具，避免只写库不同步内存。
     */
    private final PlayerAggregateService playerAggregateService;

    /**
     * 构造注入皮肤配置、道具仓储与玩家聚合依赖。
     *
     * @param skinConfigRepository  皮肤配置来源
     * @param itemRepository        道具读写
     * @param playerAggregateService 在线聚合提交
     */
    public SkinOwnershipService(SkinConfigRepository skinConfigRepository,
                                ItemRepository itemRepository,
                                PlayerAggregateService playerAggregateService) {
        this.skinConfigRepository = skinConfigRepository; // 保存配置仓储引用
        this.itemRepository = itemRepository; // 保存道具仓储引用
        this.playerAggregateService = playerAggregateService; // 保存聚合服务引用
    } // 构造结束

    /**
     * 判断玩家是否拥有指定皮肤：配置无效或未启用返回 false；默认皮肤恒 true；否则查背包是否有对应 itemId。
     *
     * @param playerId 玩家 ID
     * @param skinId   皮肤配置 ID
     * @return true 表示已拥有可用皮肤
     */
    public boolean owns(int playerId, int skinId) {
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.find(skinId); // 按 skinId 取静态配置
        if (skin == null || !skin.isEnabled()) { // 无配置或已下架则视为不拥有
            return false; // 不可穿戴/展示为拥有
        } // 配置有效性判断结束
        if (skin.isDefault()) { // 默认外观不依赖背包道具
            return true; // 所有玩家永久拥有默认皮肤
        } // 默认皮肤判断结束
        return itemRepository.existsActiveItemByItemId(playerId, skin.itemId()); // 付费皮肤以背包活跃道具为准
    } // owns 结束

    /**
     * 按道具 ID 判断是否拥有对应皮肤：先用 itemId 反查皮肤配置，再复用 owns 规则。
     *
     * @param playerId 玩家 ID
     * @param itemId   皮肤道具配置 ID
     * @return true 表示该道具对应的皮肤已拥有
     */
    public boolean ownsItem(int playerId, int itemId) {
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(itemId); // 道具 ID 反查皮肤
        if (skin == null) { // 非皮肤道具或配置缺失
            return false; // 无法判定为拥有皮肤
        } // 反查结果判断结束
        return owns(playerId, skin.skinId()); // 走统一拥有规则（含默认皮）
    } // ownsItem 结束

    /**
     * 判断某个道具配置 ID 是否属于皮肤道具（配置表中存在 itemId 映射）。
     *
     * @param itemId 道具配置 ID
     * @return true 表示该 itemId 绑定了皮肤配置
     */
    public boolean isSkinItem(int itemId) {
        return skinConfigRepository.findByItemId(itemId) != null; // 有映射即视为皮肤道具
    } // isSkinItem 结束

    /**
     * 创角时授予该角色全部已启用的默认皮肤道具，并返回应穿戴的默认 skinId；无默认配置则返回 0。
     *
     * @param playerId 新创角色所属玩家
     * @param avatarId 角色 ID
     * @return 默认皮肤 skinId，无则 0
     */
    public int grantDefaultSkins(int playerId, int avatarId) {
        SkinConfigRepository.SkinConfig defaultSkin = skinConfigRepository.findDefault(avatarId); // 取该角色主默认皮肤
        for (SkinConfigRepository.SkinConfig skin : skinConfigRepository.listByAvatarId(avatarId)) { // 遍历该角色全部皮肤配置
            if (skin == null || !skin.isEnabled() || !skin.isDefault()) { // 跳过空、下架、非默认
                continue; // 只处理可启用的默认皮肤
            } // 过滤条件结束
            ensureOwnedItem(playerId, skin, "create"); // 幂等确保背包有对应皮肤道具（默认皮内部会跳过落库）
        } // 默认皮肤遍历结束
        return defaultSkin == null ? 0 : defaultSkin.skinId(); // 返回创角后应装备的默认 skinId
    } // grantDefaultSkins 结束

    /**
     * 幂等授予皮肤道具：已拥有（含默认皮）则跳过，避免重复堆叠。
     *
     * @param playerId 玩家 ID
     * @param itemId   皮肤道具配置 ID
     * @param source   发放来源标记（如商店、活动），供 ensureOwnedItem 链路透传
     * @return true 表示本次新发放；false 表示已拥有、非皮肤或配置无效
     */
    public boolean markOwned(int playerId, int itemId, String source) {
        SkinConfigRepository.SkinConfig skin = skinConfigRepository.findByItemId(itemId); // 按道具反查皮肤
        if (skin == null || !skin.isEnabled()) { // 非皮肤或已下架，不发放
            return false; // 本次未发放
        } // 配置校验结束
        if (skin.isDefault() || itemRepository.existsActiveItemByItemId(playerId, skin.itemId())) { // 默认皮或已有道具则不必再发
            return false; // 视为已拥有，返回未新发
        } // 重复拥有判断结束
        return ensureOwnedItem(playerId, skin, source) > 0; // 实际写入成功则返回 true
    } // markOwned 结束

    /**
     * 在线聚合内授予皮肤道具，并同步写入内存背包；默认皮或已拥有时返回 0。
     *
     * @param data     在线玩家聚合数据
     * @param playerId 玩家 ID
     * @param skin     待发放皮肤配置
     * @return 新道具主键 id；已拥有或失败返回 0
     */
    public long grantSkinItemOnline(PlayerData data, int playerId, SkinConfigRepository.SkinConfig skin) {
        if (skin == null || skin.isDefault()) { // 无配置或默认皮不落道具行
            return 0L; // 不发放
        } // 默认/空配置判断结束
        if (hasItemInMemory(data, skin.itemId()) // 内存背包已有该 itemId
                || itemRepository.existsActiveItemByItemId(playerId, skin.itemId())) { // 或库中已有活跃道具
            return 0L; // 幂等：避免重复发放
        } // 已拥有判断结束
        long id = itemRepository.addSimpleItem(playerId, skin.itemId(), SKIN_ITEM_TYPE, 1L); // 落库新增数量为 1 的皮肤道具
        appendSkinItem(data, playerId, id, skin.itemId()); // 同步追加到内存背包，避免客户端需重载
        return id; // 返回新道具主键，供上层判断是否新发成功
    } // grantSkinItemOnline 结束

    /**
     * 确保玩家拥有指定皮肤道具：优先走在线聚合发放；玩家不在线则直接写库。
     * 默认皮肤不写道具行，返回 0。
     *
     * @param playerId 玩家 ID
     * @param skin     皮肤配置
     * @param source   发放来源（当前实现未落审计字段，保留调用语义）
     * @return 新道具 id；已拥有/默认皮/失败为 0
     */
    private long ensureOwnedItem(int playerId, SkinConfigRepository.SkinConfig skin, String source) {
        if (skin == null || skin.isDefault()) { // 默认皮不依赖背包道具行
            return 0L; // 无需写入
        } // 默认皮判断结束
        if (itemRepository.existsActiveItemByItemId(playerId, skin.itemId())) { // 库中已有则跳过
            return 0L; // 幂等短路
        } // 库存在判断结束
        Long online = playerAggregateService.commit(playerId, // 尝试在玩家在线聚合内发放
                (Function<PlayerData, Long>) data ->
                        grantSkinItemOnline(data, playerId, skin)); // 在线回调：写库并同步内存
        if (online != null) { // commit 非 null 表示玩家在线且回调已执行
            return online; // 返回在线发放结果（可能为 0 表示回调内判定已拥有）
        } // 在线路径结束
        return itemRepository.addSimpleItem(playerId, skin.itemId(), SKIN_ITEM_TYPE, 1L); // 离线：仅落库新增
    } // ensureOwnedItem 结束

    /**
     * 检查内存背包中是否已有未丢弃的指定 itemId 道具。
     *
     * @param data   玩家聚合数据
     * @param itemId 道具配置 ID
     * @return true 表示内存中已存在该道具
     */
    private static boolean hasItemInMemory(PlayerData data, int itemId) {
        if (data == null || data.getItems() == null) { // 无聚合或无背包列表
            return false; // 视为内存中不存在
        } // 空数据判断结束
        for (GameItemEntity item : data.getItems()) { // 扫描内存道具列表
            if (item != null && item.getItemId() == itemId && !item.isDiscarded()) { // 匹配配置 ID 且未丢弃
                return true; // 已拥有
            } // 单条匹配结束
        } // 列表扫描结束
        return false; // 未找到
    } // hasItemInMemory 结束

    /**
     * 将新发放的皮肤道具实体追加到玩家内存背包；必要时把不可变列表换成 ArrayList。
     *
     * @param data     玩家聚合数据
     * @param playerId 玩家 ID
     * @param id       新道具主键，&lt;=0 时不写入
     * @param itemId   道具配置 ID
     */
    private static void appendSkinItem(PlayerData data, int playerId, long id, int itemId) {
        if (id <= 0 || data == null) { // 无效主键或无聚合则跳过内存同步
            return; // 仅库写入成功但内存无需/无法更新
        } // 入参校验结束
        List<GameItemEntity> items = data.getItems(); // 取当前内存背包引用
        if (items == null) { // 背包尚未初始化
            items = new ArrayList<>(); // 新建可变列表
            data.setItems(items); // 写回聚合
        } else if (!(items instanceof ArrayList)) { // 可能是不可变 List.copyOf 结果
            items = new ArrayList<>(items); // 拷贝为可追加的 ArrayList
            data.setItems(items); // 替换聚合内引用
        } // 列表可变性处理结束
        GameItemEntity entity = new GameItemEntity(); // 构造与库记录对应的内存实体
        entity.setId(id); // 主键与落库一致
        entity.setPlayerId(playerId); // 归属玩家
        entity.setItemId(itemId); // 道具配置 ID
        entity.setType(SKIN_ITEM_TYPE); // 标记为皮肤类型
        entity.setCount(1L); // 皮肤通常数量为 1
        entity.setLevel(1); // 默认等级
        Timestamp now = new Timestamp(System.currentTimeMillis()); // 当前时间作为创建/更新时间
        entity.setCreatedAt(now); // 创建时间
        entity.setUpdatedAt(now); // 更新时间
        items.add(entity); // 追加到内存背包，供后续拥有判定与同步推送使用
    } // appendSkinItem 结束
}
