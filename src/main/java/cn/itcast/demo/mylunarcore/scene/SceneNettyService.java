// 场景 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.MonsterConfigRepository;
import cn.itcast.demo.mylunarcore.repo.NpcConfigRepository;
import cn.itcast.demo.mylunarcore.repo.SceneConfigRepository;
import cn.itcast.demo.mylunarcore.repo.SummonUnitConfigRepository;
import cn.itcast.demo.mylunarcore.scene.SceneContext.NpcState;
import cn.itcast.demo.mylunarcore.scene.SceneContext.PropState;
import io.netty.channel.Channel;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.anticheat.MoveSpeedGuard;
import cn.itcast.demo.mylunarcore.center.PlayerMigrationService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.PlayerSessionStateMachine;
import cn.itcast.demo.mylunarcore.assist.EnvironmentNarrationService;
import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.dialogue.DialogueNode;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.player.PlayerLoadingStateService;
import cn.itcast.demo.mylunarcore.quest.QuestTriggerEngine;
import cn.itcast.demo.mylunarcore.story.StoryChapterService;
import cn.itcast.demo.mylunarcore.world.CellBoundaryHandoffService;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 场景协议业务：进入场景、查询当前信息、NPC 交互等，装配 {@link SceneContext} 并读各类 ConfigRepository。
 */
@Service // Spring Bean：场景玩法 Netty 消息分发入口
public class SceneNettyService {

    // 本类日志记录器（场景业务分类）
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, SceneNettyService.class); // 绑定场景业务分类 SLF4J 日志

    // Jackson JSON 读写器（解析 scene_config.groups JSON）
    // 场景实体 ID 自增种子（起始 1000000，避免与玩家 uid 冲突）
    private final SceneManager sceneManager;
    private final SceneConfigRepository sceneConfigRepository;
    @SuppressWarnings("unused")
    private final MonsterConfigRepository monsterConfigRepository;
    private final NpcConfigRepository npcConfigRepository;
    @SuppressWarnings("unused")
    private final SummonUnitConfigRepository summonUnitConfigRepository;
    private final PlayerContextResolver contextResolver;
    private final SceneSyncBroadcaster sceneSyncBroadcaster;
    private final QuestTriggerEngine questTriggerEngine;
    private final ZoneManager zoneManager;
    private final DynamicZoneLineAllocator zoneLineAllocator;
    private final PlayerMigrationService playerMigrationService;
    private final GameSessionManager sessionManager;
    private final EnvironmentNarrationService environmentNarrationService;
    private final AssistNettyService assistNettyService;
    private final EncounterConfigRepository encounterConfigRepository;
    private final RewardDistributor rewardDistributor;
    private final ZoneWorldService zoneWorldService;
    private final MoveSpeedGuard moveSpeedGuard;
    private final CellBoundaryHandoffService cellBoundaryHandoffService;
    private final DialogueTriggerEngine dialogueTriggerEngine;
    private final ObjectProvider<StoryChapterService> storyChapterProvider;
    private final ObjectProvider<PlayerLoadingStateService> loadingStateProvider;
    private final ObjectProvider<ScenePreloadService> preloadProvider;
    private final ObjectProvider<PlayerMoveService> playerMoveProvider;
    private final ObjectProvider<DevicePerfProbeService> perfProbeProvider;
    private final ObjectProvider<WorldTimeService> worldTimeProvider;
    private final ObjectProvider<DeathEchoService> deathEchoProvider;

    @Autowired
    private ObjectProvider<SceneCollisionMeshPushService> collisionMeshPushProvider;

    @Autowired
    private ObjectProvider<SceneInteractHandler> sceneInteractProvider;

    /**
     * 构造器注入场景相关依赖。
     */
    public SceneNettyService(SceneManager sceneManager,
                              SceneConfigRepository sceneConfigRepository,
                              MonsterConfigRepository monsterConfigRepository,
                              NpcConfigRepository npcConfigRepository,
                              SummonUnitConfigRepository summonUnitConfigRepository,
                              PlayerContextResolver contextResolver,
                              SceneSyncBroadcaster sceneSyncBroadcaster,
                              QuestTriggerEngine questTriggerEngine,
                              ZoneManager zoneManager,
                              DynamicZoneLineAllocator zoneLineAllocator,
                              PlayerMigrationService playerMigrationService,
                              GameSessionManager sessionManager,
                              EnvironmentNarrationService environmentNarrationService,
                              AssistNettyService assistNettyService,
                              EncounterConfigRepository encounterConfigRepository,
                              RewardDistributor rewardDistributor,
                              ZoneWorldService zoneWorldService,
                              MoveSpeedGuard moveSpeedGuard,
                              CellBoundaryHandoffService cellBoundaryHandoffService,
                              DialogueTriggerEngine dialogueTriggerEngine,
                              ObjectProvider<StoryChapterService> storyChapterProvider,
                              ObjectProvider<PlayerLoadingStateService> loadingStateProvider,
                              ObjectProvider<ScenePreloadService> preloadProvider,
                              ObjectProvider<PlayerMoveService> playerMoveProvider,
                              ObjectProvider<DevicePerfProbeService> perfProbeProvider,
                              ObjectProvider<WorldTimeService> worldTimeProvider,
                              ObjectProvider<DeathEchoService> deathEchoProvider) {
        this.sceneManager = sceneManager;
        this.sceneConfigRepository = sceneConfigRepository;
        this.monsterConfigRepository = monsterConfigRepository;
        this.npcConfigRepository = npcConfigRepository;
        this.summonUnitConfigRepository = summonUnitConfigRepository;
        this.contextResolver = contextResolver;
        this.sceneSyncBroadcaster = sceneSyncBroadcaster;
        this.questTriggerEngine = questTriggerEngine;
        this.zoneManager = zoneManager;
        this.zoneLineAllocator = zoneLineAllocator;
        this.playerMigrationService = playerMigrationService;
        this.sessionManager = sessionManager;
        this.environmentNarrationService = environmentNarrationService;
        this.assistNettyService = assistNettyService;
        this.encounterConfigRepository = encounterConfigRepository;
        this.rewardDistributor = rewardDistributor;
        this.zoneWorldService = zoneWorldService;
        this.moveSpeedGuard = moveSpeedGuard;
        this.cellBoundaryHandoffService = cellBoundaryHandoffService;
        this.dialogueTriggerEngine = dialogueTriggerEngine;
        this.storyChapterProvider = storyChapterProvider;
        this.loadingStateProvider = loadingStateProvider;
        this.preloadProvider = preloadProvider;
        this.playerMoveProvider = playerMoveProvider;
        this.perfProbeProvider = perfProbeProvider;
        this.worldTimeProvider = worldTimeProvider;
        this.deathEchoProvider = deathEchoProvider;
    }

    /**
     * 进入场景：加载 plane/floor 配置、解析 groups 实体并注册 SceneContext。
     */
    public SceneSystemProto.EnterSceneScRsp handleEnterScene(SceneSystemProto.EnterSceneCsReq req, Channel channel) { // 处理进入场景请求
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.EnterSceneScRsp.newBuilder() // 组装进入场景失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .build(); // 完成响应构建
        }

        int planeId = (int) req.getPlaneId();
        int floorId = (int) req.getFloorId();
        int entryId = (int) req.getEntryId();
        float posX = req.getPosX();
        float posY = req.getPosY();
        float posZ = req.getPosZ();

        String migrationTicket = req.getMigrationTicket();
        if (migrationTicket != null && !migrationTicket.isBlank()) {
            var ticket = playerMigrationService.consumeTicket(migrationTicket);
            if (ticket == null || ticket.playerUid() != uid) {
                return SceneSystemProto.EnterSceneScRsp.newBuilder()
                        .setRetcode(5) // retcode=5：迁移票据无效/过期/归属不符
                        .build();
            }
            planeId = ticket.planeId();
            floorId = ticket.floorId();
            entryId = ticket.entryId() > 0 ? ticket.entryId() : entryId;
            posX = ticket.posX();
            posY = ticket.posY();
            posZ = ticket.posZ();
        }

        StoryChapterService story = storyChapterProvider.getIfAvailable();
        if (story != null && !story.canEnterPlane(uid.intValue(), planeId, floorId)) {
            return SceneSystemProto.EnterSceneScRsp.newBuilder()
                    .setRetcode(8) // retcode=8：主线未解锁该 Plane/Floor
                    .build();
        }

        SceneContext.ScenePos playerPos = new SceneContext.ScenePos(posX, posY, posZ);

        SceneConfigRepository.SceneRow row = sceneConfigRepository.findGroups(planeId, floorId);
        if (row == null) {
            return SceneSystemProto.EnterSceneScRsp.newBuilder()
                    .setRetcode(2)
                    .build();
        }

        SceneContext previous = sceneManager.getByPlayerUid(uid);
        Integer lineId = zoneLineAllocator.pickJoinableLine(planeId, floorId, uid);
        if (lineId == null) {
            ZoneManager.JoinResult probe = zoneManager.checkCanJoin(planeId, floorId, uid);
            if (probe == ZoneManager.JoinResult.DRAINING) {
                return SceneSystemProto.EnterSceneScRsp.newBuilder().setRetcode(7).build();
            }
            return SceneSystemProto.EnterSceneScRsp.newBuilder().setRetcode(6).build();
        }
        int newZoneId = DynamicZoneLineAllocator.encodeZoneId(planeId, floorId, lineId);
        ZoneManager.JoinResult capacity = zoneManager.checkCanJoin(planeId, floorId, lineId, uid);
        if (capacity == ZoneManager.JoinResult.FULL) {
            return SceneSystemProto.EnterSceneScRsp.newBuilder().setRetcode(6).build();
        }
        if (capacity == ZoneManager.JoinResult.DRAINING) {
            return SceneSystemProto.EnterSceneScRsp.newBuilder().setRetcode(7).build();
        }
        SceneContext ctx = new SceneContext(uid, planeId, floorId, entryId, playerPos, newZoneId);
        if (previous != null && previous.getZoneId() != newZoneId) {
            zoneManager.leaveZone(previous.getZoneId(), uid);
        }
        zoneManager.joinZone(planeId, floorId, lineId, uid, playerPos);

        zoneWorldService.seedAndProject(ctx, row.getGroupsJson());
        ctx.setInitialized(true);

        sceneManager.put(uid, ctx);
        sessionManager.findByUid(uid).ifPresent(s ->
                PlayerSessionStateMachine.tryTransition(s, PlayerSessionState.SCENE));
        PlayerLoadingStateService loading = loadingStateProvider.getIfAvailable();
        // 切图流程会保持 LOADING 直至 SceneLoadComplete；普通 Enter 则立即就绪
        if (loading != null && !loading.isLoading(uid)) {
            loading.markSceneReady(uid);
        }
        SceneCollisionMeshPushService meshPush = collisionMeshPushProvider == null
                ? null : collisionMeshPushProvider.getIfAvailable();
        if (meshPush != null) {
            meshPush.pushOnEnter(channel, planeId);
        }
        return ctx.buildEnterSceneRsp(0);
    }

    public SceneSystemProto.MigrateSceneScRsp handleMigrateScene(SceneSystemProto.MigrateSceneCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.MigrateSceneScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerMigrationService.MigrationResult migration = playerMigrationService.migrate(
                uid,
                (int) req.getTargetPlaneId(),
                (int) req.getTargetFloorId(),
                (int) req.getTargetEntryId(),
                req.getPosX(),
                req.getPosY(),
                req.getPosZ());
        if (migration.retcode() == 4) {
            String ticket = migration.sessionTicket() == null ? "" : migration.sessionTicket();
            return SceneSystemProto.MigrateSceneScRsp.newBuilder()
                    .setRetcode(4)
                    .setZoneId(migration.zoneId())
                    .setRedirectHost(migration.redirectHost())
                    .setRedirectPort(migration.redirectPort())
                    .setSessionTicket(ticket)
                    .setLoadingTicket(ticket) // 跨节点：与 session_ticket 同值，目标节点 Enter 后 LoadComplete
                    .build();
        }
        if (!migration.success()) {
            return SceneSystemProto.MigrateSceneScRsp.newBuilder().setRetcode(migration.retcode()).build();
        }
        // 签发 LoadingTicket：若已预加载则仅作校验握手
        String loadingTicket = null;
        boolean preloadHit = false;
        SceneSystemProto.SceneLoadMaskInfo maskInfo = null;
        PlayerLoadingStateService loading = loadingStateProvider.getIfAvailable();
        ScenePreloadService preload = preloadProvider.getIfAvailable();
        int planeId = (int) req.getTargetPlaneId();
        int floorId = (int) req.getTargetFloorId();
        if (preload != null && preload.isReady(uid, planeId, floorId)) {
            preloadHit = true;
            preload.recordOutcome(uid, true);
            ScenePreloadService.MaskInfo mask = preload.resolveMask(uid, planeId, floorId);
            String trans = preload.resolveTransitionType(uid, true);
            mask = ScenePreloadService.MaskInfo.forPlane(planeId, floorId, trans,
                    layoutOf(uid), preload.hitRateBp(uid));
            maskInfo = toMaskProto(mask);
            preload.consumeIfMatch(uid, planeId, floorId);
        } else if (preload != null) {
            preload.recordOutcome(uid, false);
            String trans = preload.resolveTransitionType(uid, false);
            maskInfo = toMaskProto(ScenePreloadService.MaskInfo.forPlane(
                    planeId, floorId, trans, layoutOf(uid), preload.hitRateBp(uid)));
        }
        if (loading != null) {
            String trans = preload != null
                    ? preload.resolveTransitionType(uid, preloadHit)
                    : (preloadHit ? "DISSOLVE" : "BLACK");
            loadingTicket = loading.beginLoading(uid, planeId, floorId,
                    (int) req.getTargetEntryId(), req.getPosX(), req.getPosY(), req.getPosZ(),
                    preloadHit, trans).ticket();
        }
        // 本机切图：保留旧场景快照，Enter 失败则回滚 Zone 与 SceneContext
        SceneContext previous = sceneManager.getByPlayerUid(uid);
        SceneSystemProto.EnterSceneCsReq enterReq = SceneSystemProto.EnterSceneCsReq.newBuilder()
                .setPlaneId(req.getTargetPlaneId())
                .setFloorId(req.getTargetFloorId())
                .setEntryId(req.getTargetEntryId())
                .setPosX(req.getPosX())
                .setPosY(req.getPosY())
                .setPosZ(req.getPosZ())
                .build();
        SceneSystemProto.EnterSceneScRsp enterRsp = handleEnterScene(enterReq, channel);
        if (enterRsp.getRetcode() != 0) {
            if (loading != null) {
                loading.clear(uid);
            }
            if (previous != null && sceneManager.getByPlayerUid(uid) != previous) {
                zoneManager.tryJoinZone(previous.getPlaneId(), previous.getFloorId(), uid, previous.getPlayerPos());
                sceneManager.put(uid, previous);
            }
            return SceneSystemProto.MigrateSceneScRsp.newBuilder().setRetcode(enterRsp.getRetcode()).build();
        }
        SceneSystemProto.MigrateSceneScRsp.Builder rsp = SceneSystemProto.MigrateSceneScRsp.newBuilder()
                .setRetcode(0)
                .setZoneId(migration.zoneId())
                .setSceneInfo(enterRsp.getSceneInfo())
                .setPreloadHit(preloadHit);
        if (loadingTicket != null) {
            rsp.setLoadingTicket(loadingTicket);
        }
        if (maskInfo != null) {
            rsp.setMaskInfo(maskInfo);
        }
        return rsp.build();
    }

    /**
     * 接近传送门/边界：后台预加载目标 Plane 低模资源键（经 KCP 可靠下发）。
     */
    public SceneSystemProto.ScenePreloadScRsp handleScenePreload(SceneSystemProto.ScenePreloadCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.ScenePreloadScRsp.newBuilder().setRetcode(1).build();
        }
        int planeId = (int) req.getTargetPlaneId();
        int floorId = (int) req.getTargetFloorId();
        if (planeId <= 0) {
            return SceneSystemProto.ScenePreloadScRsp.newBuilder().setRetcode(2).build();
        }
        ScenePreloadService preload = preloadProvider.getIfAvailable();
        if (preload == null) {
            return SceneSystemProto.ScenePreloadScRsp.newBuilder().setRetcode(0)
                    .setTargetPlaneId(planeId).setTargetFloorId(floorId).build();
        }
        if (preload.isPreloadStorm(uid)) {
            return SceneSystemProto.ScenePreloadScRsp.newBuilder()
                    .setRetcode(5)
                    .setTargetPlaneId(planeId)
                    .setTargetFloorId(floorId)
                    .setStormThrottled(true)
                    .setMaskInfo(toMaskProto(ScenePreloadService.MaskInfo.forPlane(planeId, floorId, "BLACK", "", 0)))
                    .build();
        }
        ScenePreloadService.PreloadState state = preload.beginPreload(uid, planeId, floorId, (int) req.getTargetEntryId());
        SceneSystemProto.ScenePreloadScRsp.Builder b = SceneSystemProto.ScenePreloadScRsp.newBuilder()
                .setRetcode(0)
                .setTargetPlaneId(planeId)
                .setTargetFloorId(floorId)
                .setEstimatedMs(state == null ? 0 : 800)
                .setMaskInfo(toMaskProto(state == null
                        ? ScenePreloadService.MaskInfo.forPlane(planeId, floorId) : state.mask()));
        if (state != null) {
            b.addAllAssetKeys(state.assetKeys());
        }
        // 立即推送就绪（本机缓存标记；客户端可并行拉低模）
        SceneSystemProto.ScenePreloadReadyScNotify ready = SceneSystemProto.ScenePreloadReadyScNotify.newBuilder()
                .setTargetPlaneId(planeId)
                .setTargetFloorId(floorId)
                .setReady(true)
                .addAllAssetKeys(state == null ? List.of() : state.assetKeys())
                .build();
        channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                cn.itcast.demo.mylunarcore.net.CmdIds.SCENE_PRELOAD_READY_SC_NOTIFY, ready.toByteArray()));
        return b.build();
    }

    private static SceneSystemProto.SceneLoadMaskInfo toMaskProto(ScenePreloadService.MaskInfo mask) {
        if (mask == null) {
            return SceneSystemProto.SceneLoadMaskInfo.getDefaultInstance();
        }
        return SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                .setIllustrationId(mask.illustrationId())
                .setCharacterAnimId(mask.characterAnimId())
                .setTipText(mask.tipText())
                .setMaskStyle(mask.maskStyle())
                .setTransitionType(mask.transitionType() == null ? "BLACK" : mask.transitionType())
                .setRecommendedLayoutId(mask.recommendedLayoutId() == null ? "" : mask.recommendedLayoutId())
                .setHitRateBp(Math.max(0, mask.hitRateBp()))
                .setDurationMs(Math.max(0, mask.durationMs()))
                .build();
    }

    /**
     * 客户端加载完成：解除 LOADING，恢复移动/开战。
     * retcode: 1未登录 2票据无效
     */
    public SceneSystemProto.SceneLoadCompleteScRsp handleSceneLoadComplete(
            SceneSystemProto.SceneLoadCompleteCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.SceneLoadCompleteScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerLoadingStateService loading = loadingStateProvider.getIfAvailable();
        if (loading == null) {
            return SceneSystemProto.SceneLoadCompleteScRsp.newBuilder().setRetcode(0).build();
        }
        PlayerLoadingStateService.CompleteResult result = loading.completeLoadingResult(uid, req.getLoadingTicket());
        return SceneSystemProto.SceneLoadCompleteScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : 2)
                .setHandshakeOnly(result.handshakeOnly())
                .setTransitionType(result.transitionType() == null ? "BLACK" : result.transitionType())
                .build();
    }

    /**
     * 查询玩家当前所在场景的完整快照（怪物、NPC、道具等）。
     */
    public SceneSystemProto.GetCurSceneInfoScRsp handleGetCurSceneInfo(SceneSystemProto.GetCurSceneInfoCsReq req, Channel channel) { // 处理查询当前场景信息
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.GetCurSceneInfoScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .build(); // 完成响应构建
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid); // 按玩家 uid 查找场景上下文
        if (ctx == null || !ctx.isInitialized()) { // 玩家尚未进入场景或上下文缺失
            return SceneSystemProto.GetCurSceneInfoScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(2) // retcode=2：尚未进入场景或场景未初始化
                    .build(); // 完成响应构建
        }
        return ctx.buildCurSceneRsp(0); // 由 SceneContext 组装当前场景快照响应
    }

    /**
     * 处理玩家移动：战斗中冻结；轨迹向量 + 100ms 客户端预测；跨 AOI 格或位移超阈值才广播。
     * retcode: 1未登录 2无场景 3非法/超速 4战斗中 5切图加载中
     */
    public SceneSystemProto.MoveScRsp handleMove(SceneSystemProto.MoveCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.MoveScRsp.newBuilder().setRetcode(1).build();
        }
        PlayerLoadingStateService loading = loadingStateProvider.getIfAvailable();
        if (loading != null && loading.isLoading(uid)) {
            return SceneSystemProto.MoveScRsp.newBuilder().setRetcode(5).build(); // 切图加载中
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session != null && session.getSessionState() == PlayerSessionState.BATTLE) {
            return SceneSystemProto.MoveScRsp.newBuilder().setRetcode(4).build(); // 战斗中冻结移动
        }
        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (ctx == null || !ctx.isInitialized()) {
            return SceneSystemProto.MoveScRsp.newBuilder().setRetcode(2).build();
        }
        long now = System.currentTimeMillis();
        int moveState = MovementPhysics.normalizeState(req.getMoveStateValue());
        PlayerMoveService moveService = playerMoveProvider == null ? null : playerMoveProvider.getIfAvailable();
        MoveSpeedGuard.MoveCheckResult check;
        boolean predictionAccepted = false;
        int predictionWindow = PlayerMoveService.PREDICTION_WINDOW_MS;
        java.util.List<EnvInteractDetector.Trigger> micros = java.util.List.of();
        if (moveService != null) {
            PlayerMoveService.MoveAccept accept = moveService.acceptMove(ctx,
                    req.getPosX(), req.getPosY(), req.getPosZ(),
                    req.getVelocityX(), req.getVelocityZ(), req.getClientTickMs(), now, moveState);
            check = accept.check();
            predictionAccepted = accept.predictionAccepted();
            predictionWindow = moveService.predictionWindowMs();
            micros = accept.microInteracts();
        } else {
            check = moveSpeedGuard.validateAndMaybeApply(
                    ctx, req.getPosX(), req.getPosY(), req.getPosZ(), now);
        }
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        if (check != MoveSpeedGuard.MoveCheckResult.ACCEPT
                && check != MoveSpeedGuard.MoveCheckResult.CORRECTED_COLLISION) {
            // 拒绝超速/非法坐标：回写服务器权威位置
            return SceneSystemProto.MoveScRsp.newBuilder()
                    .setRetcode(3)
                    .setPos(SceneSystemProto.SceneVec3.newBuilder()
                            .setX(pos.getX())
                            .setY(pos.getY())
                            .setZ(pos.getZ())
                            .build())
                    .setPredictionAccepted(false)
                    .setPredictionWindowMs(predictionWindow)
                    .build();
        }
        // 碰撞纠正：retcode=0 但坐标为权威纠正点（客户端可平滑插值）
        zoneManager.updatePlayerPos(ctx.getZoneId(), uid, pos);
        // 无缝 Cell 边界移交检测（默认关；开启后仅钩子/日志，跨节点仍走 MigrationTicket）
        cellBoundaryHandoffService.onMoveAccepted(ctx, uid, pos.getX(), pos.getY(), pos.getZ());
        // cellSize 与 Zone AOI 动态格子对齐；位移阈值 1.0
        float cellSize = 20f;
        ZoneContext zone = zoneManager.get(ctx.getZoneId());
        if (zone != null) {
            cellSize = zone.getAoiGrid().getCellSize();
        }
        if (ctx.shouldBroadcastMove(pos.getX(), pos.getZ(), cellSize, 1.0f)) {
            sceneSyncBroadcaster.pushPlayerPositionUpdate(channel, uid, ctx.getZoneId(), pos, req.getRotY());
        }
        questTriggerEngine.onSceneEventGeneric(uid.intValue());
        environmentNarrationService.maybeNarrate(uid, ctx.getPlaneId(), pos.getX(), pos.getY(), pos.getZ())
                .ifPresent(n -> assistNettyService.pushEnvironmentNarration(channel, n.poiId(), n.title(), n.message(), n.source()));
        try {
            assistNettyService.onSceneMove(uid, ctx.getPlaneId(), pos.getX(), pos.getY(), pos.getZ());
        } catch (Exception ignored) {
            // 主动教练失败不影响移动权威
        }
        maybeForcePreload(uid, ctx, pos.getX(), pos.getZ(), channel);
        MovementPhysics.Feedback feedback = MovementPhysics.resolve(
                ctx.getPlaneId(), pos.getX(), pos.getY(), pos.getZ(), moveState, ctx.getLastMoveState());
        ctx.setLastMoveState(feedback.moveState());
        if (channel != null && channel.isActive()
                && (feedback.landing() || feedback.moveState() == MovementPhysics.JUMP
                || feedback.moveState() == MovementPhysics.CLIMB)) {
            SceneSystemProto.SceneInteractPhysicsScNotify physics =
                    SceneSystemProto.SceneInteractPhysicsScNotify.newBuilder()
                            .setPlayerUid(uid.intValue())
                            .setMoveState(toMoveState(feedback.moveState()))
                            .setFloorMaterial(feedback.floorMaterial())
                            .setHapticStrength(feedback.hapticStrength())
                            .setFootstepSfxId(feedback.footstepSfxId())
                            .setLanding(feedback.landing())
                            .build();
            channel.writeAndFlush(new GamePacket(CmdIds.SCENE_INTERACT_PHYSICS_SC_NOTIFY, physics.toByteArray()));
        }
        if (channel != null && channel.isActive() && micros != null) {
            for (EnvInteractDetector.Trigger t : micros) {
                SceneSystemProto.EnvMicroInteractType type =
                        SceneSystemProto.EnvMicroInteractType.forNumber(t.type().wire());
                if (type == null) {
                    type = SceneSystemProto.EnvMicroInteractType.ENV_MICRO_UNSPECIFIED;
                }
                SceneSystemProto.SceneEnvMicroInteractScNotify micro =
                        SceneSystemProto.SceneEnvMicroInteractScNotify.newBuilder()
                                .setPlayerUid(uid)
                                .setInteractType(type)
                                .setPos(SceneSystemProto.SceneVec3.newBuilder()
                                        .setX(t.x()).setY(t.y()).setZ(t.z()).build())
                                .setParticleId(t.type().particleId())
                                .setIntensity(t.intensity())
                                .build();
                channel.writeAndFlush(new GamePacket(CmdIds.SCENE_ENV_MICRO_INTERACT_SC_NOTIFY, micro.toByteArray()));
            }
        }
        return SceneSystemProto.MoveScRsp.newBuilder()
                .setRetcode(0)
                .setPos(SceneSystemProto.SceneVec3.newBuilder()
                        .setX(pos.getX())
                        .setY(pos.getY())
                        .setZ(pos.getZ())
                        .build())
                .setMoveState(toMoveState(feedback.moveState()))
                .setPredictionAccepted(predictionAccepted)
                .setPredictionWindowMs(predictionWindow)
                .build();
    }

    public SceneSystemProto.ReportDevicePerfScRsp handleReportDevicePerf(
            SceneSystemProto.ReportDevicePerfCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.ReportDevicePerfScRsp.newBuilder().setRetcode(1).build();
        }
        DevicePerfProbeService probe = perfProbeProvider == null ? null : perfProbeProvider.getIfAvailable();
        if (probe == null) {
            return SceneSystemProto.ReportDevicePerfScRsp.newBuilder().setRetcode(0).setAdjustPushed(false).build();
        }
        var adjust = probe.report(uid, req.getSocTempC(), (int) req.getFps(), (int) req.getBatteryPercent(), req.getDeviceId());
        boolean pushed = false;
        if (adjust.isPresent() && channel != null && channel.isActive()) {
            DevicePerfProbeService.Adjust a = adjust.get();
            SceneSystemProto.ScenePerformanceAdjustScNotify notify =
                    SceneSystemProto.ScenePerformanceAdjustScNotify.newBuilder()
                            .setRenderTier(a.renderTier())
                            .setDisableNpcSilhouette(a.disableNpcSilhouette())
                            .setReduceFarLod(a.reduceFarLod())
                            .setTargetFpsCap(a.targetFpsCap())
                            .setReason(a.reason())
                            .setSocTempC(a.socTempC())
                            .setPresetId(a.presetId() == null ? "" : a.presetId())
                            .setShadowQuality(Math.max(0, a.shadowQuality()))
                            .setParticleBudget(Math.max(0, a.particleBudget()))
                            .setViewDistance(a.viewDistance())
                            .build();
            channel.writeAndFlush(new GamePacket(CmdIds.SCENE_PERFORMANCE_ADJUST_SC_NOTIFY, notify.toByteArray()));
            pushed = true;
        }
        return SceneSystemProto.ReportDevicePerfScRsp.newBuilder().setRetcode(0).setAdjustPushed(pushed).build();
    }

    public SceneSystemProto.GetWorldTimeScRsp handleGetWorldTime(Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.GetWorldTimeScRsp.newBuilder().setRetcode(1).build();
        }
        WorldTimeService wts = worldTimeProvider == null ? null : worldTimeProvider.getIfAvailable();
        if (wts == null) {
            return SceneSystemProto.GetWorldTimeScRsp.newBuilder().setRetcode(0)
                    .setWorldTimeMs(System.currentTimeMillis())
                    .setPeriod(SceneSystemProto.WorldTimePeriod.NOON)
                    .build();
        }
        WorldTimeService.Snapshot snap = wts.snapshot();
        return SceneSystemProto.GetWorldTimeScRsp.newBuilder()
                .setRetcode(0)
                .setWorldTimeMs(snap.worldTimeMs())
                .setPeriod(WorldTimeService.toProto(snap.period()))
                .setPeriodElapsedMs((int) snap.periodElapsedMs())
                .setPeriodDurationMs((int) snap.periodDurationMs())
                .build();
    }

    public SceneSystemProto.ComfortDeathEchoScRsp handleComfortDeathEcho(
            SceneSystemProto.ComfortDeathEchoCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.ComfortDeathEchoScRsp.newBuilder().setRetcode(1).build();
        }
        DeathEchoService svc = deathEchoProvider == null ? null : deathEchoProvider.getIfAvailable();
        if (svc == null) {
            return SceneSystemProto.ComfortDeathEchoScRsp.newBuilder().setRetcode(2).setEchoId(req.getEchoId()).build();
        }
        int rc = svc.comfort(uid, req.getEchoId());
        return SceneSystemProto.ComfortDeathEchoScRsp.newBuilder()
                .setRetcode(rc)
                .setEchoId(req.getEchoId() == null ? "" : req.getEchoId())
                .build();
    }

    /** 战斗失败等入口：在当前位置留下荧光残影。 */
    public void spawnDeathEcho(long uid) {
        DeathEchoService svc = deathEchoProvider == null ? null : deathEchoProvider.getIfAvailable();
        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (svc == null || ctx == null || !ctx.isInitialized()) {
            return;
        }
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        svc.spawn(uid, ctx.getPlaneId(), pos.getX(), pos.getY(), pos.getZ());
    }

    private static SceneSystemProto.MoveState toMoveState(int state) {
        SceneSystemProto.MoveState mapped = SceneSystemProto.MoveState.forNumber(state);
        return mapped == null ? SceneSystemProto.MoveState.WALK : mapped;
    }

    /**
     * 与场景内 NPC 交互，返回对应对话 ID（演示实现：对话内容与选项为空）。
     */
    public SceneSystemProto.InteractNpcScRsp handleInteractNpc(SceneSystemProto.InteractNpcCsReq req, Channel channel) { // 处理 NPC 交互
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) { // uid 为空表示未登录
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(1) // retcode=1：未登录
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid); // 按玩家 uid 查找场景上下文
        if (ctx == null) { // 玩家尚未进入场景或上下文缺失
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(2) // retcode=2：玩家不在任何场景中
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        int entityId = (int) req.getEntityId(); // 请求交互的 NPC 实体 ID
        NpcState npc = ctx.getNpc(entityId); // 在场景上下文中查找 NPC
        if (npc == null) { // 场景内找不到该 NPC 实体
            return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互失败响应
                    .setRetcode(3) // retcode=3：目标 NPC 实体不存在
                    .setEntityId(req.getEntityId()) // 回写请求的 NPC 实体 ID
                    .build(); // 完成响应构建
        }

        NpcConfigRepository.NpcRow npcRow = npcConfigRepository.findById(npc.getNpcId()); // 按模板 ID 查 NPC 配置
        int dialogueId = npcRow == null ? 0 : npcRow.getDialogueId(); // 取对话 ID，无配置则为 0
        questTriggerEngine.onNpcInteract(uid.intValue(), npc.getNpcId());

        String dialogueContent = "";
        List<SceneSystemProto.NpcDialogOption> options = new ArrayList<>();
        if (dialogueId > 0) {
            DialogueTriggerEngine.DialogueStepResult step =
                    dialogueTriggerEngine.startByNpc(uid.intValue(), String.valueOf(dialogueId));
            if (step.ok() && step.node() != null) {
                DialogueNode node = step.node();
                dialogueContent = node.text() == null ? "" : node.text();
                for (DialogueNode.Choice choice : node.safeChoices()) {
                    int optionId = 0;
                    try {
                        if (choice.choiceId() != null && !choice.choiceId().isBlank()) {
                            // 支持纯数字 choiceId；非数字则用稳定哈希落入 uint32
                            optionId = choice.choiceId().chars().allMatch(Character::isDigit)
                                    ? Integer.parseInt(choice.choiceId())
                                    : Math.floorMod(choice.choiceId().hashCode(), 1_000_000);
                        }
                    } catch (NumberFormatException ignored) {
                        optionId = Math.floorMod(String.valueOf(choice.choiceId()).hashCode(), 1_000_000);
                    }
                    options.add(SceneSystemProto.NpcDialogOption.newBuilder()
                            .setOptionId(optionId)
                            .setText(choice.text() == null ? "" : choice.text())
                            .build());
                }
            }
        }

        return SceneSystemProto.InteractNpcScRsp.newBuilder() // 组装交互成功响应
                .setRetcode(0) // retcode=0：成功
                .setEntityId(req.getEntityId()) // 回写 NPC 实体 ID
                .setDialogueId(dialogueId) // 写入对应对话 ID
                .setDialogueContent(dialogueContent)
                .addAllOptions(options)
                .build(); // 完成响应构建
    }

    /**
     * 拾取场景内道具：标记已开启并按遭遇配置发放奖励。
     */
    public SceneSystemProto.PickupPropScRsp handlePickupProp(SceneSystemProto.PickupPropCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.PickupPropScRsp.newBuilder()
                    .setRetcode(1)
                    .setEntityId(req.getEntityId())
                    .setPropState(0)
                    .build();
        }

        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (ctx == null) {
            return SceneSystemProto.PickupPropScRsp.newBuilder()
                    .setRetcode(2)
                    .setEntityId(req.getEntityId())
                    .setPropState(0)
                    .build();
        }

        int entityId = (int) req.getEntityId();
        PropState prop = ctx.getProp(entityId);
        if (prop == null) {
            return SceneSystemProto.PickupPropScRsp.newBuilder()
                    .setRetcode(3)
                    .setEntityId(req.getEntityId())
                    .setPropState(0)
                    .build();
        }
        if (prop.getState() == 1) {
            return SceneSystemProto.PickupPropScRsp.newBuilder()
                    .setRetcode(4) // 已拾取
                    .setEntityId(req.getEntityId())
                    .setPropState(1)
                    .build();
        }

        prop.setState(1);
        zoneWorldService.updatePropState(ctx.getZoneId(), entityId, 1);
        int playerId = contextResolver.resolvePlayerId(channel);
        EncounterConfig encounter = encounterConfigRepository.current();
        List<SceneSystemProto.SceneRewardItem> rewardItems = new ArrayList<>();
        if (encounter.propPickupItemId() > 0 && encounter.propPickupCount() > 0) {
            List<EncounterConfig.DropEntry> drops = List.of(
                    new EncounterConfig.DropEntry(encounter.propPickupItemId(), encounter.propPickupCount(), null, null));
            List<RewardDistributor.GrantedItem> granted =
                    rewardDistributor.grantBattleRewards(playerId, drops, 0, "pickup:" + entityId);
            for (RewardDistributor.GrantedItem g : granted) {
                rewardItems.add(SceneSystemProto.SceneRewardItem.newBuilder()
                        .setItemId(g.itemId())
                        .setCount(g.count())
                        .build());
            }
            log.info("pickup prop granted, uid={}, entityId={}, itemId={}, count={}",
                    uid, entityId, encounter.propPickupItemId(), encounter.propPickupCount());
        }
        SceneInteractHandler interact = sceneInteractProvider == null
                ? null : sceneInteractProvider.getIfAvailable();
        if (interact != null) {
            try {
                interact.onPropPickup(playerId, ctx.getPlaneId(), ctx.getFloorId(), String.valueOf(entityId));
            } catch (Exception ignored) {
            }
        }

        return SceneSystemProto.PickupPropScRsp.newBuilder()
                .setRetcode(0)
                .setEntityId(req.getEntityId())
                .addAllRewardItems(rewardItems)
                .setPropState(1)
                .build();
    }

    /**
     * 触发场景事件（演示实现：仅确认触发，不更新实体状态）。
     */
    public SceneSystemProto.TriggerSceneEventScRsp handleTriggerSceneEvent(SceneSystemProto.TriggerSceneEventCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.TriggerSceneEventScRsp.newBuilder()
                    .setRetcode(1)
                    .setEntityId(req.getEntityId())
                    .build();
        }

        questTriggerEngine.onSceneEvent(uid.intValue(), req.getTriggerType());
        return SceneSystemProto.TriggerSceneEventScRsp.newBuilder()
                .setRetcode(0)
                .setEntityId(req.getEntityId())
                .build();
    }

    /**
     * 使用治疗泉：按遭遇配置恢复 HP，并标记复活点。
     */
    public SceneSystemProto.UseHealingSpringScRsp handleUseHealingSpring(SceneSystemProto.UseHealingSpringCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return SceneSystemProto.UseHealingSpringScRsp.newBuilder()
                    .setRetcode(1)
                    .setEntityId(req.getEntityId())
                    .build();
        }
        int hpRecovered = Math.max(0, encounterConfigRepository.current().healingSpringHp());
        if (hpRecovered <= 0) {
            hpRecovered = 500;
        }
        log.info("healing spring used, uid={}, entityId={}, hpRecovered={}", uid, req.getEntityId(), hpRecovered);
        return SceneSystemProto.UseHealingSpringScRsp.newBuilder()
                .setRetcode(0)
                .setEntityId(req.getEntityId())
                .setHealEffect(SceneSystemProto.HealEffect.newBuilder()
                        .setHpRecovered(hpRecovered)
                        .build())
                .setRespawnPointSet(true)
                .build();
    }

    private String layoutOf(long uid) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getRecommendedLayoutId() == null) {
            return "";
        }
        return session.getRecommendedLayoutId();
    }

    /**
     * 接近传送门 / 方向预判（提前 2~3 身位）且目标 Zone 空闲时，强制推送预加载资源键。
     */
    private void maybeForcePreload(long uid, SceneContext ctx, float x, float z, Channel channel) {
        ScenePreloadService preload = preloadProvider.getIfAvailable();
        if (preload == null || ctx == null || channel == null) {
            return;
        }
        float[] dir = preload.trackAndDirection(uid, x, z);
        ScenePreloadService.PortalHint portal = preload.detectApproach(
                ctx.getPlaneId(), x, z, dir[0], dir[1], true);
        int reason = 3; // 方向预判
        if (portal == null) {
            portal = preload.detectApproach(ctx.getPlaneId(), x, z);
            reason = 2; // Zone 空闲强制 / 半径接近
        }
        if (portal == null) {
            return;
        }
        if (preload.isReady(uid, portal.toPlaneId(), portal.toFloorId())) {
            return;
        }
        ZoneContext target = zoneManager.getOrCreate(portal.toPlaneId(), portal.toFloorId());
        int occ = target.getPlayerUids() == null ? 0 : target.getPlayerUids().size();
        if (occ >= 8) {
            return;
        }
        if (preload.isPreloadStorm(uid)) {
            SceneSystemProto.ScenePreloadPushScNotify storm = SceneSystemProto.ScenePreloadPushScNotify.newBuilder()
                    .setTargetPlaneId(portal.toPlaneId())
                    .setTargetFloorId(portal.toFloorId())
                    .setTargetEntryId(portal.toEntryId())
                    .setStormThrottled(true)
                    .setMaskInfo(toMaskProto(ScenePreloadService.MaskInfo.forPlane(
                            portal.toPlaneId(), portal.toFloorId(), "BLACK", layoutOf(uid), 0)))
                    .setReason(reason)
                    .build();
            channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                    cn.itcast.demo.mylunarcore.net.CmdIds.SCENE_PRELOAD_PUSH_SC_NOTIFY, storm.toByteArray()));
            return;
        }
        ScenePreloadService.PreloadState state = preload.beginPreload(uid, portal.toPlaneId(),
                portal.toFloorId(), portal.toEntryId());
        if (state == null) {
            return;
        }
        SceneSystemProto.ScenePreloadPushScNotify push = SceneSystemProto.ScenePreloadPushScNotify.newBuilder()
                .setTargetPlaneId(portal.toPlaneId())
                .setTargetFloorId(portal.toFloorId())
                .setTargetEntryId(portal.toEntryId())
                .addAllAssetKeys(state.assetKeys())
                .addAllLodPlaceholderKeys(preload.lodPlaceholderKeys(portal.toPlaneId(), portal.toFloorId()))
                .setMaskInfo(toMaskProto(state.mask().withLayout(layoutOf(uid))))
                .setReason(reason)
                .setStormThrottled(false)
                .build();
        channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                cn.itcast.demo.mylunarcore.net.CmdIds.SCENE_PRELOAD_PUSH_SC_NOTIFY, push.toByteArray()));
    }
}
