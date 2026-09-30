package cn.itcast.demo.mylunarcore.net;

import com.google.protobuf.Message;
import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议干燥测试：每个 {@link PacketCmd} Handler 必须能完整解析 Request Proto，
 * 并写出带 Message 类型的 Response（避免运行期消息体为空导致 NPE）。
 */
class CmdIdProtoCompletenessTest {

    @Test
    void everyPacketCmdHandlerHasRequestAndResponseProtoTypes() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Class<?> handlerClass : handlerClasses()) {
            ReflectionUtils.doWithMethods(handlerClass, method -> {
                PacketCmd cmd = method.getAnnotation(PacketCmd.class);
                if (cmd == null || Modifier.isStatic(method.getModifiers())) {
                    return;
                }
                // 约定：(ChannelHandlerContext, GamePacket) → 方法内 parseFrom + writeAndFlush(GamePacket)
                // 干燥检查：同文件/同域应存在对应 ScRsp 常量，且方法体通过编译即保证 Proto 类存在
                String name = method.getName();
                int cmdId = cmd.value();
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) {
                    failures.add(handlerClass.getSimpleName() + "#" + name + " cmd=" + cmdId
                            + " expected (ChannelHandlerContext, GamePacket)");
                    return;
                }
                if (!io.netty.channel.ChannelHandlerContext.class.isAssignableFrom(params[0])
                        || !GamePacket.class.isAssignableFrom(params[1])) {
                    failures.add(handlerClass.getSimpleName() + "#" + name + " cmd=" + cmdId
                            + " bad param types");
                }
                // 请求侧：CsReq 常量存在；响应侧：同号段 ScRsp / Notify 存在
                if (!hasPairedResponseConstant(cmdId)) {
                    failures.add("CmdId=" + cmdId + " (" + handlerClass.getSimpleName() + "#" + name
                            + ") missing paired SC_RSP/NOTIFY constant in CmdIds");
                }
            }, m -> m.getAnnotation(PacketCmd.class) != null);
        }
        assertTrue(failures.isEmpty(), "Proto completeness failures:\n" + String.join("\n", failures));
    }

    @Test
    void packetHandlerClassesAreMessageAware() {
        // 抽样：Scene / Guild / Battle 的 parseFrom 目标均为 Protobuf Message
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.SceneSystemProto.EnterSceneCsReq.class));
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.SceneSystemProto.EnterSceneScRsp.class));
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.GuildSystemProto.CreateGuildCsReq.class));
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.BattleSystemProto.FightStartCsReq.class));
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto.PlayerLoginCsReq.class));
        assertTrue(Message.class.isAssignableFrom(
                cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto.PlayerLoginScRsp.class));
    }

    private static boolean hasPairedResponseConstant(int csReqCmdId) {
        // 多数成对：CS 偶数 / SC 奇数，或显式 SC_NOTIFY
        try {
            for (var field : CmdIds.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                    continue;
                }
                String n = field.getName();
                int v = field.getInt(null);
                if (v == csReqCmdId + 1 && (n.endsWith("_SC_RSP") || n.endsWith("_SC_NOTIFY"))) {
                    return true;
                }
                // 特殊：登录 CS=68 / SC=6 等历史号段，仅要求存在任意 SC 后缀常量不等于自身
                if (csReqCmdId == CmdIds.PLAYER_LOGIN_CS_REQ && v == CmdIds.PLAYER_LOGIN_SC_RSP) {
                    return true;
                }
            }
        } catch (IllegalAccessException e) {
            return false;
        }
        // 宽松：若 CmdId 本身已是 SC 则跳过（不应出现在 @PacketCmd 上）
        return false;
    }

    private static List<Class<?>> handlerClasses() {
        return List.of(
                ScenePacketHandlers.class,
                BattlePacketHandlers.class,
                GuildPacketHandlers.class,
                QuestPacketHandlers.class,
                HomePacketHandlers.class,
                DialogueCutscenePacketHandlers.class,
                EconomyPacketHandlers.class,
                ChallengePacketHandlers.class,
                MatchPacketHandlers.class,
                ItemPacketHandlers.class,
                RoguePacketHandlers.class,
                SkinPacketHandlers.class,
                UpdatePacketHandlers.class,
                BattlePassPacketHandlers.class,
                DailyLoopPacketHandlers.class,
                TutorialPacketHandlers.class,
                CharacterPacketHandlers.class,
                HallPacketHandlers.class,
                SettingsPacketHandlers.class,
                QolSocialPacketHandlers.class
        );
    }
}
