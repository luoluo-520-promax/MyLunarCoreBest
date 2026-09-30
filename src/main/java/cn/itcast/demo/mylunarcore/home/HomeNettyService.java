package cn.itcast.demo.mylunarcore.home;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import io.netty.channel.Channel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 家园协议适配（CmdId 850–859 / 870–874）：基建、互访、家具互动与 Avatar 表现同步。
 */
@Service
public class HomeNettyService {

    private final HomeBaseService homeBaseService;
    private final PlayerContextResolver playerContextResolver;
    private final HomePresenceService presenceService;
    private final FurnitureInteractHandler furnitureInteractHandler;
    private final GameSessionManager sessionManager;
    private final HomeVisitorLogService visitorLogService;
    private final cn.itcast.demo.mylunarcore.repo.FriendRepository friendRepository;
    private final HomeShadowGreetingService shadowGreetingService;

    public HomeNettyService(HomeBaseService homeBaseService,
                            PlayerContextResolver playerContextResolver,
                            HomePresenceService presenceService,
                            FurnitureInteractHandler furnitureInteractHandler,
                            ObjectProvider<GameSessionManager> sessionManagerProvider) {
        this(homeBaseService, playerContextResolver, presenceService, furnitureInteractHandler,
                sessionManagerProvider, null, null, null);
    }

    @Autowired
    public HomeNettyService(HomeBaseService homeBaseService,
                            PlayerContextResolver playerContextResolver,
                            HomePresenceService presenceService,
                            FurnitureInteractHandler furnitureInteractHandler,
                            ObjectProvider<GameSessionManager> sessionManagerProvider,
                            ObjectProvider<HomeVisitorLogService> visitorLogProvider,
                            ObjectProvider<cn.itcast.demo.mylunarcore.repo.FriendRepository> friendRepoProvider,
                            ObjectProvider<HomeShadowGreetingService> shadowGreetingProvider) {
        this.homeBaseService = homeBaseService;
        this.playerContextResolver = playerContextResolver;
        this.presenceService = presenceService;
        this.furnitureInteractHandler = furnitureInteractHandler;
        this.sessionManager = sessionManagerProvider == null ? null : sessionManagerProvider.getIfAvailable();
        this.visitorLogService = visitorLogProvider == null ? null : visitorLogProvider.getIfAvailable();
        this.friendRepository = friendRepoProvider == null ? null : friendRepoProvider.getIfAvailable();
        this.shadowGreetingService = shadowGreetingProvider == null ? null : shadowGreetingProvider.getIfAvailable();
    }

    public HomeNettyService(HomeBaseService homeBaseService,
                            PlayerContextResolver playerContextResolver,
                            HomePresenceService presenceService,
                            FurnitureInteractHandler furnitureInteractHandler,
                            ObjectProvider<GameSessionManager> sessionManagerProvider,
                            ObjectProvider<HomeVisitorLogService> visitorLogProvider) {
        this(homeBaseService, playerContextResolver, presenceService, furnitureInteractHandler,
                sessionManagerProvider, visitorLogProvider, null, null);
    }

    public HomeNettyService(HomeBaseService homeBaseService,
                            PlayerContextResolver playerContextResolver,
                            HomePresenceService presenceService,
                            FurnitureInteractHandler furnitureInteractHandler,
                            ObjectProvider<GameSessionManager> sessionManagerProvider,
                            ObjectProvider<HomeVisitorLogService> visitorLogProvider,
                            ObjectProvider<cn.itcast.demo.mylunarcore.repo.FriendRepository> friendRepoProvider) {
        this(homeBaseService, playerContextResolver, presenceService, furnitureInteractHandler,
                sessionManagerProvider, visitorLogProvider, friendRepoProvider, null);
    }

    /** 测试便捷构造：共享内存 Presence，不推送频道。 */
    public HomeNettyService(HomeBaseService homeBaseService, PlayerContextResolver playerContextResolver) {
        this.homeBaseService = homeBaseService;
        this.playerContextResolver = playerContextResolver;
        this.presenceService = new HomePresenceService();
        this.furnitureInteractHandler = new FurnitureInteractHandler(homeBaseService, this.presenceService);
        this.sessionManager = null;
        this.visitorLogService = null;
        this.friendRepository = null;
        this.shadowGreetingService = null;
    }

    public HomeSystemProto.GetHomeInfoScRsp handleGetInfo(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.GetHomeInfoScRsp.newBuilder().setRetcode(1).build();
        }
        presenceService.enter(uid, new HomePresenceService.Presence(uid, 0, 0, 0, 0, 0, "stand", 0));
        return HomeSystemProto.GetHomeInfoScRsp.newBuilder()
                .setRetcode(0)
                .setHome(toProto(homeBaseService.getOrCreate(uid)))
                .build();
    }

    public HomeSystemProto.HomePlaceFacilityScRsp handlePlace(HomeSystemProto.HomePlaceFacilityCsReq req,
                                                              Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomePlaceFacilityScRsp.newBuilder().setRetcode(1).build();
        }
        HomeBaseService.OpResult r = homeBaseService.placeFacility(uid, req.getFacilityId(), req.getLevel());
        HomeSystemProto.HomePlaceFacilityScRsp.Builder b = HomeSystemProto.HomePlaceFacilityScRsp.newBuilder()
                .setRetcode(r.retcode());
        if (r.state() != null) {
            b.setHome(toProto(r.state()));
        }
        return b.build();
    }

    public HomeSystemProto.HomeClaimProduceScRsp handleClaim(Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeClaimProduceScRsp.newBuilder().setRetcode(1).build();
        }
        HomeBaseService.OpResult r = homeBaseService.claimProduction(uid);
        HomeSystemProto.HomeClaimProduceScRsp.Builder b = HomeSystemProto.HomeClaimProduceScRsp.newBuilder()
                .setRetcode(r.retcode());
        if (r.success() && r.state() != null) {
            for (Map.Entry<Integer, Integer> e : r.state().pendingProduce().entrySet()) {
                b.putClaimed(e.getKey(), e.getValue());
            }
        }
        HomeBaseService.HomeState after = homeBaseService.getOrCreate(uid);
        b.setHome(toProto(after));
        return b.build();
    }

    public HomeSystemProto.HomeVisitScRsp handleVisit(HomeSystemProto.HomeVisitCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeVisitScRsp.newBuilder().setRetcode(1).build();
        }
        HomeBaseService.VisitResult r = homeBaseService.visit(uid, req.getHostPlayerId());
        HomeSystemProto.HomeVisitScRsp.Builder b = HomeSystemProto.HomeVisitScRsp.newBuilder()
                .setRetcode(r.retcode());
        if (r.hostState() != null) {
            b.setHostHome(toProto(r.hostState()));
            int hostId = req.getHostPlayerId();
            boolean friend = isFriend(hostId, uid);
            presenceService.enter(hostId, new HomePresenceService.Presence(
                    uid, 1, 0, 1, 0, 0, "stand", 0, !friend));
            for (HomePresenceService.Presence p : presenceService.list(hostId)) {
                b.addHostAvatars(toPresenceProto(p));
            }
            notifyRoom(hostId, 1, presenceService.list(hostId));
            if (visitorLogService != null && r.success()) {
                visitorLogService.recordVisit(hostId, uid);
            }
        }
        return b.build();
    }

    public HomeSystemProto.HomeShadowGreetingScRsp handleShadowGreeting(
            HomeSystemProto.HomeShadowGreetingCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeShadowGreetingScRsp.newBuilder().setRetcode(1).build();
        }
        if (shadowGreetingService == null) {
            return HomeSystemProto.HomeShadowGreetingScRsp.newBuilder()
                    .setRetcode(4).setHostPlayerId(req.getHostPlayerId()).build();
        }
        if (!isFriend(req.getHostPlayerId(), uid) && req.getHostPlayerId() != uid) {
            // 非好友家园也可问候房主虚影，但需在拜访态；此处仅校验 host 有效
            if (req.getHostPlayerId() <= 0) {
                return HomeSystemProto.HomeShadowGreetingScRsp.newBuilder().setRetcode(2).build();
            }
        }
        HomeShadowGreetingService.Result r = shadowGreetingService.greet(
                uid, "旅人" + uid, req.getHostPlayerId(), req.getSilhouetteUid(), req.getGreetingType());
        return HomeSystemProto.HomeShadowGreetingScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setHostPlayerId(r.hostPlayerId())
                .setReserveStaminaGifted(r.reserveGifted())
                .build();
    }

    public HomeSystemProto.HomePlaceFurnitureScRsp handlePlaceFurniture(
            HomeSystemProto.HomePlaceFurnitureCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomePlaceFurnitureScRsp.newBuilder().setRetcode(1).build();
        }
        HomeBaseService.OpResult r = homeBaseService.placeFurniture(
                uid, req.getFurnitureId(), req.getX(), req.getY(), req.getZ(), req.getRotateY());
        HomeSystemProto.HomePlaceFurnitureScRsp.Builder b = HomeSystemProto.HomePlaceFurnitureScRsp.newBuilder()
                .setRetcode(r.retcode());
        if (r.state() != null) {
            b.setHome(toProto(r.state()));
        }
        return b.build();
    }

    public HomeSystemProto.HomeFurnitureInteractScRsp handleFurnitureInteract(
            HomeSystemProto.HomeFurnitureInteractCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeFurnitureInteractScRsp.newBuilder().setRetcode(1).build();
        }
        int hostId = uid;
        FurnitureInteractHandler.InteractResult r = furnitureInteractHandler.interact(
                hostId, uid, req.getFurnitureId(), req.getAction(),
                req.getX(), req.getY(), req.getZ(), req.getRotY(), 0);
        HomeSystemProto.HomeFurnitureInteractScRsp.Builder b =
                HomeSystemProto.HomeFurnitureInteractScRsp.newBuilder().setRetcode(r.retcode());
        if (r.presence() != null) {
            b.setSelfPresence(toPresenceProto(r.presence()));
            notifyRoom(hostId, 1, List.of(r.presence()));
        }
        return b.build();
    }

    public HomeSystemProto.HomeUpdatePresenceScRsp handleUpdatePresence(
            HomeSystemProto.HomeUpdatePresenceCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeUpdatePresenceScRsp.newBuilder().setRetcode(1).build();
        }
        HomePresenceService.Presence p = new HomePresenceService.Presence(
                uid, req.getX(), req.getY(), req.getZ(), req.getRotY(),
                req.getSkinId(), req.getIdleAnim(), 0);
        presenceService.update(uid, p);
        notifyRoom(uid, 1, List.of(p));
        return HomeSystemProto.HomeUpdatePresenceScRsp.newBuilder().setRetcode(0).build();
    }

    public HomeSystemProto.GetHomeVisitorLogScRsp handleGetVisitorLog(int limit, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.GetHomeVisitorLogScRsp.newBuilder().setRetcode(1).build();
        }
        HomeSystemProto.GetHomeVisitorLogScRsp.Builder b =
                HomeSystemProto.GetHomeVisitorLogScRsp.newBuilder().setRetcode(0);
        if (visitorLogService != null) {
            for (HomeVisitorLogService.LogEntry e : visitorLogService.list(uid, limit)) {
                b.addEntries(toLogProto(e));
            }
        }
        return b.build();
    }

    public HomeSystemProto.HomeLeaveMessageScRsp handleLeaveMessage(
            HomeSystemProto.HomeLeaveMessageCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeLeaveMessageScRsp.newBuilder().setRetcode(1).build();
        }
        if (visitorLogService == null) {
            return HomeSystemProto.HomeLeaveMessageScRsp.newBuilder().setRetcode(0).build();
        }
        if (req.getLike()) {
            visitorLogService.recordLike(req.getHostPlayerId(), uid);
        }
        HomeVisitorLogService.LogEntry entry = visitorLogService.recordMessage(
                req.getHostPlayerId(), uid, req.getMessage());
        return HomeSystemProto.HomeLeaveMessageScRsp.newBuilder()
                .setRetcode(entry == null ? 2 : 0)
                .build();
    }

    public HomeSystemProto.HomeHarvestAssistScRsp handleHarvestAssist(
            HomeSystemProto.HomeHarvestAssistCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return HomeSystemProto.HomeHarvestAssistScRsp.newBuilder().setRetcode(1).build();
        }
        HomeBaseService.AssistResult r = homeBaseService.harvestAssist(uid, req.getHostPlayerId(), req.getFacilityId());
        if (r.success()) {
            String facilityName = "设施" + req.getFacilityId();
            String facilityIcon = "home_facility_" + req.getFacilityId();
            String helperName = "Player" + uid;
            pushAssistEffect(req.getHostPlayerId(), req.getFacilityId(), facilityName, facilityIcon,
                    uid, helperName, r.reducedCdMs());
            if (visitorLogService != null) {
                visitorLogService.recordAssist(req.getHostPlayerId(), uid, req.getFacilityId(),
                        facilityName, facilityIcon);
            }
            if (r.claimedOverflow()) {
                pushOverflow(req.getHostPlayerId(), req.getFacilityId(), r.overflowItemId(), r.overflowCount(), uid);
            }
        }
        return HomeSystemProto.HomeHarvestAssistScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setHostPlayerId(req.getHostPlayerId())
                .setFacilityId(req.getFacilityId())
                .setReducedCdMs(r.reducedCdMs())
                .setClaimedOverflow(r.claimedOverflow())
                .build();
    }

    public void pushOverflowIfNeeded(int hostPlayerId) {
        HomeBaseService.OverflowHint hint = homeBaseService.overflowIfAny(hostPlayerId);
        if (hint != null) {
            pushOverflow(hostPlayerId, 0, hint.itemId(), hint.count(), 0);
        }
    }

    private void pushOverflow(int hostPlayerId, int facilityId, int itemId, int count, int helperId) {
        if (sessionManager == null) {
            return;
        }
        HomeSystemProto.HomeOverflowNotify notify = HomeSystemProto.HomeOverflowNotify.newBuilder()
                .setHostPlayerId(hostPlayerId)
                .setFacilityId(facilityId)
                .setItemId(itemId)
                .setOverflowCount(count)
                .setHelperPlayerId(helperId)
                .setHelperName(helperId > 0 ? "Player" + helperId : "")
                .setReason(helperId > 0 ? "assist_claim" : "overflow")
                .build();
        sessionManager.findByUid(hostPlayerId).ifPresent(s -> {
            if (s.getChannel() != null && s.getChannel().isActive()) {
                s.getChannel().writeAndFlush(new GamePacket(CmdIds.HOME_OVERFLOW_SC_NOTIFY, notify.toByteArray()));
            }
        });
    }

    /** 登录后推送未读拜访日志；无未读时返回空 entries。 */
    public HomeSystemProto.HomeVisitorLogNotify buildVisitorLogNotify(int hostPlayerId) {
        HomeSystemProto.HomeVisitorLogNotify.Builder b = HomeSystemProto.HomeVisitorLogNotify.newBuilder();
        if (visitorLogService == null) {
            return b.build();
        }
        List<HomeVisitorLogService.LogEntry> unread = visitorLogService.drainUnread(hostPlayerId);
        for (HomeVisitorLogService.LogEntry e : unread) {
            b.addEntries(toLogProto(e));
        }
        b.setUnreadCount(unread.size());
        return b.build();
    }

    private void notifyRoom(int hostPlayerId, int syncType, List<HomePresenceService.Presence> avatars) {
        if (sessionManager == null || avatars == null || avatars.isEmpty()) {
            return;
        }
        HomeSystemProto.HomeAvatarSyncScNotify.Builder n = HomeSystemProto.HomeAvatarSyncScNotify.newBuilder()
                .setHostPlayerId(hostPlayerId)
                .setSyncType(syncType);
        for (HomePresenceService.Presence p : avatars) {
            n.addAvatars(toPresenceProto(p));
        }
        byte[] payload = n.build().toByteArray();
        for (HomePresenceService.Presence occupant : presenceService.list(hostPlayerId)) {
            sessionManager.findByUid(occupant.playerUid()).ifPresent(s -> {
                if (s.getChannel() != null && s.getChannel().isActive()) {
                    s.getChannel().writeAndFlush(new GamePacket(CmdIds.HOME_AVATAR_SYNC_SC_NOTIFY, payload));
                }
            });
        }
    }

    private void pushAssistEffect(int hostPlayerId, int facilityId, String facilityName, String facilityIcon,
                                  int helperId, String helperName, int reducedCdMs) {
        if (sessionManager == null) {
            return;
        }
        String floatText = helperName + " 帮你加速了产量！";
        HomeSystemProto.HomeAssistEffectScNotify notify = HomeSystemProto.HomeAssistEffectScNotify.newBuilder()
                .setHostPlayerId(hostPlayerId)
                .setFacilityId(facilityId)
                .setFacilityName(facilityName == null ? "" : facilityName)
                .setFacilityIcon(facilityIcon == null ? "" : facilityIcon)
                .setHelperPlayerId(helperId)
                .setHelperName(helperName == null ? "" : helperName)
                .setFloatText(floatText)
                .setVfxId("golden_flash")
                .setReducedCdMs(Math.max(0, reducedCdMs))
                .build();
        sessionManager.findByUid(hostPlayerId).ifPresent(s -> {
            if (s.getChannel() != null && s.getChannel().isActive()) {
                s.getChannel().writeAndFlush(new GamePacket(CmdIds.HOME_ASSIST_EFFECT_SC_NOTIFY, notify.toByteArray()));
            }
        });
    }

    private boolean isFriend(int a, int b) {
        if (friendRepository == null || a <= 0 || b <= 0) {
            return false;
        }
        try {
            cn.itcast.demo.mylunarcore.model.FriendEntity rel = friendRepository.findRelation(a, b);
            return rel != null && rel.getStatus() == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private static HomeSystemProto.HomeVisitorLogEntry toLogProto(HomeVisitorLogService.LogEntry e) {
        return HomeSystemProto.HomeVisitorLogEntry.newBuilder()
                .setVisitorId(e.visitorId())
                .setAction(e.action() == null ? "" : e.action())
                .setMessage(e.message() == null ? "" : e.message())
                .setAtMs(e.atMs())
                .setUnread(e.unread())
                .setFacilityId(Math.max(0, e.facilityId()))
                .setFacilityName(e.facilityName() == null ? "" : e.facilityName())
                .setFacilityIcon(e.facilityIcon() == null ? "" : e.facilityIcon())
                .build();
    }

    private static HomeSystemProto.HomeAvatarPresence toPresenceProto(HomePresenceService.Presence p) {
        return HomeSystemProto.HomeAvatarPresence.newBuilder()
                .setPlayerUid(p.playerUid())
                .setX(p.x())
                .setY(p.y())
                .setZ(p.z())
                .setRotY(p.rotY())
                .setSkinId(Math.max(0, p.skinId()))
                .setIdleAnim(p.idleAnim())
                .setInteractFurnitureId(Math.max(0, p.interactFurnitureId()))
                .setSilhouette(p.silhouette())
                .build();
    }

    private static HomeSystemProto.HomeInfo toProto(HomeBaseService.HomeState s) {
        HomeSystemProto.HomeInfo.Builder b = HomeSystemProto.HomeInfo.newBuilder()
                .setPlayerId(s.playerId())
                .setStamina(Math.max(0, s.stamina()))
                .setStaminaCap(Math.max(0, s.staminaCap()));
        s.facilities().forEach(b::putFacilities);
        s.pendingProduce().forEach(b::putPendingProduce);
        for (HomeBaseService.FurnitureItem f : s.furniture()) {
            b.addFurniture(HomeSystemProto.HomeFurnitureItem.newBuilder()
                    .setFurnitureId(f.furnitureId())
                    .setX(f.x())
                    .setY(f.y())
                    .setZ(f.z())
                    .setRotateY(f.rotateY())
                    .build());
        }
        return b.build();
    }
}
