package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import cn.itcast.demo.mylunarcore.qol.QolSocialNettyService;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 体验/社交缺口协议入口（CmdId 1200–1247）。
 */
@Component
public class QolSocialPacketHandlers {

    private final QolSocialNettyService netty;

    public QolSocialPacketHandlers(QolSocialNettyService netty) {
        this.netty = netty;
    }

    @PacketCmd(CmdIds.CLAIM_ALL_DAILY_REWARDS_CS_REQ)
    public void onClaimAll(ChannelHandlerContext ctx, GamePacket packet) {
        QolSocialSystemProto.ClaimAllDailyRewardsScRsp rsp = netty.handleClaimAll(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_ALL_DAILY_REWARDS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_EXPLORATION_INFO_CS_REQ)
    public void onGetExploration(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.GetExplorationInfoCsReq req =
                QolSocialSystemProto.GetExplorationInfoCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.GetExplorationInfoScRsp rsp = netty.handleGetExploration(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_EXPLORATION_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SET_PLAYER_STATUS_CS_REQ)
    public void onSetStatus(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.SetPlayerStatusCsReq req =
                QolSocialSystemProto.SetPlayerStatusCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.SetPlayerStatusScRsp rsp = netty.handleSetStatus(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SET_PLAYER_STATUS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_PLAYER_PROFILE_DETAIL_CS_REQ)
    public void onGetProfile(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.GetPlayerProfileDetailCsReq req =
                QolSocialSystemProto.GetPlayerProfileDetailCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.GetPlayerProfileDetailScRsp rsp = netty.handleGetProfile(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_PLAYER_PROFILE_DETAIL_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.REPORT_PLAYER_CS_REQ)
    public void onReport(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.ReportPlayerCsReq req =
                QolSocialSystemProto.ReportPlayerCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.ReportPlayerScRsp rsp = netty.handleReport(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.REPORT_PLAYER_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.BLOCK_PLAYER_CS_REQ)
    public void onBlock(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.BlockPlayerCsReq req =
                QolSocialSystemProto.BlockPlayerCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.BlockPlayerScRsp rsp = netty.handleBlock(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BLOCK_PLAYER_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SHARE_BATTLE_REPLAY_CS_REQ)
    public void onShareReplay(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.ShareBattleReplayCsReq req =
                QolSocialSystemProto.ShareBattleReplayCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.ShareBattleReplayScRsp rsp = netty.handleShareReplay(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SHARE_BATTLE_REPLAY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_SHARED_REPLAY_CS_REQ)
    public void onGetSharedReplay(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.GetSharedReplayCsReq req =
                QolSocialSystemProto.GetSharedReplayCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.GetSharedReplayScRsp rsp = netty.handleGetSharedReplay(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SHARED_REPLAY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SET_DAILY_REMINDER_PREF_CS_REQ)
    public void onSetReminderPref(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.SetDailyReminderPrefCsReq req =
                QolSocialSystemProto.SetDailyReminderPrefCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.SetDailyReminderPrefScRsp rsp = netty.handleSetReminderPref(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SET_DAILY_REMINDER_PREF_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.UPLOAD_CUSTOM_EMOTE_CS_REQ)
    public void onUploadEmote(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.UploadCustomEmoteCsReq req =
                QolSocialSystemProto.UploadCustomEmoteCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.UploadCustomEmoteScRsp rsp = netty.handleUploadEmote(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.UPLOAD_CUSTOM_EMOTE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.MAKEUP_SIGN_IN_CS_REQ)
    public void onMakeup(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.MakeupSignInCsReq req =
                QolSocialSystemProto.MakeupSignInCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.MakeupSignInScRsp rsp = netty.handleMakeup(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.MAKEUP_SIGN_IN_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SEARCH_PLAYERS_CS_REQ)
    public void onSearchPlayers(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.SearchPlayersCsReq req =
                QolSocialSystemProto.SearchPlayersCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.SearchPlayersScRsp rsp = netty.handleSearchPlayers(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SEARCH_PLAYERS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SEARCH_GUILDS_CS_REQ)
    public void onSearchGuilds(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.SearchGuildsCsReq req =
                QolSocialSystemProto.SearchGuildsCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.SearchGuildsScRsp rsp = netty.handleSearchGuilds(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SEARCH_GUILDS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_RECENT_PLAYERS_CS_REQ)
    public void onGetRecent(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.GetRecentPlayersCsReq req =
                QolSocialSystemProto.GetRecentPlayersCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.GetRecentPlayersScRsp rsp = netty.handleGetRecent(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_RECENT_PLAYERS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_TITLE_LIST_CS_REQ)
    public void onGetTitles(ChannelHandlerContext ctx, GamePacket packet) {
        QolSocialSystemProto.GetTitleListScRsp rsp = netty.handleGetTitles(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_TITLE_LIST_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.EQUIP_TITLE_CS_REQ)
    public void onEquipTitle(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.EquipTitleCsReq req =
                QolSocialSystemProto.EquipTitleCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.EquipTitleScRsp rsp = netty.handleEquipTitle(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.EQUIP_TITLE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.INVITE_FRIENDS_TO_PARTY_CS_REQ)
    public void onInviteFriends(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.InviteFriendsToPartyCsReq req =
                QolSocialSystemProto.InviteFriendsToPartyCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.InviteFriendsToPartyScRsp rsp = netty.handleInviteFriends(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.INVITE_FRIENDS_TO_PARTY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.INVITE_GUILD_MEMBERS_CS_REQ)
    public void onInviteGuild(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.InviteGuildMembersCsReq req =
                QolSocialSystemProto.InviteGuildMembersCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.InviteGuildMembersScRsp rsp = netty.handleInviteGuild(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.INVITE_GUILD_MEMBERS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_GACHA_GUARANTEE_INFO_CS_REQ)
    public void onGachaGuarantee(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.GetGachaGuaranteeInfoCsReq req =
                QolSocialSystemProto.GetGachaGuaranteeInfoCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.GetGachaGuaranteeInfoScRsp rsp = netty.handleGachaGuarantee(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GACHA_GUARANTEE_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.EXPORT_TRANSACTION_HISTORY_CS_REQ)
    public void onExportHistory(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QolSocialSystemProto.ExportTransactionHistoryCsReq req =
                QolSocialSystemProto.ExportTransactionHistoryCsReq.parseFrom(payload(packet));
        QolSocialSystemProto.ExportTransactionHistoryScRsp rsp = netty.handleExportHistory(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.EXPORT_TRANSACTION_HISTORY_SC_RSP, rsp.toByteArray()));
    }

    private static byte[] payload(GamePacket packet) {
        return packet.getPayload() == null ? new byte[0] : packet.getPayload();
    }
}
