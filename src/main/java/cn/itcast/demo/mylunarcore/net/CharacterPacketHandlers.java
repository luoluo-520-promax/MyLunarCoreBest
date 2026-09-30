// 网络命令处理器实现所在包（角色/养成系统相关）
package cn.itcast.demo.mylunarcore.net;

// 角色领域 Netty 门面：创建角色、晋升、属性、天赋等业务入口
import cn.itcast.demo.mylunarcore.character.CharacterNettyService;
// 角色系统 Protobuf 消息定义（CsReq 上行 / ScRsp 下行）
import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto;
// Netty 通道上下文：写回下行响应时使用
import io.netty.channel.ChannelHandlerContext;
// Spring 组件注解：让 PacketCommandRegistry 能扫描到本类中带 @PacketCmd 的方法
import org.springframework.stereotype.Component;

/**
 * 角色/养成系统协议入口处理器。
 * <p>
 * 覆盖角色协议号段 160–169（wire_version≥2 后与 Party 120–129 拆分的号段）：
 * 创建角色、晋升、查询属性、升级天赋、查询天赋列表。
 * 每个方法通过 {@link PacketCmd} 绑定一个上行命令号，标准流程为：
 * <ol>
 *   <li>将帧负载 {@code packet.getPayload()} 反序列化为对应的 CsReq Protobuf；</li>
 *   <li>委托 {@link CharacterNettyService} 完成业务处理（校验、落库、写玩家数据）；</li>
 *   <li>把返回的 ScRsp 序列化后封装为 {@link GamePacket} 并经 ctx 写回客户端。</li>
 * </ol>
 * 注意：本处理器不解析会话状态，是否已登录由业务服务内部通过 Channel 属性判定。
 */
@Component // 注册为 Spring Bean，供 PacketCommandRegistry 启动期扫描
public class CharacterPacketHandlers {

    // 角色领域服务（不可变依赖）：处理创建/晋升/天赋等全部角色业务
    private final CharacterNettyService characterNettyService;
    private final cn.itcast.demo.mylunarcore.character.CharacterDevelopmentNettyService developmentNettyService;

    public CharacterPacketHandlers(CharacterNettyService characterNettyService,
                                   cn.itcast.demo.mylunarcore.character.CharacterDevelopmentNettyService developmentNettyService) {
        this.characterNettyService = characterNettyService;
        this.developmentNettyService = developmentNettyService;
    }

    /**
     * 创建角色（CREATE_CHARACTER_CS_REQ=160）。
     * 解析客户端提交的角色创建请求（职业/外观等），由服务创建角色数据并返回结果。
     */
    @PacketCmd(CmdIds.CREATE_CHARACTER_CS_REQ) // 绑定上行命令号 160
    public void onCreateCharacter(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体
        CharacterSystemProto.CreateCharacterCsReq req = CharacterSystemProto.CreateCharacterCsReq.parseFrom(packet.getPayload());
        // 调用角色服务完成创建
        CharacterSystemProto.CreateCharacterScRsp rsp = characterNettyService.handleCreateCharacter(req, ctx.channel());
        // 序列化响应并写出（命令号 161）
        ctx.writeAndFlush(new GamePacket(CmdIds.CREATE_CHARACTER_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 晋升角色（PROMOTE_AVATAR_CS_REQ=162）。
     * 消耗晋升材料提升角色等级上限等养成属性，由服务扣材料并写回结果。
     */
    @PacketCmd(CmdIds.PROMOTE_AVATAR_CS_REQ) // 绑定上行命令号 162
    public void onPromoteAvatar(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体
        CharacterSystemProto.PromoteAvatarCsReq req = CharacterSystemProto.PromoteAvatarCsReq.parseFrom(packet.getPayload());
        // 调用角色服务执行晋升
        CharacterSystemProto.PromoteAvatarScRsp rsp = characterNettyService.handlePromoteAvatar(req, ctx.channel());
        // 序列化响应并写出（命令号 163）
        ctx.writeAndFlush(new GamePacket(CmdIds.PROMOTE_AVATAR_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询角色最终属性（GET_AVATAR_ATTRIBUTES_CS_REQ=164）。
     * 返回角色装备、遗器、天赋等叠加后的面板属性快照。
     */
    @PacketCmd(CmdIds.GET_AVATAR_ATTRIBUTES_CS_REQ) // 绑定上行命令号 164
    public void onGetAvatarAttributes(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体
        CharacterSystemProto.GetAvatarAttributesCsReq req = CharacterSystemProto.GetAvatarAttributesCsReq.parseFrom(packet.getPayload());
        // 计算并返回角色属性
        CharacterSystemProto.GetAvatarAttributesScRsp rsp = characterNettyService.handleGetAvatarAttributes(req, ctx.channel());
        // 序列化响应并写出（命令号 165）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_AVATAR_ATTRIBUTES_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 升级角色天赋（UPGRADE_TALENT_CS_REQ=166）。
     * 消耗对应天赋材料提升天赋等级，返回升级后的天赋状态。
     */
    @PacketCmd(CmdIds.UPGRADE_TALENT_CS_REQ) // 绑定上行命令号 166
    public void onUpgradeTalent(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体
        CharacterSystemProto.UpgradeTalentCsReq req = CharacterSystemProto.UpgradeTalentCsReq.parseFrom(packet.getPayload());
        // 调用角色服务升级天赋
        CharacterSystemProto.UpgradeTalentScRsp rsp = characterNettyService.handleUpgradeTalent(req, ctx.channel());
        // 序列化响应并写出（命令号 167）
        ctx.writeAndFlush(new GamePacket(CmdIds.UPGRADE_TALENT_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询角色天赋列表（GET_TALENT_LIST_CS_REQ=168）。
     * 返回指定角色已解锁/未解锁的天赋树状态，供客户端渲染天赋界面。
     */
    @PacketCmd(CmdIds.GET_TALENT_LIST_CS_REQ) // 绑定上行命令号 168
    public void onGetTalentList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体
        CharacterSystemProto.GetTalentListCsReq req = CharacterSystemProto.GetTalentListCsReq.parseFrom(packet.getPayload());
        // 查询角色天赋列表
        CharacterSystemProto.GetTalentListScRsp rsp = characterNettyService.handleGetTalentList(req, ctx.channel());
        // 序列化响应并写出（命令号 169）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_TALENT_LIST_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CALCULATE_UPGRADE_MATERIALS_CS_REQ)
    public void onCalculateUpgradeMaterials(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        CharacterSystemProto.CalculateUpgradeMaterialsCsReq req =
                CharacterSystemProto.CalculateUpgradeMaterialsCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        CharacterSystemProto.CalculateUpgradeMaterialsScRsp rsp =
                developmentNettyService.handleCalculate(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CALCULATE_UPGRADE_MATERIALS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CALCULATE_OPTIMAL_SCHEDULE_CS_REQ)
    public void onCalculateOptimalSchedule(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        CharacterSystemProto.CalculateOptimalScheduleCsReq req =
                CharacterSystemProto.CalculateOptimalScheduleCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        CharacterSystemProto.CalculateOptimalScheduleScRsp rsp =
                developmentNettyService.handleSchedule(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CALCULATE_OPTIMAL_SCHEDULE_SC_RSP, rsp.toByteArray()));
    }
}
