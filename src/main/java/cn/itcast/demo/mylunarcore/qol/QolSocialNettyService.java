package cn.itcast.demo.mylunarcore.qol;

import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.battle.BattleReplayShareService;
import cn.itcast.demo.mylunarcore.exploration.ExplorationService;
import cn.itcast.demo.mylunarcore.gacha.GachaGuaranteeQueryService;
import cn.itcast.demo.mylunarcore.gacha.TransactionHistoryExportService;
import cn.itcast.demo.mylunarcore.guild.GuildService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.profile.PlayerProfileService;
import cn.itcast.demo.mylunarcore.profile.TitleService;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import cn.itcast.demo.mylunarcore.social.CustomEmoteService;
import cn.itcast.demo.mylunarcore.social.FriendOnlineStatusService;
import cn.itcast.demo.mylunarcore.social.FriendSearchService;
import cn.itcast.demo.mylunarcore.social.GuildSearchService;
import cn.itcast.demo.mylunarcore.social.PlayerReportBlockService;
import io.netty.channel.Channel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * QoL / 社交缺口协议适配层。
 */
@Service
public class QolSocialNettyService {

    private final PlayerContextResolver contextResolver;
    private final GameSessionManager sessionManager;
    private final ClaimAllDailyRewardsService claimAllService;
    private final ExplorationService explorationService;
    private final PlayerProfileService profileService;
    private final PlayerReportBlockService reportBlockService;
    private final BattleReplayShareService replayShareService;
    private final DailyReminderService dailyReminderService;
    private final CustomEmoteService customEmoteService;
    private final ActivityTemplateService activityTemplateService;
    private final FriendSearchService friendSearchService;
    private final GuildSearchService guildSearchService;
    private final TitleService titleService;
    private final PartyService partyService;
    private final GachaGuaranteeQueryService gachaGuaranteeQueryService;
    private final TransactionHistoryExportService transactionHistoryExportService;
    private final ObjectProvider<GuildService> guildServiceProvider;
    private final ObjectProvider<FriendOnlineStatusService> friendOnlineProvider;

    public QolSocialNettyService(PlayerContextResolver contextResolver,
                                 GameSessionManager sessionManager,
                                 ClaimAllDailyRewardsService claimAllService,
                                 ExplorationService explorationService,
                                 PlayerProfileService profileService,
                                 PlayerReportBlockService reportBlockService,
                                 BattleReplayShareService replayShareService,
                                 DailyReminderService dailyReminderService,
                                 CustomEmoteService customEmoteService,
                                 ActivityTemplateService activityTemplateService,
                                 FriendSearchService friendSearchService,
                                 GuildSearchService guildSearchService,
                                 TitleService titleService,
                                 PartyService partyService,
                                 GachaGuaranteeQueryService gachaGuaranteeQueryService,
                                 TransactionHistoryExportService transactionHistoryExportService,
                                 ObjectProvider<GuildService> guildServiceProvider,
                                 ObjectProvider<FriendOnlineStatusService> friendOnlineProvider) {
        this.contextResolver = contextResolver;
        this.sessionManager = sessionManager;
        this.claimAllService = claimAllService;
        this.explorationService = explorationService;
        this.profileService = profileService;
        this.reportBlockService = reportBlockService;
        this.replayShareService = replayShareService;
        this.dailyReminderService = dailyReminderService;
        this.customEmoteService = customEmoteService;
        this.activityTemplateService = activityTemplateService;
        this.friendSearchService = friendSearchService;
        this.guildSearchService = guildSearchService;
        this.titleService = titleService;
        this.partyService = partyService;
        this.gachaGuaranteeQueryService = gachaGuaranteeQueryService;
        this.transactionHistoryExportService = transactionHistoryExportService;
        this.guildServiceProvider = guildServiceProvider;
        this.friendOnlineProvider = friendOnlineProvider;
    }

    public QolSocialSystemProto.ClaimAllDailyRewardsScRsp handleClaimAll(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.ClaimAllDailyRewardsScRsp.newBuilder().setRetcode(1).build();
        }
        ClaimAllDailyRewardsService.ClaimAllResult r = claimAllService.claimAll(playerId);
        QolSocialSystemProto.ClaimAllDailyRewardsScRsp.Builder b =
                QolSocialSystemProto.ClaimAllDailyRewardsScRsp.newBuilder()
                        .setRetcode(r.retcode())
                        .setTotalClaimed(r.totalClaimed());
        for (ClaimAllDailyRewardsService.ModuleSummary s : r.summaries()) {
            b.addSummaries(QolSocialSystemProto.ClaimedRewardSummary.newBuilder()
                    .setModule(s.module())
                    .setSuccessCount(s.successCount())
                    .setSkipCount(s.skipCount())
                    .setDetail(s.detail() == null ? "" : s.detail())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.GetExplorationInfoScRsp handleGetExploration(
            QolSocialSystemProto.GetExplorationInfoCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.GetExplorationInfoScRsp.newBuilder().setRetcode(1).build();
        }
        ExplorationService.RegionProgress p =
                explorationService.getInfo(playerId, req.getPlaneId(), req.getFloorId());
        QolSocialSystemProto.GetExplorationInfoScRsp.Builder b =
                QolSocialSystemProto.GetExplorationInfoScRsp.newBuilder()
                        .setRetcode(0)
                        .setPlaneId(p.planeId())
                        .setFloorId(p.floorId())
                        .setCollected(p.collected())
                        .setTotal(p.total())
                        .setProgressPercent(p.percent());
        for (ExplorationService.CollectableView v : p.collectables()) {
            b.addCollectables(QolSocialSystemProto.ExplorationCollectable.newBuilder()
                    .setCollectId(v.collectId())
                    .setKind(v.kind())
                    .setName(v.name())
                    .setCollected(v.collected())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.SetPlayerStatusScRsp handleSetStatus(
            QolSocialSystemProto.SetPlayerStatusCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.SetPlayerStatusScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerProfileService.OpResult r = profileService.setStatus(
                playerId, req.getSignature(), req.getStatusMessage(), req.getCustomStatus());
        if (r.ok()) {
            FriendOnlineStatusService online = friendOnlineProvider.getIfAvailable();
            if (online != null) {
                online.publishOnline(playerId);
            }
        }
        return QolSocialSystemProto.SetPlayerStatusScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setSignature(r.profile().signature())
                .setStatusMessage(r.profile().statusMessage())
                .setCustomStatus(r.profile().customStatus())
                .build();
    }

    public QolSocialSystemProto.GetPlayerProfileDetailScRsp handleGetProfile(
            QolSocialSystemProto.GetPlayerProfileDetailCsReq req, Channel channel) {
        int selfId = contextResolver.resolvePlayerId(channel);
        if (selfId <= 0) {
            return QolSocialSystemProto.GetPlayerProfileDetailScRsp.newBuilder().setRetcode(1).build();
        }
        int target = req.getTargetPlayerId() <= 0 ? selfId : (int) req.getTargetPlayerId();
        PlayerProfileService.Profile profile = profileService.getOrDefault(target);
        GameSession session = sessionManager.getOrNull(target);
        String nickname = session != null && session.getNickname() != null ? session.getNickname() : "";
        int level = session != null ? session.getLevel() : 0;
        return QolSocialSystemProto.GetPlayerProfileDetailScRsp.newBuilder()
                .setRetcode(0)
                .setPlayerId(target)
                .setNickname(nickname)
                .setLevel(level)
                .setSignature(profile.signature())
                .setStatusMessage(profile.statusMessage())
                .setCustomStatus(profile.customStatus())
                .setOnline(session != null)
                .setEquippedTitle(titleService.getEquippedDisplayName(target))
                .setTitleId("")
                .build();
    }

    public QolSocialSystemProto.ReportPlayerScRsp handleReport(
            QolSocialSystemProto.ReportPlayerCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.ReportPlayerScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerReportBlockService.ReportResult r = reportBlockService.report(
                playerId, (int) req.getTargetPlayerId(), req.getScene(),
                req.getEvidenceType(), req.getEvidence(), req.getReason());
        return QolSocialSystemProto.ReportPlayerScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setReportId(r.reportId())
                .build();
    }

    public QolSocialSystemProto.BlockPlayerScRsp handleBlock(
            QolSocialSystemProto.BlockPlayerCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.BlockPlayerScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerReportBlockService.BlockResult r = reportBlockService.setBlocked(
                playerId, (int) req.getTargetPlayerId(), req.getUnblock());
        return QolSocialSystemProto.BlockPlayerScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setTargetPlayerId(req.getTargetPlayerId())
                .setBlocked(r.blocked())
                .build();
    }

    public QolSocialSystemProto.ShareBattleReplayScRsp handleShareReplay(
            QolSocialSystemProto.ShareBattleReplayCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.ShareBattleReplayScRsp.newBuilder().setRetcode(1).build();
        }
        GameSession session = sessionManager.getOrNull(playerId);
        String nick = session == null || session.getNickname() == null ? "" : session.getNickname();
        BattleReplayShareService.ShareResult r = replayShareService.share(
                playerId, req.getBattleId(), (int) req.getTargetFriendId(), req.getToGuild(), nick);
        if (r.ok() && req.getToGuild()) {
            GuildService guild = guildServiceProvider.getIfAvailable();
            if (guild != null) {
                Long gid = guild.findGuildIdByPlayer(playerId);
                if (gid != null) {
                    List<Integer> members = new ArrayList<>();
                    for (GuildService.MemberInfo m : guild.listMembers(gid)) {
                        members.add(m.playerId());
                    }
                    replayShareService.notifyGuildMembers(members, r.shareCode(), playerId,
                            req.getBattleId(), nick);
                }
            }
        }
        return QolSocialSystemProto.ShareBattleReplayScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setShareCode(r.shareCode())
                .setExpireAtMs(r.expireAtMs())
                .build();
    }

    public QolSocialSystemProto.GetSharedReplayScRsp handleGetSharedReplay(
            QolSocialSystemProto.GetSharedReplayCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.GetSharedReplayScRsp.newBuilder().setRetcode(1).build();
        }
        BattleReplayShareService.FetchResult r = replayShareService.fetch(req.getShareCode());
        return QolSocialSystemProto.GetSharedReplayScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setBattleId(r.battleId() == null ? "" : r.battleId())
                .setOwnerPlayerId(r.ownerPlayerId())
                .setReplayPayload(com.google.protobuf.ByteString.copyFrom(
                        r.payload() == null ? new byte[0] : r.payload()))
                .setExpireAtMs(r.expireAtMs())
                .build();
    }

    public QolSocialSystemProto.SetDailyReminderPrefScRsp handleSetReminderPref(
            QolSocialSystemProto.SetDailyReminderPrefCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.SetDailyReminderPrefScRsp.newBuilder().setRetcode(1).build();
        }
        dailyReminderService.setPlayerPref(playerId, req.getEnabled(), req.getDisabledModulesList());
        return QolSocialSystemProto.SetDailyReminderPrefScRsp.newBuilder()
                .setRetcode(0)
                .setEnabled(req.getEnabled())
                .build();
    }

    public QolSocialSystemProto.UploadCustomEmoteScRsp handleUploadEmote(
            QolSocialSystemProto.UploadCustomEmoteCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.UploadCustomEmoteScRsp.newBuilder().setRetcode(1).build();
        }
        CustomEmoteService.UploadResult r = customEmoteService.upload(
                playerId, req.getImageData().toByteArray(), req.getContentType(), req.getName());
        return QolSocialSystemProto.UploadCustomEmoteScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setCustomEmoteId(r.customEmoteId())
                .setReviewStatus(r.reviewStatus() == null ? "" : r.reviewStatus())
                .build();
    }

    public QolSocialSystemProto.MakeupSignInScRsp handleMakeup(
            QolSocialSystemProto.MakeupSignInCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.MakeupSignInScRsp.newBuilder().setRetcode(1).build();
        }
        ActivityTemplateService.OpResult r = activityTemplateService.makeupSignIn(
                playerId, req.getActivityId(), req.getMissDay(), req.getCostItemId());
        return QolSocialSystemProto.MakeupSignInScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setMissDay(req.getMissDay())
                .setCompensated(r.success())
                .build();
    }

    public QolSocialSystemProto.SearchPlayersScRsp handleSearchPlayers(
            QolSocialSystemProto.SearchPlayersCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.SearchPlayersScRsp.newBuilder().setRetcode(1).build();
        }
        FriendSearchService.SearchPage page = friendSearchService.search(
                req.getQuery(), req.getPage(), req.getPageSize() <= 0 ? 20 : req.getPageSize());
        QolSocialSystemProto.SearchPlayersScRsp.Builder b =
                QolSocialSystemProto.SearchPlayersScRsp.newBuilder()
                        .setRetcode(0)
                        .setTotal(page.total());
        for (FriendSearchService.PlayerHit hit : page.hits()) {
            GameSession s = sessionManager.getOrNull((int) hit.uid());
            PlayerProfileService.Profile p = profileService.getOrDefault((int) hit.uid());
            b.addHits(QolSocialSystemProto.PlayerSearchHit.newBuilder()
                    .setPlayerId((int) hit.uid())
                    .setNickname(hit.nickname() == null ? "" : hit.nickname())
                    .setLevel(hit.level())
                    .setOnline(s != null)
                    .setCustomStatus(p.customStatus())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.SearchGuildsScRsp handleSearchGuilds(
            QolSocialSystemProto.SearchGuildsCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.SearchGuildsScRsp.newBuilder().setRetcode(1).build();
        }
        GuildSearchService.SearchPage page = guildSearchService.search(
                req.getQuery(), req.getPage(), req.getPageSize() <= 0 ? 20 : req.getPageSize());
        QolSocialSystemProto.SearchGuildsScRsp.Builder b =
                QolSocialSystemProto.SearchGuildsScRsp.newBuilder()
                        .setRetcode(0)
                        .setTotal(page.total());
        for (GuildSearchService.GuildHit hit : page.hits()) {
            b.addHits(QolSocialSystemProto.GuildSearchHit.newBuilder()
                    .setGuildId((int) hit.guildId())
                    .setName(hit.name() == null ? "" : hit.name())
                    .setLevel(hit.level())
                    .setMemberCount(hit.memberCount())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.GetRecentPlayersScRsp handleGetRecent(
            QolSocialSystemProto.GetRecentPlayersCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.GetRecentPlayersScRsp.newBuilder().setRetcode(1).build();
        }
        int limit = req.getLimit() <= 0 ? 20 : Math.min(50, req.getLimit());
        QolSocialSystemProto.GetRecentPlayersScRsp.Builder b =
                QolSocialSystemProto.GetRecentPlayersScRsp.newBuilder().setRetcode(0);
        for (FriendSearchService.RecentContact c : friendSearchService.listRecent(playerId, limit)) {
            GameSession s = sessionManager.getOrNull((int) c.otherId());
            PlayerProfileService.Profile p = profileService.getOrDefault((int) c.otherId());
            b.addPlayers(QolSocialSystemProto.PlayerSearchHit.newBuilder()
                    .setPlayerId((int) c.otherId())
                    .setNickname(c.nickname() == null ? "" : c.nickname())
                    .setLevel(c.level())
                    .setOnline(s != null)
                    .setCustomStatus(p.customStatus())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.GetTitleListScRsp handleGetTitles(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.GetTitleListScRsp.newBuilder().setRetcode(1).build();
        }
        QolSocialSystemProto.GetTitleListScRsp.Builder b =
                QolSocialSystemProto.GetTitleListScRsp.newBuilder().setRetcode(0);
        String equipped = "";
        for (TitleService.TitleView t : titleService.listForPlayer(playerId)) {
            if (t.equipped()) {
                equipped = t.titleId();
            }
            b.addTitles(QolSocialSystemProto.TitleEntry.newBuilder()
                    .setTitleId(t.titleId())
                    .setName(t.displayName())
                    .setSourceAchievement(t.sourceAchievement())
                    .setUnlocked(t.unlocked())
                    .setEquipped(t.equipped())
                    .build());
        }
        return b.setEquippedTitleId(equipped).build();
    }

    public QolSocialSystemProto.EquipTitleScRsp handleEquipTitle(
            QolSocialSystemProto.EquipTitleCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.EquipTitleScRsp.newBuilder().setRetcode(1).build();
        }
        TitleService.EquipResult r = titleService.equip(playerId, req.getTitleId());
        return QolSocialSystemProto.EquipTitleScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setTitleId(r.titleId())
                .setDisplayName(r.displayName())
                .build();
    }

    public QolSocialSystemProto.InviteFriendsToPartyScRsp handleInviteFriends(
            QolSocialSystemProto.InviteFriendsToPartyCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.InviteFriendsToPartyScRsp.newBuilder().setRetcode(1).build();
        }
        List<Long> targets = new ArrayList<>();
        for (int uid : req.getTargetUidsList()) {
            targets.add((long) uid);
        }
        PartyService.InviteManyResult r = partyService.inviteMany(playerId, targets);
        GameSession self = sessionManager.getOrNull(playerId);
        String nick = self == null || self.getNickname() == null ? "" : self.getNickname();
        String partyId = r.party() == null ? "" : r.party().partyId();
        for (Long tid : targets) {
            if (tid == null) {
                continue;
            }
            GameSession target = sessionManager.getOrNull(tid.intValue());
            if (target == null) {
                continue;
            }
            QolSocialSystemProto.PartyInviteScNotify notify =
                    QolSocialSystemProto.PartyInviteScNotify.newBuilder()
                            .setPartyId(partyId)
                            .setFromPlayerId(playerId)
                            .setFromNickname(nick)
                            .setAtMs(System.currentTimeMillis())
                            .build();
            target.send(new GamePacket(CmdIds.PARTY_INVITE_SC_NOTIFY, notify.toByteArray()));
            friendSearchService.recordRecent(playerId, tid, "party_invite");
        }
        int ret = switch (r.code()) {
            case OK -> 0;
            case NOT_IN_PARTY -> 2;
            case NOT_LEADER -> 3;
            case NOT_OWNER_NODE -> 8;
            default -> 5;
        };
        return QolSocialSystemProto.InviteFriendsToPartyScRsp.newBuilder()
                .setRetcode(ret)
                .setInvited(r.invited())
                .setSkipped(r.skipped())
                .build();
    }

    public QolSocialSystemProto.InviteGuildMembersScRsp handleInviteGuild(
            QolSocialSystemProto.InviteGuildMembersCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.InviteGuildMembersScRsp.newBuilder().setRetcode(1).build();
        }
        GuildService guild = guildServiceProvider.getIfAvailable();
        if (guild == null) {
            return QolSocialSystemProto.InviteGuildMembersScRsp.newBuilder().setRetcode(2).build();
        }
        Long gid = guild.findGuildIdByPlayer(playerId);
        if (gid == null) {
            return QolSocialSystemProto.InviteGuildMembersScRsp.newBuilder().setRetcode(3).build();
        }
        int max = req.getMaxCount() <= 0 ? 10 : Math.min(20, req.getMaxCount());
        List<Long> targets = new ArrayList<>();
        for (GuildService.MemberInfo m : guild.listMembers(gid)) {
            if (m.playerId() == playerId) {
                continue;
            }
            targets.add((long) m.playerId());
            if (targets.size() >= max) {
                break;
            }
        }
        PartyService.InviteManyResult r = partyService.inviteMany(playerId, targets);
        return QolSocialSystemProto.InviteGuildMembersScRsp.newBuilder()
                .setRetcode(0)
                .setInvited(r.invited())
                .setSkipped(r.skipped())
                .build();
    }

    public QolSocialSystemProto.GetGachaGuaranteeInfoScRsp handleGachaGuarantee(
            QolSocialSystemProto.GetGachaGuaranteeInfoCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.GetGachaGuaranteeInfoScRsp.newBuilder().setRetcode(1).build();
        }
        QolSocialSystemProto.GetGachaGuaranteeInfoScRsp.Builder b =
                QolSocialSystemProto.GetGachaGuaranteeInfoScRsp.newBuilder().setRetcode(0);
        for (GachaGuaranteeQueryService.GuaranteeInfo g :
                gachaGuaranteeQueryService.listGuaranteeInfo(playerId, req.getBannerType())) {
            b.addEntries(QolSocialSystemProto.GachaGuaranteeEntry.newBuilder()
                    .setBannerType(g.bannerType())
                    .setBannerName(g.bannerName())
                    .setPity5(g.pity5())
                    .setPity4(g.pity4())
                    .setRemainToHard5(g.remainToHard5())
                    .setFailedUpCount(g.failedUpCount())
                    .setSoftGuarantee5(g.softGuarantee5())
                    .putAllUpConstellationLayers(g.upConstellationLayers())
                    .build());
        }
        return b.build();
    }

    public QolSocialSystemProto.ExportTransactionHistoryScRsp handleExportHistory(
            QolSocialSystemProto.ExportTransactionHistoryCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QolSocialSystemProto.ExportTransactionHistoryScRsp.newBuilder().setRetcode(1).build();
        }
        TransactionHistoryExportService.ExportResult r = transactionHistoryExportService.export(
                playerId, req.getKind(), req.getDays() <= 0 ? 30 : req.getDays());
        return QolSocialSystemProto.ExportTransactionHistoryScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setMailHint(r.ok() ? "记录已发送至邮箱" : "")
                .setRecordCount(r.recordCount())
                .build();
    }
}
