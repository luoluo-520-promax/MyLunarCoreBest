// 战斗 Netty 协议业务服务所在包
package cn.itcast.demo.mylunarcore.battle;

// 战斗主表持久化
import cn.itcast.demo.mylunarcore.repo.BattleRepository;
// 关卡波次配置
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
// Buff 叠层上限查询
import cn.itcast.demo.mylunarcore.repo.MazeBuffRepository;
// 技能行为链
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
// 技能主表
import cn.itcast.demo.mylunarcore.repo.MazeSkillRepository;
import cn.itcast.demo.mylunarcore.repo.SummonUnitConfigRepository;
// 战局场景工厂
import cn.itcast.demo.mylunarcore.battle.BattleSceneFactory;
// 单场战斗内存状态
import cn.itcast.demo.mylunarcore.battle.BattleContext;
// 单波运行时
import cn.itcast.demo.mylunarcore.battle.WaveRuntime;
// 战局注册表
import cn.itcast.demo.mylunarcore.battle.BattleManager;
// 统计 JSON 工具
import cn.itcast.demo.mylunarcore.battle.BattleStatisticsUtil;
// 实体 HP/Buff 状态
import cn.itcast.demo.mylunarcore.battle.EntityState;
// 怪物实例
import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;
// 领域事件发布
import cn.itcast.demo.mylunarcore.common.GameEventPublisher;
// 战斗结束事件
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
// 战斗开始事件
import cn.itcast.demo.mylunarcore.common.BattleStartedEvent;
// 协议命令号
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 下行游戏包封装
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 战斗系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
// Netty 连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类（战斗业务等）
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.anticheat.BattleAuditService;
import cn.itcast.demo.mylunarcore.anticheat.BattleDeterministicValidator;
import cn.itcast.demo.mylunarcore.assist.AiAutoSuggestionFactory;
import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.assist.SuggestedAutoOverride;
import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.PlayerSessionStateMachine;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneSyncBroadcaster;
import cn.itcast.demo.mylunarcore.scene.ZoneContext;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.scene.ZoneWorldService;
import cn.itcast.demo.mylunarcore.hall.SupportService;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.player.PlayerLoadingStateService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.story.StoryChapterService;
// SLF4J 日志接口
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
// Spring 服务 Bean
import org.springframework.stereotype.Service;

// SQL 时间戳类型
import java.sql.Timestamp;
// 可变数组列表
import java.util.ArrayList;
// 不可修改集合工厂方法
import java.util.Collections;
// 比较器：实体快照稳定排序
import java.util.Comparator;
// 列表接口
import java.util.List;
import java.util.Optional;

/**
 * 战斗协议业务入口：处理开战、技能释放、回合推进等 Netty 消息，协调 {@link cn.itcast.demo.mylunarcore.battle.BattleManager} 与迷宫技能仓储。
 */
@Service // 注册为 Spring Bean，作为战斗协议 Netty 消息入口
public class BattleNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleNettyService.class);

    private final BattleManager battleManager;
    private final BattleRepository battleRepository;
    private final BattleMonsterWaveRepository waveRepository;
    private final MazeSkillRepository skillRepository;
    private final MazeSkillActionRepository skillActionRepository;
    private final MazeBuffRepository buffRepository;
    private final SummonUnitConfigRepository summonUnitConfigRepository;
    private final BattleSceneFactory battleSceneFactory;
    private final GameEventPublisher gameEventPublisher;
    private final PlayerContextResolver contextResolver;
    private final BattleAssistPolicy battleAssistPolicy;
    private final LunarCoreProperties properties;
    private final SceneManager sceneManager;
    private final SceneSyncBroadcaster sceneSyncBroadcaster;
    private final GameSessionManager sessionManager;
    private final EncounterConfigRepository encounterConfigRepository;
    private final RewardDistributor rewardDistributor;
    private final ZoneWorldService zoneWorldService;
    private final ZoneManager zoneManager;
    private final BattleAuditService battleAuditService;
    private final PartyService partyService;
    private final BattleDeterministicValidator deterministicValidator;
    private final ObjectProvider<StaminaService> staminaProvider;
    private final ObjectProvider<SupportService> supportProvider;
    private final ObjectProvider<StoryChapterService> storyChapterProvider;
    private final ObjectProvider<PlayerLoadingStateService> loadingStateProvider;
    private final ObjectProvider<cn.itcast.demo.mylunarcore.challenge.SweepService> sweepProvider;
    private final ObjectProvider<BattleAutoService> battleAutoProvider;

    @Autowired
    private ObjectProvider<PlayerInputBufferService> inputBufferProvider;
    @Autowired
    private ObjectProvider<BattleSnapshotService> snapshotProvider;
    @Autowired
    private ObjectProvider<BuffModifierCatalog> buffModifierCatalogProvider;
    @Autowired
    private ObjectProvider<BattleCorrectionService> battleCorrectionProvider;
    @Autowired
    private ObjectProvider<CancelWindowService> cancelWindowProvider;
    @Autowired
    private ObjectProvider<BattleQteService> battleQteProvider;

    /**
     * 构造器注入战斗相关依赖。
     */
    public BattleNettyService(BattleManager battleManager,
                               BattleRepository battleRepository,
                               BattleMonsterWaveRepository waveRepository,
                               MazeSkillRepository skillRepository,
                               MazeSkillActionRepository skillActionRepository,
                               MazeBuffRepository buffRepository,
                               SummonUnitConfigRepository summonUnitConfigRepository,
                               BattleSceneFactory battleSceneFactory,
                               GameEventPublisher gameEventPublisher,
                               PlayerContextResolver contextResolver,
                               BattleAssistPolicy battleAssistPolicy,
                               LunarCoreProperties properties,
                               SceneManager sceneManager,
                               SceneSyncBroadcaster sceneSyncBroadcaster,
                               GameSessionManager sessionManager,
                               EncounterConfigRepository encounterConfigRepository,
                               RewardDistributor rewardDistributor,
                               ZoneWorldService zoneWorldService,
                               ZoneManager zoneManager,
                               BattleAuditService battleAuditService,
                               PartyService partyService,
                               BattleDeterministicValidator deterministicValidator,
                               ObjectProvider<StaminaService> staminaProvider,
                               ObjectProvider<SupportService> supportProvider,
                               ObjectProvider<StoryChapterService> storyChapterProvider,
                               ObjectProvider<PlayerLoadingStateService> loadingStateProvider,
                               ObjectProvider<cn.itcast.demo.mylunarcore.challenge.SweepService> sweepProvider) {
        this(battleManager, battleRepository, waveRepository, skillRepository, skillActionRepository,
                buffRepository, summonUnitConfigRepository, battleSceneFactory, gameEventPublisher,
                contextResolver, battleAssistPolicy, properties, sceneManager, sceneSyncBroadcaster,
                sessionManager, encounterConfigRepository, rewardDistributor, zoneWorldService, zoneManager,
                battleAuditService, partyService, deterministicValidator, staminaProvider, supportProvider,
                storyChapterProvider, loadingStateProvider, sweepProvider, null);
    }

    @Autowired
    public BattleNettyService(BattleManager battleManager,
                               BattleRepository battleRepository,
                               BattleMonsterWaveRepository waveRepository,
                               MazeSkillRepository skillRepository,
                               MazeSkillActionRepository skillActionRepository,
                               MazeBuffRepository buffRepository,
                               SummonUnitConfigRepository summonUnitConfigRepository,
                               BattleSceneFactory battleSceneFactory,
                               GameEventPublisher gameEventPublisher,
                               PlayerContextResolver contextResolver,
                               BattleAssistPolicy battleAssistPolicy,
                               LunarCoreProperties properties,
                               SceneManager sceneManager,
                               SceneSyncBroadcaster sceneSyncBroadcaster,
                               GameSessionManager sessionManager,
                               EncounterConfigRepository encounterConfigRepository,
                               RewardDistributor rewardDistributor,
                               ZoneWorldService zoneWorldService,
                               ZoneManager zoneManager,
                               BattleAuditService battleAuditService,
                               PartyService partyService,
                               BattleDeterministicValidator deterministicValidator,
                               ObjectProvider<StaminaService> staminaProvider,
                               ObjectProvider<SupportService> supportProvider,
                               ObjectProvider<StoryChapterService> storyChapterProvider,
                               ObjectProvider<PlayerLoadingStateService> loadingStateProvider,
                               ObjectProvider<cn.itcast.demo.mylunarcore.challenge.SweepService> sweepProvider,
                               ObjectProvider<BattleAutoService> battleAutoProvider) {
        this.battleManager = battleManager;
        this.battleRepository = battleRepository;
        this.waveRepository = waveRepository;
        this.skillRepository = skillRepository;
        this.skillActionRepository = skillActionRepository;
        this.buffRepository = buffRepository;
        this.summonUnitConfigRepository = summonUnitConfigRepository;
        this.battleSceneFactory = battleSceneFactory;
        this.gameEventPublisher = gameEventPublisher;
        this.contextResolver = contextResolver;
        this.battleAssistPolicy = battleAssistPolicy;
        this.properties = properties;
        this.sceneManager = sceneManager;
        this.sceneSyncBroadcaster = sceneSyncBroadcaster;
        this.sessionManager = sessionManager;
        this.encounterConfigRepository = encounterConfigRepository;
        this.rewardDistributor = rewardDistributor;
        this.zoneWorldService = zoneWorldService;
        this.zoneManager = zoneManager;
        this.battleAuditService = battleAuditService;
        this.partyService = partyService;
        this.deterministicValidator = deterministicValidator;
        this.staminaProvider = staminaProvider;
        this.supportProvider = supportProvider;
        this.storyChapterProvider = storyChapterProvider;
        this.loadingStateProvider = loadingStateProvider;
        this.sweepProvider = sweepProvider;
        this.battleAutoProvider = battleAutoProvider;
    }

    /**
     * 开始战斗：创建 battle 记录、构建运行时上下文并返回首帧敌方信息。
     * <p>若携带 scene_entity_uid，则绑定场景怪、校验/映射 stage，并切入 BATTLE 状态。</p>
     */
    public BattleSystemProto.FightStartScRsp handleFightStart(BattleSystemProto.FightStartCsReq req, Channel channel) {
        Long uid = contextResolver.resolveUid(channel).stream().boxed().findFirst().orElse(null);
        if (uid == null) {
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(1)
                    .build();
        }
        PlayerLoadingStateService loading = loadingStateProvider.getIfAvailable();
        if (loading != null && loading.isLoading(uid)) {
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(9) // 切图加载中禁止开战
                    .build();
        }
        if (req.getLineupId() <= 0) {
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(2)
                    .build();
        }

        int playerId = contextResolver.resolvePlayerId(channel);
        if (battleManager.findActiveByPlayerId(playerId) != null) {
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(5) // 已有进行中战局
                    .build();
        }
        if (req.getConsumeStamina()) {
            StaminaService stamina = staminaProvider.getIfAvailable();
            if (stamina != null) {
                StaminaService.ConsumeResult consumed = stamina.tryConsumeDungeon(playerId);
                if (!consumed.ok()) {
                    return BattleSystemProto.FightStartScRsp.newBuilder()
                            .setRetcode(consumed.retcode() == 2 ? 10 : 2) // 10=体力不足
                            .build();
                }
            }
        }

        int sceneEntityUid = (int) req.getSceneEntityUid();
        int clientStageId = (int) req.getBattleStageId();
        int resolvedStageId = clientStageId;
        int sceneMonsterId = 0;
        SceneContext sceneCtx = null;

        if (sceneEntityUid > 0) {
            sceneCtx = sceneManager.getByPlayerUid(uid);
            if (sceneCtx == null || !sceneCtx.isInitialized()) {
                return BattleSystemProto.FightStartScRsp.newBuilder()
                        .setRetcode(6) // 不在场景中
                        .build();
            }
            SceneContext.MonsterState monster = sceneCtx.getMonster(sceneEntityUid);
            if (monster == null) {
                // 回退查 Zone 权威（可能视图尚未同步）
                ZoneContext zone = zoneManager.get(sceneCtx.getZoneId());
                ZoneContext.ZoneMonster zm = zone != null ? zone.getMonster(sceneEntityUid) : null;
                if (zm == null || !zm.isAlive()) {
                    return BattleSystemProto.FightStartScRsp.newBuilder()
                            .setRetcode(7) // 场景怪不存在
                            .build();
                }
                sceneMonsterId = zm.getMonsterId();
            } else {
                sceneMonsterId = monster.getMonsterId();
            }
            EncounterConfig encounter = encounterConfigRepository.current();
            resolvedStageId = encounter.resolveStageId(sceneMonsterId, clientStageId);
            if (resolvedStageId <= 0) {
                return BattleSystemProto.FightStartScRsp.newBuilder()
                        .setRetcode(2)
                        .build();
            }
            // 客户端传了 stage 时做一致性校验（允许 0 表示完全交给服务端映射）
            if (clientStageId > 0 && clientStageId != resolvedStageId) {
                EncounterConfig.EncounterEntry entry = encounter.find(sceneMonsterId);
                if (entry != null && entry.battleStageId() > 0) {
                    return BattleSystemProto.FightStartScRsp.newBuilder()
                            .setRetcode(8) // stage 与遭遇配置不一致
                            .build();
                }
            }
        } else if (clientStageId <= 0) {
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(2)
                    .build();
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;

        long battleId;
        try {
            battleId = battleRepository.insertBattle(playerId, (int) req.getLineupId(), resolvedStageId,
                    new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("insertBattle failed, playerId={}, stageId={}, lineupId={}", playerId, resolvedStageId, req.getLineupId(), e);
            return BattleSystemProto.FightStartScRsp.newBuilder().setRetcode(3).build();
        }

        List<BattleMonsterWaveRepository.WaveConfig> waves;
        try {
            waves = waveRepository.loadWavesByStageId(resolvedStageId);
        } catch (Exception e) {
            log.warn("loadWavesByStageId failed, stageId={}", resolvedStageId, e);
            return BattleSystemProto.FightStartScRsp.newBuilder()
                    .setRetcode(4)
                    .build();
        }

        BattleContext context = battleSceneFactory.createBattleScene(battleId,
                playerId,
                (int) req.getLineupId(),
                resolvedStageId,
                nowSeconds,
                waves);

        if (sceneCtx != null) {
            SceneContext.ScenePos pos = sceneCtx.getPlayerPos();
            context.bindWorldAnchor(uid, sceneEntityUid, sceneMonsterId,
                    sceneCtx.getPlaneId(), sceneCtx.getFloorId(), sceneCtx.getEntryId(),
                    pos.getX(), pos.getY(), pos.getZ());
        } else {
            context.bindWorldAnchor(uid, 0, 0, 0, 0, 0, 0f, 0f, 0f);
        }

        GameSession session = sessionManager.getOrNull(uid);
        if (session != null && !PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.BATTLE)) {
            // 非 SCENE/可切状态时仍允许直接 stage 开战，但记录告警
            log.warn("SCENE→BATTLE transition failed, uid={}, state={}", uid, session.getSessionState());
        }

        SupportService support = supportProvider.getIfAvailable();
        if (support != null && req.getSupportFriendUid() > 0) {
            SupportService.SupportSnapshot snap =
                    support.borrowFriendSupport(playerId, (int) req.getSupportFriendUid());
            if (snap != null) {
                context.addParticipant(snap.ownerPlayerId());
                log.info("support unit enrolled battleId={} owner={} avatarId={}",
                        battleId, snap.ownerPlayerId(), snap.avatarId());
            }
        }

        if (!battleManager.put(context)) {
            if (battleManager.instancePool().isQueued(context.getBattleId())) {
                // 排队：仍返回开战成功，客户端等待；配额满拒绝则 retcode=8
            } else {
                return BattleSystemProto.FightStartScRsp.newBuilder().setRetcode(8).build();
            }
        }
        gameEventPublisher.publish(new BattleStartedEvent(
                battleId,
                playerId,
                context.getBattleStageId(),
                context.getLineupId(),
                context.getWaveCount(),
                nowSeconds
        ));

        BattleSystemProto.StageInfo stageInfo = BattleSystemProto.StageInfo.newBuilder()
                .setId(context.getBattleStageId())
                .setWaveCount(context.getWaveCount())
                .build();

        List<BattleSystemProto.EnemyInfo> enemyInfo = new ArrayList<>();
        for (int i = 0; i < context.getWaves().size(); i++) {
            WaveRuntime wave = context.getWaves().get(i);
            BattleSystemProto.EnemyInfo.Builder waveBuilder = BattleSystemProto.EnemyInfo.newBuilder()
                    .setWaveIndex(i + 1);
            for (MonsterRuntime monster : wave.getMonsters()) {
                waveBuilder.addMonsters(BattleSystemProto.MonsterInfo.newBuilder()
                        .setId(monster.getRuntimeEntityId())
                        .setLevel(monster.getLevel())
                        .setHp(monster.getHp())
                        .setMaxHp(monster.getMaxHp())
                        .addAllBuffs(Collections.emptyList())
                        .build());
            }
            enemyInfo.add(waveBuilder.build());
        }

        BattleSystemProto.FightStartScRsp rsp = BattleSystemProto.FightStartScRsp.newBuilder()
                .setRetcode(0)
                .setBattleId(battleId)
                .setStageInfo(stageInfo)
                .addAllEnemyInfo(enemyInfo)
                .setStartTime(nowSeconds)
                .build();
        // Party 共战：同队成员扇入同一 battleId 并推送开战包
        enrollPartyParticipants(uid, context, rsp);
        return rsp;
    }

    /**
     * 处理战斗行动请求（当前重点支持 actionType=1 的技能释放）。
     *
     * @param req 行动请求
     * @param channel 客户端连接
     * @return 行动结算响应；会校验战斗归属、战斗状态、技能合法性与目标合法性
     */
    public BattleSystemProto.FightActionScRsp handleFightAction(BattleSystemProto.FightActionCsReq req, Channel channel) { // 处理战斗行动（技能释放等）
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(6) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        long battleId = req.getBattleId(); // 从请求中读取战斗 ID
        BattleContext context = battleManager.get(battleId); // 按 battleId 查找内存战局
        if (context == null) { // 内存中找不到对应战局
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 战局不存在或已过期
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) { // 校验请求者是否为共战参与者
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(7) // 非本人战局，拒绝操作
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (context.isEnded()) { // 战局已结束则拒绝继续操作
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2)
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        long nowMs = System.currentTimeMillis();
        // Hit-stop 防外挂：校验客户端顿帧结束时间戳
        BattleDeterministicValidator.HitStopValidation hitStopCheck =
                deterministicValidator.validateHitStopEnd(context, context.getExpectedHitStopEndMs(),
                        req.getClientHitStopEndMs(), nowMs);
        if (!hitStopCheck.accepted()) {
            return BattleSystemProto.FightActionScRsp.newBuilder()
                    .setRetcode(11) // 顿帧未结束 / 外挂缩短顿帧
                    .setBattleId(battleId)
                    .build();
        }

        int actionType = (int) req.getActionType(); // 读取行动类型（1=技能，10=智能托管）
        int skillId = (int) req.getSkillId(); // 读取技能 ID
        java.util.List<Integer> targetIds = new ArrayList<>(req.getTargetIdsList());

        // 预输入缓冲：后摇/顿帧窗口内不丢指令，按取消层级入队
        PlayerInputBufferService inputBuffer = inputBufferProvider == null ? null : inputBufferProvider.getIfAvailable();
        if (inputBuffer != null && actionType != 10) {
            int entityKey = req.getCasterId() > 0 ? (int) req.getCasterId() : currentPlayerId;
            if (inputBuffer.enqueueOrExecuteNow(battleId, entityKey, currentPlayerId, actionType, skillId,
                    entityKey, targetIds, nowMs)) {
                return BattleSystemProto.FightActionScRsp.newBuilder()
                        .setRetcode(0)
                        .setBattleId(battleId)
                        .setCurrentState(buildCurrentState(context))
                        .build();
            }
        }

        // 方案 D：actionType=10 智能托管 —— 仅 PVE 开关开启时由策略填充技能与目标
        if (actionType == 10) {
            if (!properties.getAiAssist().isBattleHintEnabled() || !battleAssistPolicy.isEnabledFor(context)) {
                return BattleSystemProto.FightActionScRsp.newBuilder()
                        .setRetcode(8) // 智能托管未开启
                        .setBattleId(battleId)
                        .build();
            }
            int actorId = req.getCasterId() > 0 ? (int) req.getCasterId() : context.getPlayerId();
            BattleAssistPolicy.Suggestion suggestion = battleAssistPolicy.suggest(context, actorId);
            if (suggestion.skillId() <= 0 || suggestion.targetIds().isEmpty()) {
                return BattleSystemProto.FightActionScRsp.newBuilder()
                        .setRetcode(4)
                        .setBattleId(battleId)
                        .build();
            }
            actionType = 1;
            skillId = suggestion.skillId();
            targetIds = new ArrayList<>(suggestion.targetIds());
            log.info("auto-battle applied battleId={} skillId={} targets={} reason={}",
                    battleId, skillId, targetIds, suggestion.reason());
        }

        List<BattleSystemProto.ActionResult> results = new ArrayList<>(); // 收集各目标的行动结算结果
        List<BattleFxComposer.FxHint> fxHints = new ArrayList<>();
        // 以战局锁串行化行动结算，避免并发请求导致同一回合内状态覆盖或重复结算
        synchronized (context.getLock()) { // 加战局锁，串行化本回合状态变更
            if (actionType == 1) { // actionType=1 表示释放技能
                if (skillId <= 0) { // 技能 ID 必须大于 0
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(3) // 技能 ID 无效
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }
                if (skillRepository.findById(skillId) == null) { // 校验技能是否在配置表中存在
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(4) // 技能配置不存在
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }

                if (targetIds.isEmpty()) { // 技能释放必须指定至少一个目标
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(5)
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }

                List<BattleContext.MazeSkillActionRuntime> actions = context.getResolvedActionsForSkill(skillId, skillActionRepository); // 解析技能对应的行为链配置

                for (Integer targetId : targetIds) { // 逐个目标结算技能效果
                    EntityState entity = context.getEntity(targetId); // 在战局实体表中查找目标
                    if (entity == null) { // 目标实体不在当前战局中
                        // 目标不存在时仍返回占位结果，便于客户端对齐目标列表
                        results.add(BattleSystemProto.ActionResult.newBuilder() // 将单目标结算结果加入响应列表
                                .setTargetId(targetId) // 写入行动目标实体 ID
                                .setHpChange(0) // HP 变化为 0（无效果或目标不存在）
                                .setMpChange(0) // MP 变化为 0
                                .build());
                        continue; // 跳过当前循环迭代
                    }
                    int hpChangeTotal = 0; // 累计本目标 HP 变化量
                    List<Integer> addedBuffs = new ArrayList<>(); // 记录本目标新增的 Buff ID
                    List<Integer> removedBuffs = new ArrayList<>(); // 记录本目标移除的 Buff ID

                    for (BattleContext.MazeSkillActionRuntime action : actions) { // 按顺序执行技能行为链中的每个 action
                        switch (action.getActionType()) { // 按 action_type 分发到不同效果处理器
                            case 1: { // modify hp — 按配置修改生命值；击破后伤害 1.5 倍
                                int delta = action.tryParseHpDelta();
                                int toughnessHit = action.tryParseToughnessDelta();
                                if (toughnessHit == 0 && delta < 0) {
                                    toughnessHit = Math.max(1, (-delta) / 2);
                                }
                                if (toughnessHit > 0 && !entity.isDead()) {
                                    entity.reduceToughness(toughnessHit);
                                }
                                if (delta != 0 && !entity.isDead()) {
                                    if (delta < 0 && entity.isBroken()) {
                                        delta = (int) Math.floor(delta * 1.5);
                                    }
                                    if (delta < 0) {
                                        // 服务端权威审计：按双方属性重跑伤害公式，不信任客户端申报（当前协议无申报字段）。
                                        // 正式角色面板接入后，可将落地 HP 改为纯公式结果。
                                        int attackerId = req.getCasterId() > 0
                                                ? (int) req.getCasterId() : context.getPlayerId();
                                        deterministicValidator.validateAndCompute(context,
                                                new BattleDeterministicValidator.DamageIntent(
                                                        attackerId, targetId, skillId, Math.max(50, -delta), 0));
                                    }
                                    int before = entity.getHp();
                                    int after = Math.max(0, before + delta);
                                    entity.setHp(after);
                                    if (after == 0) {
                                        entity.setDead(true);
                                    }
                                    hpChangeTotal += (after - before);
                                }
                                break;
                            }
                            case 2: { // add buff — 叠加 Buff 层数
                                List<Integer> buffIds = action.tryParseBuffIds();
                                for (Integer buffId : buffIds) {
                                    if (entity.isDead()) {
                                        continue;
                                    }
                                    int maxStack = buffRepository.findMaxStack(buffId);
                                    int oldStacks = entity.getBuffStacks().getOrDefault(buffId, 0);
                                    if (oldStacks < maxStack) {
                                        BuffModifierCatalog catalog = buffModifierCatalogProvider == null
                                                ? null : buffModifierCatalogProvider.getIfAvailable();
                                        if (catalog != null) {
                                            entity.addBuffStack(buffId, 1, maxStack, catalog::perStack);
                                        } else {
                                            entity.addBuffStack(buffId, 1, maxStack);
                                        }
                                        if (oldStacks == 0) {
                                            addedBuffs.add(buffId);
                                        }
                                    }
                                }
                                break;
                            }
                            case 3: { // 击中道具/机关：按 HP 变化结算（无配置则 -1）
                                int delta = action.tryParseHpDelta();
                                if (delta == 0) {
                                    delta = -1;
                                }
                                if (!entity.isDead()) {
                                    int before = entity.getHp();
                                    int after = Math.max(0, before + delta);
                                    entity.setHp(after);
                                    if (after == 0) {
                                        entity.setDead(true);
                                    }
                                    hpChangeTotal += (after - before);
                                }
                                break;
                            }
                            case 4: { // 召唤单位
                                int summonConfigId = action.tryParseSummonId();
                                if (summonConfigId > 0) {
                                    SummonUnitConfigRepository.SummonUnitRow cfg =
                                            summonUnitConfigRepository.findById(summonConfigId);
                                    int summonHp = cfg != null && cfg.getHp() > 0 ? cfg.getHp() : 300;
                                    int summonEntityId = 900_000 + summonConfigId;
                                    if (context.getEntity(summonEntityId) == null) {
                                        EntityState summon = new EntityState(summonEntityId, summonHp, false);
                                        context.putEntity(summon);
                                        results.add(BattleSystemProto.ActionResult.newBuilder()
                                                .setTargetId(summonEntityId)
                                                .setHpChange(summonHp)
                                                .setMpChange(0)
                                                .build());
                                    }
                                }
                                break;
                            }
                            case 5: { // set death — 强制击杀
                                if (action.tryParseKillTrue()) {
                                    if (!entity.isDead()) {
                                        int before = entity.getHp();
                                        entity.setHp(0);
                                        entity.setDead(true);
                                        hpChangeTotal += -before;
                                    }
                                }
                                break;
                            }
                            default:
                                break;
                        }
                    }

                    BattleSystemProto.ActionResult.Builder r = BattleSystemProto.ActionResult.newBuilder() // 构建单目标行动结果 Protobuf
                            .setTargetId(entity.getId()) // 写入行动目标实体 ID
                            .setHpChange(hpChangeTotal) // 写入本目标累计 HP 变化
                            .setMpChange(0); // 当前技能链不涉及 MP 变化

                    for (Integer b : addedBuffs) { // 将新增 Buff 写入 ActionResult
                        r.addAddedBuffs(b); // 追加单个新增 Buff ID
                    }
                    for (Integer b : removedBuffs) { // 将移除 Buff 写入 ActionResult
                        r.addRemovedBuffs(b); // 追加单个移除 Buff ID
                    }
                    results.add(r.build()); // 将单目标结算结果加入响应列表
                    if (hpChangeTotal < 0 || entity.isDead()) {
                        BattleFxComposer.FxHint fx = BattleFxComposer.compose(
                                hpChangeTotal, entity.isDead(), skillId, entity.isBroken(), entity.getId());
                        fxHints.add(fx);
                    }
                }
            } else { // 非技能类行动（actionType≠1）走空操作分支
                // action_type=2/3 等其它行动类型暂为空操作，仍返回零变化结果
                for (Integer targetId : req.getTargetIdsList()) { // 逐个目标结算技能效果
                    results.add(BattleSystemProto.ActionResult.newBuilder() // 将单目标结算结果加入响应列表
                            .setTargetId(targetId) // 写入行动目标实体 ID
                            .setHpChange(0) // HP 变化为 0（无效果或目标不存在）
                            .setMpChange(0) // MP 变化为 0
                            .build());
                }
            }

            // 波次切换放在本次行动结算末尾，确保客户端观察到「先结算伤害，再进下一波」的稳定时序
            boolean allDead = context.isAllMonstersDeadInWave(context.getCurrentWave()); // 判断当前波次怪物是否已全部死亡
            if (allDead && context.getCurrentWave() < context.getWaveCount()) { // 当前波怪物全灭且仍有后续波次
                int nextWave = context.getCurrentWave() + 1; // 计算下一波次序号
                context.switchToWave(nextWave); // 切换到下一波怪物

                BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
                BattleSystemProto.FightStateScNotify notify = BattleSystemProto.FightStateScNotify.newBuilder()
                        .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                        .setUpdateType(3) // updateType=3 表示波次推进
                        .setStateSnapshot(snapshot) // 写入状态快照到推送通知
                        .setExtraDataJson("{}") // 附加扩展 JSON（当前为空对象）
                        .build();
                channel.writeAndFlush(new GamePacket(CmdIds.FIGHT_STATE_SC_NOTIFY, notify.toByteArray())); // 主动推送战斗状态变更通知给客户端
            }

            // 无论行动类型是否造成伤害都推进回合计数，确保状态机前进可预测
            context.incrementTurn(); // 推进回合计数器
            battleManager.checkpoint(context);
        }

        BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
        if (!fxHints.isEmpty() && channel != null && channel.isActive()) {
            BattleSystemProto.BattleFxScNotify.Builder fxNotify = BattleSystemProto.BattleFxScNotify.newBuilder()
                    .setBattleId(battleId)
                    .setSkillId(skillId)
                    .setCasterId(req.getCasterId());
            GameSession fxSession = sessionManager.getOrNull(currentPlayerId);
            int rtt = fxSession == null ? 0 : fxSession.getRttMs();
            int loss = fxSession == null ? 0 : fxSession.getPacketLossBp();
            fxNotify.setRttMs(Math.max(0, rtt));
            fxNotify.setPacketLossBp(Math.max(0, loss));
            fxNotify.setFxQualityLevel(FxQualityAdvisor.resolve(rtt, loss));
            boolean anyCrit = false;
            boolean anyKill = false;
            HitStopComposer.HitStopPlan mergedHitStop = HitStopComposer.HitStopPlan.empty();
            BattleSnapshotService snaps = snapshotProvider == null ? null : snapshotProvider.getIfAvailable();
            for (BattleFxComposer.FxHint fx : fxHints) {
                anyCrit |= fx.critical();
                anyKill |= fx.kill();
                fxNotify.addFx(BattleSystemProto.BattleFxMeta.newBuilder()
                        .setCritical(fx.critical())
                        .setWeakness(fx.weakness())
                        .setKill(fx.kill())
                        .setCameraShake(fx.cameraShake())
                        .setCameraShakeIntensity(fx.cameraShakeIntensity())
                        .setCameraFovImpact(fx.cameraFovImpact())
                        .setTimeScale(fx.timeScale())
                        .setDamagePopupStyle(fx.damagePopupStyle())
                        .setDisplayDamage(fx.displayDamage())
                        .setTargetEntityId(fx.targetEntityId())
                        .build());
                if (fx.hitStop() != null && fx.hitStop().totalMs() > mergedHitStop.totalMs()) {
                    mergedHitStop = fx.hitStop();
                }
                // 普攻后开启取消窗口
                if (skillId < 1000 && fx.displayDamage() > 0) {
                    CancelWindowService cancel = cancelWindowProvider == null
                            ? null : cancelWindowProvider.getIfAvailable();
                    if (cancel != null) {
                        cancel.open(battleId, (int) req.getCasterId(), currentPlayerId,
                                PlayerInputBufferService.CancelTier.BASIC.priority(),
                                CancelWindowService.DEFAULT_BASIC_CANCEL_MS);
                    }
                }
                BattleQteService qte = battleQteProvider == null ? null : battleQteProvider.getIfAvailable();
                if (qte != null && (fx.kill() || fx.weakness())) {
                    qte.maybeTrigger(battleId, currentPlayerId, fx.weakness(), fx.kill());
                }
                if (snaps != null) {
                    snaps.recordDelta(battleId, (int) req.getCasterId(), skillId, fx.targetEntityId(),
                            fx.displayDamage(), fx.critical(), fx.kill(),
                            Optional.ofNullable(context.getEntity(fx.targetEntityId()))
                                    .map(EntityState::getHp).orElse(0));
                }
            }
            for (HitStopComposer.HitStopSpec spec : mergedHitStop.frames()) {
                fxNotify.addHitStopFrames(BattleSystemProto.HitStopFrame.newBuilder()
                        .setTriggerFrame(spec.triggerFrame())
                        .setDurationMs(spec.durationMs())
                        .build());
            }
            long expectedEnd = System.currentTimeMillis() + mergedHitStop.totalMs();
            fxNotify.setHitStopTotalMs(mergedHitStop.totalMs());
            fxNotify.setExpectedHitStopEndMs(expectedEnd);
            context.setExpectedHitStopEndMs(expectedEnd);
            PlayerInputBufferService buf = inputBufferProvider == null ? null : inputBufferProvider.getIfAvailable();
            if (buf != null) {
                buf.openActionWindow(battleId, expectedEnd);
            }
            boolean counter = skillId > 0 && skillId % 5 == 0;
            fxNotify.setHapticIntensity(cn.itcast.demo.mylunarcore.settings.DeviceHapticsConfigService
                    .pickIntensity(anyCrit, anyKill, counter));
            fxNotify.setWaveformId(cn.itcast.demo.mylunarcore.settings.DeviceHapticsConfigService
                    .pickWaveformId(anyCrit, anyKill, counter));
            channel.writeAndFlush(new GamePacket(CmdIds.BATTLE_FX_SC_NOTIFY, fxNotify.build().toByteArray()));
        }
        // 客户端预测时间对齐：proto field client_estimated_time_ms（wire v4）；偏差超阈下发修正
        BattleCorrectionService correction = battleCorrectionProvider == null
                ? null : battleCorrectionProvider.getIfAvailable();
        if (correction != null) {
            long clientEst = readClientEstimatedTimeMs(req);
            if (clientEst > 0 && correction.needsTimeCorrection(clientEst, System.currentTimeMillis())) {
                correction.push(currentPlayerId, new BattleCorrectionService.Correction(
                        battleId, (int) req.getCasterId(), 0, false, System.currentTimeMillis(), "client_time_skew"));
            }
        }
        return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .addAllActionResults(results) // 写入各目标行动结算结果
                .setCurrentState(snapshot) // 写入战局当前快照
                .build();
    }

    /**
     * 方案 D：仅返回战术建议，不改战局权威状态。
     */
    public AssistSystemProto.AskBattleHintScRsp handleAskBattleHint(AssistSystemProto.AskBattleHintCsReq req, Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return AssistSystemProto.AskBattleHintScRsp.newBuilder().setRetcode(1).setBattleId(req.getBattleId()).build();
        }
        if (!properties.getAiAssist().isBattleHintEnabled()) {
            return AssistSystemProto.AskBattleHintScRsp.newBuilder().setRetcode(2).setBattleId(req.getBattleId()).build();
        }
        BattleContext context = battleManager.get(req.getBattleId());
        if (context == null || !isBattleParticipant(context, currentPlayerId) || context.isEnded()) {
            return AssistSystemProto.AskBattleHintScRsp.newBuilder().setRetcode(3).setBattleId(req.getBattleId()).build();
        }
        int actorId = req.getActorEntityId() > 0 ? (int) req.getActorEntityId() : context.getPlayerId();
        BattleAssistPolicy.Suggestion suggestion = battleAssistPolicy.suggest(context, actorId);
        if (suggestion.skillId() <= 0) {
            return AssistSystemProto.AskBattleHintScRsp.newBuilder()
                    .setRetcode(4)
                    .setBattleId(req.getBattleId())
                    .build();
        }
        AssistSystemProto.AskBattleHintScRsp.Builder builder = AssistSystemProto.AskBattleHintScRsp.newBuilder()
                .setRetcode(0)
                .setBattleId(req.getBattleId())
                .setSuggestedSkillId(suggestion.skillId())
                .setReason(suggestion.reason() == null ? "" : suggestion.reason())
                .setWeaknessAdvice(suggestion.weaknessAdvice() == null ? "" : suggestion.weaknessAdvice())
                .setSwitchAdvice(suggestion.switchAdvice() == null ? "" : suggestion.switchAdvice());
        for (Integer tid : suggestion.targetIds()) {
            builder.addSuggestedTargetIds(tid);
        }
        SuggestedAutoOverride auto = AiAutoSuggestionFactory.fromSuggestion(suggestion);
        if (auto.isPresent()) {
            builder.setSuggestedAutoOverride(AssistNettyService.toAutoOverrideProto(auto));
        }
        if (suggestion.battleStateSummary() != null && !suggestion.battleStateSummary().isBlank()) {
            builder.setBattleStateSummary(suggestion.battleStateSummary());
        }
        builder.setEstimatedWaitMs(120);
        return builder.build();
    }

    /**
     * 一键采纳 AI 建议的 Auto 策略权重，无需进入二级菜单。
     */
    public AssistSystemProto.ApplyAiSuggestionScRsp handleApplyAiSuggestion(
            AssistSystemProto.ApplyAiSuggestionCsReq req, Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder().setRetcode(1).setBattleId(req.getBattleId()).build();
        }
        if (!properties.getAiAssist().isBattleHintEnabled() && !properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder().setRetcode(2).setBattleId(req.getBattleId()).build();
        }
        BattleContext context = battleManager.get(req.getBattleId());
        if (context == null || !isBattleParticipant(context, currentPlayerId) || context.isEnded()) {
            return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder().setRetcode(3).setBattleId(req.getBattleId()).build();
        }
        AssistSystemProto.SuggestedAutoOverride override = req.getOverride();
        int strategy = override.getSkillPriority();
        int focus = override.getTargetFocus();
        if (strategy < 0 || strategy > 3 || focus < 0 || focus > 2) {
            return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder().setRetcode(4).setBattleId(req.getBattleId()).build();
        }
        boolean enable = req.getEnableAuto() || !context.isAutoBattle();
        if (override.getUltReserve() && strategy == 0) {
            strategy = 3; // SAVE_ENERGY
        }
        if (strategy == 0) {
            strategy = 1;
        }
        BattleAutoService auto = battleAutoProvider == null ? null : battleAutoProvider.getIfAvailable();
        boolean ok;
        if (auto != null) {
            ok = auto.enableAuto(req.getBattleId(), currentPlayerId, enable,
                    "ai_suggestion", strategy, focus);
        } else {
            synchronized (context.getLock()) {
                if (enable && !context.setAutoStrategy(strategy)) {
                    return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder()
                            .setRetcode(4).setBattleId(req.getBattleId()).build();
                }
                if (enable && !context.setTargetFocus(focus)) {
                    return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder()
                            .setRetcode(4).setBattleId(req.getBattleId()).build();
                }
                context.setAutoBattle(enable, enable ? "ai_suggestion" : "");
                if (enable) {
                    context.scheduleNextAutoAction(BattleAutoService.BASE_AUTO_WAIT_MS);
                }
            }
            ok = true;
        }
        int lockSkill = override.getLockNextSkillId();
        if (lockSkill > 0) {
            synchronized (context.getLock()) {
                int caster = override.getLockCasterEntityId() > 0
                        ? override.getLockCasterEntityId() : context.getPlayerId();
                context.queueMicroIntervention(lockSkill, caster, List.of(),
                        override.getAutoRevertAfterAction());
            }
        }
        SuggestedAutoOverride applied = SuggestedAutoOverride.withMicro(focus, strategy, override.getUltReserve(),
                override.getReason(), lockSkill, override.getLockCasterEntityId(),
                override.getAutoRevertAfterAction(),
                override.getBattleStateSummary());
        return AssistSystemProto.ApplyAiSuggestionScRsp.newBuilder()
                .setRetcode(ok ? 0 : 3)
                .setBattleId(req.getBattleId())
                .setAutoEnabled(context.isAutoBattle())
                .setApplied(AssistNettyService.toAutoOverrideProto(applied))
                .build();
    }

    /**
     * 上报战斗结果并结束战局：服务端权威胜负，胜则删怪发奖并回落世界。
     */
    public BattleSystemProto.FightResultScRsp handleFightResult(BattleSystemProto.FightResultCsReq req, Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return BattleSystemProto.FightResultScRsp.newBuilder()
                    .setRetcode(4)
                    .setBattleId(req.getBattleId())
                    .build();
        }
        long battleId = req.getBattleId();
        BattleContext context = battleManager.get(battleId);
        if (context == null) {
            return BattleSystemProto.FightResultScRsp.newBuilder()
                    .setRetcode(1)
                    .setBattleId(battleId)
                    .build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) {
            return BattleSystemProto.FightResultScRsp.newBuilder()
                    .setRetcode(5)
                    .setBattleId(battleId)
                    .build();
        }

        int clientEndStatus = (int) req.getEndStatus();
        int endStatus = resolveEndStatus(context, clientEndStatus);
        if (clientEndStatus == 1 && endStatus != 1) {
            battleAuditService.recordReject(battleId, currentPlayerId, "client_win_without_clear");
        }
        battleAuditService.recordFightEnd(battleId, currentPlayerId, endStatus, clientEndStatus, "fight_result");
        String statsJson = BattleStatisticsUtil.toJson(req.getStatistics());

        try {
            battleRepository.updateBattleResult(battleId, endStatus, statsJson, new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("updateBattleResult failed, battleId={}, endStatus={}", battleId, endStatus, e);
            return BattleSystemProto.FightResultScRsp.newBuilder()
                    .setRetcode(2)
                    .setBattleId(battleId)
                    .build();
        }

        int playerExp = 0;
        List<RewardDistributor.GrantedItem> granted = List.of();
        if (endStatus == 1) {
            EncounterConfig encounter = encounterConfigRepository.current();
            int monsterId = context.getSceneMonsterId();
            List<EncounterConfig.DropEntry> drops = monsterId > 0
                    ? encounter.resolveDrops(monsterId)
                    : encounter.defaultDrops();
            playerExp = monsterId > 0
                    ? encounter.resolvePlayerExp(monsterId)
                    : Math.max(0, encounter.defaultPlayerExp());
            granted = rewardDistributor.grantBattleRewards(currentPlayerId, drops, playerExp, "battle:" + battleId);
            despawnSceneMonster(context, channel);
            StoryChapterService story = storyChapterProvider.getIfAvailable();
            if (story != null) {
                story.onBattleCleared(currentPlayerId, context.getBattleStageId());
            }
            cn.itcast.demo.mylunarcore.challenge.SweepService sweep = sweepProvider.getIfAvailable();
            if (sweep != null) {
                int turns = context.getTurn();
                if (req.hasStatistics() && req.getStatistics().getTurnCount() > 0) {
                    turns = (int) req.getStatistics().getTurnCount();
                }
                sweep.recordClear(currentPlayerId, context.getBattleStageId(), Math.max(1, turns));
            }
        }

        synchronized (context.getLock()) {
            context.setEnded(true);
        }
        battleManager.remove(battleId);
        transitionBackToScene(context);
        gameEventPublisher.publish(new BattleEndedEvent(
                battleId,
                context.getPlayerId(),
                endStatus,
                "RESULT",
                System.currentTimeMillis() / 1000L
        ));

        BattleSystemProto.FightResultScRsp.Builder rsp = BattleSystemProto.FightResultScRsp.newBuilder()
                .setRetcode(0)
                .setBattleId(battleId)
                .setPlayerExp(playerExp);
        for (RewardDistributor.GrantedItem item : granted) {
            rsp.addRewardItems(BattleSystemProto.RewardItem.newBuilder()
                    .setItemId(item.itemId())
                    .setCount(item.count())
                    .build());
        }
        return rsp.build();
    }

    /**
     * 主动退出战斗：回落世界坐标，不删怪、不发奖。
     */
    public BattleSystemProto.FightQuitScRsp handleFightQuit(BattleSystemProto.FightQuitCsReq req, Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return BattleSystemProto.FightQuitScRsp.newBuilder()
                    .setRetcode(3)
                    .setBattleId(req.getBattleId())
                    .build();
        }
        long battleId = req.getBattleId();
        BattleContext context = battleManager.get(battleId);
        if (context == null) {
            return BattleSystemProto.FightQuitScRsp.newBuilder()
                    .setRetcode(1)
                    .setBattleId(battleId)
                    .build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) {
            return BattleSystemProto.FightQuitScRsp.newBuilder()
                    .setRetcode(4)
                    .setBattleId(battleId)
                    .build();
        }

        try {
            battleRepository.updateBattleResult(battleId, 3, "{}", new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("updateBattleResult failed on quit, battleId={}", battleId, e);
            return BattleSystemProto.FightQuitScRsp.newBuilder()
                    .setRetcode(2)
                    .setBattleId(battleId)
                    .build();
        }

        synchronized (context.getLock()) {
            context.setEnded(true);
        }
        battleManager.remove(battleId);
        transitionBackToScene(context);
        gameEventPublisher.publish(new BattleEndedEvent(
                battleId,
                context.getPlayerId(),
                3,
                "QUIT",
                System.currentTimeMillis() / 1000L
        ));

        ReturnPoint point = resolveReturnPoint(context);
        BattleSystemProto.Vec3 pos = BattleSystemProto.Vec3.newBuilder()
                .setX(point.x())
                .setY(point.y())
                .setZ(point.z())
                .build();

        return BattleSystemProto.FightQuitScRsp.newBuilder()
                .setRetcode(0)
                .setBattleId(battleId)
                .setTeleportSceneId(point.teleportSceneId())
                .setTeleportPos(pos)
                .build();
    }

    /**
     * 查询战局快照。
     *
     * @param req 查询请求
     * @param channel 客户端连接
     * @return 战局信息响应；仅战斗拥有者可查询
     */
    public BattleSystemProto.GetBattleInfoScRsp handleGetBattleInfo(BattleSystemProto.GetBattleInfoCsReq req, Channel channel) { // 处理战局快照查询
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        BattleContext context = battleManager.get(req.getBattleId());
        if (context == null) {
            BattleSnapshotService snaps = snapshotProvider == null ? null : snapshotProvider.getIfAvailable();
            if (snaps != null) {
                context = snaps.hydrate(req.getBattleId()).orElse(null);
                if (context != null) {
                    battleManager.put(context);
                }
            }
        }
        long battleId = req.getBattleId();
        if (context == null) {
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder()
                    .setRetcode(1)
                    .setBattleId(battleId)
                    .build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) {
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder()
                    .setRetcode(3)
                    .setBattleId(battleId)
                    .build();
        }
        if (channel != null) {
            BattleSnapshotService snaps = snapshotProvider == null ? null : snapshotProvider.getIfAvailable();
            if (snaps != null) {
                snaps.pushReplayOnReconnect(context.getBattleId(), context, channel);
            }
        }

        BattleSystemProto.StageInfo stageInfo = BattleSystemProto.StageInfo.newBuilder() // 构建关卡摘要信息
                .setId(context.getBattleStageId()) // 写入关卡/实体 ID
                .setWaveCount(context.getWaveCount()) // 写入总波次数
                .build();

        BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
        return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .setStageInfo(stageInfo) // 写入关卡摘要
                .setCurrentState(snapshot) // 写入战局当前快照
                .setStartTime(context.getStartTimeSeconds()) // 写入开战时间戳
                .setSpeedMultiplier(context.getSpeedMultiplier())
                .setAutoEnabled(context.isAutoBattle())
                .build();
    }

    public BattleSystemProto.SetBattleAutoScRsp handleSetBattleAuto(BattleSystemProto.SetBattleAutoCsReq req,
                                                                   Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return BattleSystemProto.SetBattleAutoScRsp.newBuilder().setRetcode(1).setBattleId(req.getBattleId()).build();
        }
        BattleContext context = battleManager.get(req.getBattleId());
        if (context == null) {
            return BattleSystemProto.SetBattleAutoScRsp.newBuilder().setRetcode(2).setBattleId(req.getBattleId()).build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) {
            return BattleSystemProto.SetBattleAutoScRsp.newBuilder().setRetcode(3).setBattleId(req.getBattleId()).build();
        }
        if (context.isEnded()) {
            return BattleSystemProto.SetBattleAutoScRsp.newBuilder().setRetcode(4).setBattleId(req.getBattleId()).build();
        }
        BattleAutoService auto = battleAutoProvider == null ? null : battleAutoProvider.getIfAvailable();
        boolean ok = auto != null && auto.enableAuto(req.getBattleId(), currentPlayerId, req.getEnabled(), "manual",
                req.getAutoStrategyValue(), req.getTargetFocusValue());
        if (!ok && auto == null) {
            synchronized (context.getLock()) {
                if (req.getEnabled() && !context.setAutoStrategy(req.getAutoStrategyValue())) {
                    return BattleSystemProto.SetBattleAutoScRsp.newBuilder()
                            .setRetcode(5).setBattleId(req.getBattleId()).build();
                }
                if (req.getEnabled() && !context.setTargetFocus(req.getTargetFocusValue())) {
                    return BattleSystemProto.SetBattleAutoScRsp.newBuilder()
                            .setRetcode(5).setBattleId(req.getBattleId()).build();
                }
                context.setAutoBattle(req.getEnabled(), req.getEnabled() ? "manual" : "");
                if (req.getEnabled()) {
                    context.scheduleNextAutoAction(BattleAutoService.BASE_AUTO_WAIT_MS);
                }
            }
            ok = true;
        }
        BattleSystemProto.BattleAutoStrategy strategy = BattleSystemProto.BattleAutoStrategy.forNumber(context.getAutoStrategy());
        if (strategy == null) {
            strategy = BattleSystemProto.BattleAutoStrategy.PRIORITY_SKILL;
        }
        BattleSystemProto.BattleAutoTargetFocus focus =
                BattleSystemProto.BattleAutoTargetFocus.forNumber(context.getTargetFocus());
        if (focus == null) {
            focus = BattleSystemProto.BattleAutoTargetFocus.TARGET_FOCUS_DEFAULT;
        }
        return BattleSystemProto.SetBattleAutoScRsp.newBuilder()
                .setRetcode(ok ? 0 : 2)
                .setBattleId(req.getBattleId())
                .setAutoEnabled(context.isAutoBattle())
                .setAutoStrategy(strategy)
                .setTargetFocus(focus)
                .build();
    }

    public BattleSystemProto.BattleManualUltScRsp handleBattleManualUlt(BattleSystemProto.BattleManualUltCsReq req,
                                                                        Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return BattleSystemProto.BattleManualUltScRsp.newBuilder().setRetcode(1).setBattleId(req.getBattleId()).build();
        }
        BattleContext context = battleManager.get(req.getBattleId());
        if (context == null) {
            return BattleSystemProto.BattleManualUltScRsp.newBuilder().setRetcode(2).setBattleId(req.getBattleId()).build();
        }
        if (!isBattleParticipant(context, currentPlayerId)) {
            return BattleSystemProto.BattleManualUltScRsp.newBuilder().setRetcode(3).setBattleId(req.getBattleId()).build();
        }
        if (context.isEnded()) {
            return BattleSystemProto.BattleManualUltScRsp.newBuilder().setRetcode(5).setBattleId(req.getBattleId()).build();
        }
        if (!context.isAutoBattle()) {
            return BattleSystemProto.BattleManualUltScRsp.newBuilder().setRetcode(4).setBattleId(req.getBattleId()).build();
        }
        BattleAutoService auto = battleAutoProvider == null ? null : battleAutoProvider.getIfAvailable();
        BattleAutoService.AutoAction action = auto == null ? null
                : auto.executeManualUlt(req.getBattleId(), currentPlayerId, req.getSkillId(), req.getCasterId(),
                req.getTargetIdsList());
        return BattleSystemProto.BattleManualUltScRsp.newBuilder()
                .setRetcode(action == null ? 4 : 0)
                .setBattleId(req.getBattleId())
                .setSkillId(action == null ? req.getSkillId() : action.skillId())
                .setAutoResumed(context.isAutoBattle())
                .setEstimatedCastTimeMs(action == null ? 0 : action.estimatedCastMs())
                .setClientPreFxMs(action == null ? 0 : BattleAutoService.CLIENT_PRE_FX_MS)
                .build();
    }

    public BattleSystemProto.SetBattleSpeedScRsp handleSetBattleSpeed(BattleSystemProto.SetBattleSpeedCsReq req,
                                                                     Channel channel) {
        Integer currentPlayerId = getCurrentPlayerId(channel);
        if (currentPlayerId == null) {
            return BattleSystemProto.SetBattleSpeedScRsp.newBuilder().setRetcode(1).setBattleId(req.getBattleId()).build();
        }
        BattleAutoService auto = battleAutoProvider == null ? null : battleAutoProvider.getIfAvailable();
        boolean ok;
        if (auto != null) {
            ok = auto.setSpeed(req.getBattleId(), currentPlayerId, req.getSpeedMultiplier());
        } else {
            BattleContext context = battleManager.get(req.getBattleId());
            if (context == null || !isBattleParticipant(context, currentPlayerId)) {
                ok = false;
            } else {
                synchronized (context.getLock()) {
                    ok = context.setSpeedMultiplier(req.getSpeedMultiplier());
                }
            }
        }
        BattleContext after = battleManager.get(req.getBattleId());
        return BattleSystemProto.SetBattleSpeedScRsp.newBuilder()
                .setRetcode(ok ? 0 : (after == null ? 2 : 3))
                .setBattleId(req.getBattleId())
                .setSpeedMultiplier(after == null ? 1 : after.getSpeedMultiplier())
                .build();
    }

    /**
     * 将运行时上下文转换为协议快照。
     *
     * @param context 战斗上下文
     * @return 当前状态快照
     */
    private BattleSystemProto.CurrentState buildCurrentState(BattleContext context) { // 将内存战局转为 CurrentState 快照
        BattleSystemProto.CurrentState.Builder b = BattleSystemProto.CurrentState.newBuilder()
                .setTurn(context.getTurn()) // 写入当前回合数
                .setCurrentWave(context.getCurrentWave()); // 写入当前波次

        List<EntityState> entities = new ArrayList<>(context.getEntities().values()); // 拷贝战局实体集以便排序
        entities.sort(Comparator.comparingInt(EntityState::getId)); // 按 id 排序，保证客户端展示顺序稳定
        for (EntityState e : entities) { // 逐个实体写入快照
            BattleSystemProto.EntityState.Builder eb = BattleSystemProto.EntityState.newBuilder() // 构建单实体状态 Protobuf
                    .setId(e.getId()) // 写入关卡/实体 ID
                    .setHp(e.getHp()) // 写入当前 HP
                    .setDead(e.isDead()); // 写入死亡标记
            for (Integer buffId : e.getBuffStacks().keySet()) { // 遍历实体持有的 Buff
                if (e.getBuffStacks().getOrDefault(buffId, 0) > 0) { // 仅输出层数大于 0 的 Buff
                    eb.addBuffs(buffId); // 仅输出层数大于 0 的 Buff
                }
            }
            b.addEntities(eb.build()); // 将实体快照追加到 CurrentState
        }
        return b.build(); // 返回完整 CurrentState 快照
    }

    /**
     * 从连接属性读取当前玩家 id。
     *
     * @param channel 客户端连接
     * @return 玩家 id；未登录返回 null
     */
    private Integer getCurrentPlayerId(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        return playerId <= 0 ? null : playerId;
    }

    /**
     * 校验当前请求是否来自共战参与者（含发起者与 Party 扇入成员）。
     */
    private boolean isBattleParticipant(BattleContext context, int currentPlayerId) {
        return context.isParticipant(currentPlayerId);
    }

    /**
     * Party 开战扇出：同队且空闲成员加入同一战局，切 BATTLE 并推送 FightStart 包。
     */
    private void enrollPartyParticipants(long leaderUid, BattleContext context,
                                         BattleSystemProto.FightStartScRsp startRsp) {
        PartyService.Party party = partyService.getByUid(leaderUid);
        if (party == null || party.memberUids().size() <= 1) {
            return;
        }
        GamePacket packet = new GamePacket(CmdIds.FIGHT_START_SC_RSP, startRsp.toByteArray());
        for (Long memberUid : party.memberUids()) {
            if (memberUid == null || memberUid == leaderUid) {
                continue;
            }
            int memberPid = memberUid.intValue();
            if (battleManager.findActiveByPlayerId(memberPid) != null) {
                log.info("skip party enroll, member already in battle, uid={}", memberUid);
                continue;
            }
            context.addParticipant(memberPid);
            GameSession memberSession = sessionManager.getOrNull(memberUid);
            if (memberSession != null) {
                PlayerSessionStateMachine.tryTransition(memberSession, PlayerSessionState.BATTLE);
                Channel ch = memberSession.getChannel();
                if (ch != null && ch.isActive()) {
                    ch.writeAndFlush(packet);
                }
            }
            log.info("party co-battle enroll battleId={} leader={} member={}",
                    context.getBattleId(), leaderUid, memberUid);
        }
    }

    /**
     * 权威结算：服务端波次清剿优先；玩家全灭为负；拒绝未清场的客户端胜利。
     */
    private static int resolveEndStatus(BattleContext context, int clientEndStatus) {
        int auth = context.resolveAuthoritativeEndStatus();
        if (auth == 1) {
            return 1;
        }
        if (auth == 2) {
            return 2;
        }
        if (clientEndStatus == 1) {
            // 客户端宣称胜利但服务端未清场 → 按失败处理，避免刷奖
            return 2;
        }
        if (clientEndStatus == 2 || clientEndStatus == 3) {
            return clientEndStatus;
        }
        return 2;
    }

    private void despawnSceneMonster(BattleContext context, Channel channel) {
        if (context.getSceneEntityUid() <= 0 || context.getPlayerUid() <= 0) {
            return;
        }
        SceneContext scene = sceneManager.getByPlayerUid(context.getPlayerUid());
        int zoneId = scene != null ? scene.getZoneId() : 0;
        if (zoneId > 0 && zoneWorldService != null) {
            ZoneContext.ZoneMonster killed = zoneWorldService.despawnMonster(
                    zoneId, context.getSceneEntityUid(), System.currentTimeMillis());
            if (killed != null) {
                log.info("despawned zone monster after win, uid={}, entityId={}, monsterId={}",
                        context.getPlayerUid(), context.getSceneEntityUid(), killed.getMonsterId());
                return;
            }
        }
        if (scene == null) {
            return;
        }
        SceneContext.MonsterState removed = scene.removeMonster(context.getSceneEntityUid());
        if (removed != null) {
            sceneSyncBroadcaster.pushMonsterRemoved(channel, context.getSceneEntityUid());
            log.info("despawned scene monster after win, uid={}, entityId={}, monsterId={}",
                    context.getPlayerUid(), context.getSceneEntityUid(), removed.getMonsterId());
        }
    }

    private void transitionBackToScene(BattleContext context) {
        for (Integer pid : context.getParticipantPlayerIds()) {
            if (pid == null || pid <= 0) {
                continue;
            }
            GameSession session = sessionManager.getOrNull(pid.longValue());
            if (session == null) {
                continue;
            }
            if (session.getSessionState() == PlayerSessionState.BATTLE) {
                PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.SCENE);
            }
        }
    }

    private ReturnPoint resolveReturnPoint(BattleContext context) {
        if (context.getPlayerUid() > 0) {
            SceneContext scene = sceneManager.getByPlayerUid(context.getPlayerUid());
            if (scene != null) {
                SceneContext.ScenePos pos = scene.getPlayerPos();
                return new ReturnPoint(scene.getPlaneId(), pos.getX(), pos.getY(), pos.getZ());
            }
        }
        if (context.getReturnPlaneId() > 0) {
            return new ReturnPoint(context.getReturnPlaneId(),
                    context.getReturnPosX(), context.getReturnPosY(), context.getReturnPosZ());
        }
        return new ReturnPoint(0, 0f, 0f, 0f);
    }

    /** 兼容尚未重新生成的 Proto：反射读取 client_estimated_time_ms。 */
    private static long readClientEstimatedTimeMs(BattleSystemProto.FightActionCsReq req) {
        if (req == null) {
            return 0L;
        }
        try {
            java.lang.reflect.Method m = req.getClass().getMethod("getClientEstimatedTimeMs");
            Object v = m.invoke(req);
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (ReflectiveOperationException e) {
            return 0L;
        }
    }

    private record ReturnPoint(int teleportSceneId, float x, float y, float z) {
    }
}
