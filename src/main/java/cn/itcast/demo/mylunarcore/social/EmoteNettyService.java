package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import io.netty.channel.Channel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 聊天表情与场景动作协议适配（CmdId 1054–1061）。
 */
@Service
public class EmoteNettyService {

    private final EmoteInventoryService inventory;
    private final PlayerContextResolver contextResolver;
    private final GameSessionManager sessionManager;
    private final SceneManager sceneManager;
    private final ZoneManager zoneManager;

    public EmoteNettyService(EmoteInventoryService inventory,
                             PlayerContextResolver contextResolver,
                             ObjectProvider<GameSessionManager> sessionProvider,
                             ObjectProvider<SceneManager> sceneProvider,
                             ObjectProvider<ZoneManager> zoneProvider) {
        this.inventory = inventory;
        this.contextResolver = contextResolver;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.sceneManager = sceneProvider == null ? null : sceneProvider.getIfAvailable();
        this.zoneManager = zoneProvider == null ? null : zoneProvider.getIfAvailable();
    }

    public HallSystemProto.GetEmoteInventoryScRsp handleGetInventory(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return HallSystemProto.GetEmoteInventoryScRsp.newBuilder().setRetcode(1).build();
        }
        HallSystemProto.GetEmoteInventoryScRsp.Builder b =
                HallSystemProto.GetEmoteInventoryScRsp.newBuilder().setRetcode(0);
        java.util.Set<Integer> owned = new java.util.HashSet<>();
        for (EmoteInventoryService.EmoteDef e : inventory.ownedOrDefault(playerId)) {
            owned.add(e.emoteId());
        }
        for (EmoteInventoryService.EmoteDef e : inventory.catalog()) {
            b.addEmotes(HallSystemProto.EmoteItem.newBuilder()
                    .setEmoteId(e.emoteId())
                    .setName(e.name())
                    .setAnimId(e.animId())
                    .setSticker(e.sticker())
                    .setOwned(owned.contains(e.emoteId()))
                    .build());
        }
        return b.build();
    }

    public HallSystemProto.SendChatEmoteScRsp handleSendChatEmote(HallSystemProto.SendChatEmoteCsReq req,
                                                                  Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return HallSystemProto.SendChatEmoteScRsp.newBuilder().setRetcode(1).build();
        }
        EmoteInventoryService.PlayResult play = inventory.play(playerId, req.getEmoteId(), false);
        if (!play.ok()) {
            return HallSystemProto.SendChatEmoteScRsp.newBuilder()
                    .setRetcode(play.retcode())
                    .setEmoteId(req.getEmoteId())
                    .build();
        }
        EmoteInventoryService.EmoteDef def = play.emote();
        HallSystemProto.ChatMessage msg = HallSystemProto.ChatMessage.newBuilder()
                .setChannelType(req.getChannelType())
                .setSenderId(playerId)
                .setSenderName("Player" + playerId)
                .setTargetId(req.getTargetId())
                .setContent("[emote:" + def.emoteId() + "]")
                .setSendTime(System.currentTimeMillis())
                .setEmoteId(def.emoteId())
                .build();
        HallSystemProto.ChatEmoteScNotify notify = HallSystemProto.ChatEmoteScNotify.newBuilder()
                .setMessage(msg)
                .setEmoteId(def.emoteId())
                .setAnimId(def.animId())
                .setSticker(def.sticker())
                .build();
        byte[] payload = notify.toByteArray();
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new GamePacket(CmdIds.CHAT_EMOTE_SC_NOTIFY, payload));
        }
        if (sessionManager != null && req.getChannelType() == 1 && req.getTargetId() > 0) {
            GameSession target = sessionManager.getOrNull(req.getTargetId());
            if (target != null && target.getChannel() != null && target.getChannel() != channel) {
                target.getChannel().writeAndFlush(new GamePacket(CmdIds.CHAT_EMOTE_SC_NOTIFY, payload));
            }
        }
        return HallSystemProto.SendChatEmoteScRsp.newBuilder().setRetcode(0).setEmoteId(def.emoteId()).build();
    }

    public SceneSystemProto.SceneEmoteScRsp handleSceneEmote(SceneSystemProto.SceneEmoteCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.SceneEmoteScRsp.newBuilder().setRetcode(1).build();
        }
        if (sceneManager == null) {
            return SceneSystemProto.SceneEmoteScRsp.newBuilder().setRetcode(2).build();
        }
        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (ctx == null || !ctx.isInitialized()) {
            return SceneSystemProto.SceneEmoteScRsp.newBuilder().setRetcode(2).build();
        }
        boolean wantDuo = req.getTargetPlayerUid() > 0;
        EmoteInventoryService.PlayResult play = inventory.play(uid.intValue(), req.getEmoteId(), wantDuo);
        if (!play.ok()) {
            return SceneSystemProto.SceneEmoteScRsp.newBuilder()
                    .setRetcode(play.retcode())
                    .setEmoteId(req.getEmoteId())
                    .build();
        }
        long targetUid = req.getTargetPlayerUid();
        boolean duo = play.duo();
        if (duo && targetUid > 0 && zoneManager != null) {
            boolean inAoi = false;
            for (Long nearby : zoneManager.get(ctx.getZoneId()) == null
                    ? java.util.List.<Long>of()
                    : zoneManager.get(ctx.getZoneId()).nearbyPlayers(uid)) {
                if (nearby != null && nearby == targetUid) {
                    inAoi = true;
                    break;
                }
            }
            if (!inAoi) {
                return SceneSystemProto.SceneEmoteScRsp.newBuilder()
                        .setRetcode(4)
                        .setEmoteId(req.getEmoteId())
                        .build();
            }
        } else {
            duo = false;
            targetUid = 0;
        }
        SceneSystemProto.SceneEmoteScNotify notify = SceneSystemProto.SceneEmoteScNotify.newBuilder()
                .setPlayerUid(uid)
                .setEmoteId(play.emote().emoteId())
                .setTargetPlayerUid(targetUid)
                .setDuo(duo)
                .setAnimId(play.emote().animId())
                .build();
        byte[] payload = notify.toByteArray();
        broadcastAoi(channel, uid, ctx.getZoneId(), payload);
        return SceneSystemProto.SceneEmoteScRsp.newBuilder()
                .setRetcode(0)
                .setEmoteId(play.emote().emoteId())
                .setDuo(duo)
                .build();
    }

    private void broadcastAoi(Channel self, long uid, int zoneId, byte[] payload) {
        if (self != null && self.isActive()) {
            self.writeAndFlush(new GamePacket(CmdIds.SCENE_EMOTE_SC_NOTIFY, payload));
        }
        if (sessionManager == null || zoneManager == null) {
            return;
        }
        var zone = zoneManager.get(zoneId);
        if (zone == null) {
            return;
        }
        for (Long other : zone.nearbyPlayers(uid)) {
            GameSession session = sessionManager.getOrNull(other);
            if (session != null && session.getChannel() != null && session.getChannel() != self) {
                session.getChannel().writeAndFlush(new GamePacket(CmdIds.SCENE_EMOTE_SC_NOTIFY, payload));
            }
        }
    }
}
