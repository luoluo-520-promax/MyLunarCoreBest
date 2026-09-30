// 角色系统协议适配层：负责把 protobuf 请求转成应用服务调用，再组装响应包
package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.player.DataChangeScope; // 数据变更维度：创角/突破后通知客户端刷新 AVATARS
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver; // 从 Netty Channel 解析 playerId/uid
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService; // 向在线客户端推送数据变更通知
import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto; // 角色系统 protobuf 消息定义
import cn.itcast.demo.mylunarcore.repo.AvatarRepository; // 头像仓储：查询 avatar 是否属于当前玩家
import io.netty.channel.Channel; // Netty 连接通道，代表一个客户端 TCP 会话
import org.springframework.stereotype.Service; // 声明为 Spring 单例 Bean，供 PacketHandler 注入

/**
 * 角色系统 Netty 协议适配器。
 * 职责：解析登录态 → 调用 ApplicationService → 组装 protobuf 响应 → 必要时触发 AVATARS 同步。
 * 通用 retcode：1=未登录；各接口另有 2/3/4 等业务码。
 */
@Service // 注册到 Spring 容器，生命周期由容器管理
public class CharacterNettyService {

    /** 创角业务：昵称校验、初始 avatar 发放。 */
    private final CharacterCreationApplicationService creationService;
    /** 成长业务：经验累加、突破（promotion）。 */
    private final CharacterProgressionApplicationService progressionService;
    /** 面板属性公式：level/promotion/rank → hp/atk/def/spd。 */
    private final AttributeCalculator attributeCalculator;
    /** 头像仓储：GetAvatarAttributes 时校验 avatar 归属。 */
    private final AvatarRepository avatarRepository;
    /** 从 Channel 解析 playerId / uid，拦截未登录请求。 */
    private final PlayerContextResolver contextResolver;
    /** 创角/突破成功后通知客户端刷新 AVATARS 维度。 */
    private final PlayerDataSyncService playerDataSyncService;
    /** 天赋升级与列表查询。 */
    private final TalentApplicationService talentApplicationService;

    /** 构造器注入角色协议层所需的七个依赖。 */
    public CharacterNettyService(CharacterCreationApplicationService creationService,
                                 CharacterProgressionApplicationService progressionService,
                                 AttributeCalculator attributeCalculator,
                                 AvatarRepository avatarRepository,
                                 PlayerContextResolver contextResolver,
                                 PlayerDataSyncService playerDataSyncService,
                                 TalentApplicationService talentApplicationService) {
        this.creationService = creationService; // 保存创角服务引用
        this.progressionService = progressionService; // 保存成长服务引用
        this.attributeCalculator = attributeCalculator; // 保存属性计算器引用
        this.avatarRepository = avatarRepository; // 保存头像仓储引用
        this.contextResolver = contextResolver; // 保存会话解析器引用
        this.playerDataSyncService = playerDataSyncService; // 保存数据同步服务引用
        this.talentApplicationService = talentApplicationService; // 保存天赋服务引用
    }

    /**
     * 处理创角请求 CreateCharacterCsReq。
     * retcode：0 成功，1 未登录；2~4 由 CharacterCreationApplicationService 透传。
     */
    public CharacterSystemProto.CreateCharacterScRsp handleCreateCharacter(
            CharacterSystemProto.CreateCharacterCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 从当前连接解析登录玩家 uid
        if (playerId <= 0) { // uid 无效表示未登录或会话未绑定
            return CharacterSystemProto.CreateCharacterScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        CharacterCreationApplicationService.CreateResult result =
                creationService.create(playerId, req.getNickname(), req.getStarterAvatarId()); // 执行业务创角
        if (!result.success()) { // 昵称非法、已创角、写库失败等业务错误
            return CharacterSystemProto.CreateCharacterScRsp.newBuilder().setRetcode(result.retcode()).build(); // 透传业务 retcode
        }
        notifyAvatars(channel); // 创角成功，通知客户端刷新角色列表
        return CharacterSystemProto.CreateCharacterScRsp.newBuilder()
                .setRetcode(0) // 0 表示创角成功
                .setNickname(result.nickname()) // 回显 trim 后的最终昵称
                .setStarterAvatar(toAvatarInfo(result.avatar())) // 封装新创建的头像信息
                .build(); // 序列化为 CreateCharacterScRsp 字节流
    }

    /**
     * 处理角色突破 PromoteAvatarCsReq。
     * retcode：0 成功，1 未登录，2 突破条件不满足（等级不足或已达 MAX_PROMOTION）。
     */
    public CharacterSystemProto.PromoteAvatarScRsp handlePromoteAvatar(
            CharacterSystemProto.PromoteAvatarCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析当前登录玩家
        if (playerId <= 0) {
            return CharacterSystemProto.PromoteAvatarScRsp.newBuilder().setRetcode(1).build(); // 未登录
        }
        CharacterProgressionApplicationService.PromoteResult result =
                progressionService.promoteAvatar(playerId, req.getAvatarId()); // 尝试 promotion+1
        if (!result.success()) { // 等级不够或突破次数已满
            return CharacterSystemProto.PromoteAvatarScRsp.newBuilder().setRetcode(2).build(); // 突破条件不满足
        }
        notifyAvatars(channel); // 突破成功，刷新客户端 AVATARS 缓存
        return CharacterSystemProto.PromoteAvatarScRsp.newBuilder()
                .setRetcode(0)
                .setAvatar(toAvatarInfo(result.avatar())) // 回传突破后的 avatar 快照（含新 promotion）
                .build();
    }

    /**
     * 处理 GetAvatarAttributes：按 AttributeCalculator 公式计算战斗面板四维。
     * retcode：0 成功，1 未登录，2 角色不存在或不属于当前玩家。
     */
    public CharacterSystemProto.GetAvatarAttributesScRsp handleGetAvatarAttributes(
            CharacterSystemProto.GetAvatarAttributesCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return CharacterSystemProto.GetAvatarAttributesScRsp.newBuilder().setRetcode(1).build();
        }
        var avatar = avatarRepository.findAvatar(playerId, req.getAvatarId()); // 校验 avatar 归属当前玩家
        if (avatar == null) { // 无记录说明 avatarId 不存在或不属于该玩家
            return CharacterSystemProto.GetAvatarAttributesScRsp.newBuilder().setRetcode(2).build();
        }
        AttributeCalculator.AvatarAttributes attrs = attributeCalculator.calculate(avatar); // 按 level/promotion/rank 算面板
        return CharacterSystemProto.GetAvatarAttributesScRsp.newBuilder()
                .setRetcode(0)
                .setAttributes(CharacterSystemProto.AvatarAttributes.newBuilder()
                        .setAvatarId(attrs.avatarId()) // 角色模板 ID
                        .setHp(attrs.hp()) // 生命值（含等级与突破加成）
                        .setAtk(attrs.atk()) // 攻击力
                        .setDef(attrs.def()) // 防御力
                        .setSpd(attrs.spd()) // 速度（影响行动顺序）
                        .build())
                .build();
    }

    /**
     * 处理天赋升级 UpgradeTalentCsReq。
     * retcode：0 成功，1 未登录；2~4 由 TalentApplicationService 透传。
     */
    public CharacterSystemProto.UpgradeTalentScRsp handleUpgradeTalent(
            CharacterSystemProto.UpgradeTalentCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return CharacterSystemProto.UpgradeTalentScRsp.newBuilder().setRetcode(1).build();
        }
        TalentApplicationService.UpgradeResult result = talentApplicationService.upgrade(
                playerId, req.getAvatarId(), req.getTalentId(), req.getTargetLevel()); // 推进天赋到目标等级
        if (!result.success()) { // 角色不存在、等级非法、已不低于目标等级
            return CharacterSystemProto.UpgradeTalentScRsp.newBuilder().setRetcode(result.retcode()).build();
        }
        return CharacterSystemProto.UpgradeTalentScRsp.newBuilder()
                .setRetcode(0)
                .setTalent(toTalentInfo(result.talent())) // 回传升级后的天赋快照
                .build();
    }

    /** 拉取指定 avatar 的全部天赋列表；只读，不触发 AVATARS 同步。 */
    public CharacterSystemProto.GetTalentListScRsp handleGetTalentList(
            CharacterSystemProto.GetTalentListCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return CharacterSystemProto.GetTalentListScRsp.newBuilder().setRetcode(1).build();
        }
        CharacterSystemProto.GetTalentListScRsp.Builder builder =
                CharacterSystemProto.GetTalentListScRsp.newBuilder().setRetcode(0); // 预置成功码
        talentApplicationService.listTalents(playerId, req.getAvatarId()) // 从 DB 读取天赋列表
                .forEach(t -> builder.addTalents(toTalentInfo(t))); // 逐条映射为协议 TalentInfo
        return builder.build();
    }

    /** 创角/突破成功后，按 uid 通知客户端刷新 AVATARS 维度数据。 */
    private void notifyAvatars(Channel channel) {
        contextResolver.resolveUid(channel).ifPresent(uid -> // 仅在线且已绑定 uid 的会话才通知
                playerDataSyncService.notifyDataChanged(uid, DataChangeScope.AVATARS)); // 触发客户端拉取最新角色列表
    }

    /** 领域 AvatarEntity 转换为协议 AvatarInfo，屏蔽数据库内部字段。 */
    private static CharacterSystemProto.AvatarInfo toAvatarInfo(AvatarEntity avatar) {
        if (avatar == null) { // 防御性处理，避免 NPE
            return CharacterSystemProto.AvatarInfo.getDefaultInstance(); // 返回 protobuf 空实例
        }
        return CharacterSystemProto.AvatarInfo.newBuilder()
                .setAvatarId(avatar.getAvatarId()) // 角色配置模板 ID
                .setLevel(avatar.getLevel()) // 当前等级
                .setExp(avatar.getExp()) // 当前等级内剩余经验
                .setPromotion(avatar.getPromotion()) // 突破次数（0~6）
                .setRank(avatar.getRank()) // 命座等级
                .setEquippedSkinId(Math.max(0, avatar.getEquippedSkinId())) // 0=默认皮肤
                .build();
    }

    /** 领域 AvatarTalentEntity 转换为协议 TalentInfo。 */
    private static CharacterSystemProto.TalentInfo toTalentInfo(AvatarTalentEntity talent) {
        if (talent == null) {
            return CharacterSystemProto.TalentInfo.getDefaultInstance();
        }
        return CharacterSystemProto.TalentInfo.newBuilder()
                .setTalentId(talent.getTalentId()) // 天赋树节点 ID
                .setLevel(talent.getLevel()) // 当前天赋等级（1~10）
                .setActivated(talent.isActivated()) // 是否已激活（解锁）
                .build();
    }
}
