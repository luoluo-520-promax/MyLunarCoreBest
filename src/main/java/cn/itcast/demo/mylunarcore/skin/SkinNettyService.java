package cn.itcast.demo.mylunarcore.skin; // 皮肤 Netty 协议适配层所在包

import cn.itcast.demo.mylunarcore.player.DataChangeScope; // 数据变更范围，穿戴成功后推送 AVATARS
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver; // 从 Channel 解析 playerId / uid
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService; // 通知客户端玩家数据已变更
import cn.itcast.demo.mylunarcore.protocol.SkinSystemProto; // 皮肤系统 protobuf 请求/响应
import io.netty.channel.Channel; // 客户端连接，用于鉴权解析玩家身份
import org.springframework.stereotype.Service; // 注册为协议处理服务

/**
 * 皮肤协议适配服务：将衣柜查询与穿戴的 CsReq 转为应用服务调用，并组装 ScRsp；
 * 穿戴成功时触发角色数据变更推送。
 */
@Service // Spring 管理，供 Netty 业务分发注入
public class SkinNettyService {

    /**
     * 皮肤穿戴与衣柜应用服务，承载业务校验与写库。
     */
    private final SkinEquipApplicationService equipService;
    /**
     * 从 Channel 解析登录态中的玩家 ID 与 UID。
     */
    private final PlayerContextResolver contextResolver;
    /**
     * 玩家数据同步服务，穿戴成功后通知客户端刷新角色外观。
     */
    private final PlayerDataSyncService playerDataSyncService;

    /**
     * 构造注入穿戴服务、上下文解析与数据同步依赖。
     *
     * @param equipService           穿戴/衣柜业务
     * @param contextResolver        Channel → 玩家身份
     * @param playerDataSyncService  数据变更推送
     */
    public SkinNettyService(SkinEquipApplicationService equipService,
                            PlayerContextResolver contextResolver,
                            PlayerDataSyncService playerDataSyncService) {
        this.equipService = equipService; // 保存应用服务
        this.contextResolver = contextResolver; // 保存身份解析
        this.playerDataSyncService = playerDataSyncService; // 保存同步服务
    } // 构造结束

    /**
     * 处理衣柜查询请求：未登录返回 retcode=1；业务失败透传 retcode；成功则填充皮肤条目列表。
     *
     * @param req     客户端衣柜查询请求（含 avatarId）
     * @param channel 当前连接
     * @return 衣柜查询响应
     */
    public SkinSystemProto.GetSkinWardrobeScRsp handleGetSkinWardrobe(
            SkinSystemProto.GetSkinWardrobeCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 从连接上下文取玩家 ID
        if (playerId <= 0) { // 未登录或会话无效
            return SkinSystemProto.GetSkinWardrobeScRsp.newBuilder().setRetcode(1).build(); // retcode=1 表示未授权
        } // 登录校验结束
        SkinEquipApplicationService.WardrobeResult result =
                equipService.getWardrobe(playerId, req.getAvatarId()); // 查询该角色衣柜
        if (!result.success()) { // 如无角色等业务失败
            return SkinSystemProto.GetSkinWardrobeScRsp.newBuilder()
                    .setRetcode(result.retcode()) // 透传应用层错误码（如 2）
                    .setAvatarId(req.getAvatarId()) // 回显请求角色，便于客户端对齐
                    .build(); // 不附带皮肤列表
        } // 业务失败分支结束
        SkinSystemProto.GetSkinWardrobeScRsp.Builder builder =
                SkinSystemProto.GetSkinWardrobeScRsp.newBuilder()
                        .setRetcode(0) // 成功
                        .setAvatarId(result.avatarId()) // 角色 ID
                        .setEquippedSkinId(result.equippedSkinId()); // 当前装备皮肤（默认可为 0）
        for (SkinEquipApplicationService.WardrobeEntry entry : result.skins()) { // 将业务条目转为 protobuf
            builder.addSkins(SkinSystemProto.SkinWardrobeEntry.newBuilder()
                    .setSkinId(entry.skinId()) // 皮肤 ID
                    .setOwned(entry.owned()) // 是否已拥有
                    .setEquipped(entry.equipped()) // 是否当前穿戴
                    .setName(nullToEmpty(entry.name())) // 名称，null 转空串避免 protobuf 异常
                    .setRarity(entry.rarity()) // 稀有度
                    .setResourceKey(nullToEmpty(entry.resourceKey())) // 资源键
                    .setPreviewIcon(nullToEmpty(entry.previewIcon())) // 预览图
                    .setObtainTips(nullToEmpty(entry.obtainTips())) // 获取提示
                    .setIsDefault(entry.isDefault()) // 是否默认皮
                    .build()); // 完成单条 SkinWardrobeEntry
        } // 条目转换循环结束
        return builder.build(); // 返回完整衣柜响应
    } // handleGetSkinWardrobe 结束

    /**
     * 处理穿戴请求：未登录 retcode=1；成功则通知 UID 对应客户端刷新角色数据，并回传装备结果。
     *
     * @param req     客户端穿戴请求（avatarId + skinId）
     * @param channel 当前连接
     * @return 穿戴响应
     */
    public SkinSystemProto.EquipSkinScRsp handleEquipSkin(
            SkinSystemProto.EquipSkinCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析玩家 ID
        if (playerId <= 0) { // 未登录
            return SkinSystemProto.EquipSkinScRsp.newBuilder().setRetcode(1).build(); // 未授权
        } // 登录校验结束
        SkinEquipApplicationService.EquipResult result =
                equipService.equip(playerId, req.getAvatarId(), req.getSkinId()); // 执行穿戴/还原默认
        if (result.success()) { // 仅成功时推送，避免失败刷屏
            contextResolver.resolveUid(channel).ifPresent(uid ->
                    playerDataSyncService.notifyDataChanged(uid, DataChangeScope.AVATARS)); // 通知客户端角色外观可能变化
        } // 成功推送分支结束
        return SkinSystemProto.EquipSkinScRsp.newBuilder()
                .setRetcode(result.retcode()) // 0 成功，或 2/3/4 等业务错误
                .setAvatarId(result.avatarId()) // 操作角色
                .setEquippedSkinId(result.equippedSkinId()) // 最终装备皮肤（失败时多为当前值）
                .build(); // 组装响应
    } // handleEquipSkin 结束

    /**
     * 将可能为 null 的字符串转为空串，保证 protobuf setter 安全。
     *
     * @param value 原始字符串
     * @return 非 null 字符串
     */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value; // null 用空串占位
    } // nullToEmpty 结束
}
