// 玩家会话业务：登录、心跳、登出、会话查询及 Tick 注册
package cn.itcast.demo.mylunarcore.player;

// 全局配置：最大在线人数、会话超时等
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

// 账号实体：用户名、密码、状态等
import cn.itcast.demo.mylunarcore.model.AccountEntity;

// 玩家聚合数据：内存 L2 对象
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 玩家主实体：等级、昵称、坐标等核心字段
import cn.itcast.demo.mylunarcore.model.PlayerEntity;

// 会话相关 Protobuf 请求/响应消息
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// 玩家数据持久化仓储
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;

// 在线玩家 Tick 对象，纳入游戏主循环
import cn.itcast.demo.mylunarcore.common.OnlinePlayer;

// Tick 注册表，登录注册、登出注销
import cn.itcast.demo.mylunarcore.common.PlayerTickRegistry;

// 货币 JSON 解析工具
import cn.itcast.demo.mylunarcore.player.PlayerCurrencyHelper;

// 异步全量加载服务
import cn.itcast.demo.mylunarcore.player.PlayerDataAsyncLoadService;

// 周期快照持久化服务
import cn.itcast.demo.mylunarcore.player.PlayerDataPeriodicPersistenceService;

// 同步原因枚举
import cn.itcast.demo.mylunarcore.player.SyncReason;

// 从 Netty Channel 读取绑定的 Ukcp 实例
import cn.itcast.demo.mylunarcore.net.ClientFeatureFlags;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GameLoginRateLimiter;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.InputCapability;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.net.SessionCryptoBinder;
import cn.itcast.demo.mylunarcore.net.UkcpChannelAccessor;
import cn.itcast.demo.mylunarcore.hall.ChatService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.social.FriendOnlineStatusService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// 场景管理器，OnlinePlayer 构造依赖
import cn.itcast.demo.mylunarcore.scene.SceneManager;

// 单用户游戏会话
import cn.itcast.demo.mylunarcore.player.GameSession;

// 全局会话管理器
import cn.itcast.demo.mylunarcore.player.GameSessionManager;

// Netty 客户端连接通道
import io.netty.channel.Channel;

// Channel 属性键：绑定 uid
import cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes;

// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J 日志
import org.slf4j.Logger;

// Spring 服务层组件
import cn.itcast.demo.mylunarcore.common.AccountPasswordService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;

// 高精度小数，读取数据库中的场景坐标
import java.math.BigDecimal;

// JDBC 时间戳，写入登录/登出审计字段
import java.sql.Timestamp;

import java.time.Instant;

// 货币 map，填充登录响应 PlayerInfo
import java.util.Map;

/**
 * 玩家会话与登录业务服务。
 * <p>
 * 实现账号密码 / SessionToken 双模式登录、心跳保活、主动登出、会话信息查询；
 * 登录成功时创建 {@link GameSession}、注册 {@link OnlinePlayer} 至 Tick 循环、
 * 下发 Protobuf 登录响应，并异步补全全量玩家数据。
 * </p>
 */
@Service // 注册为 Spring Bean，供 Netty Handler 调用
public class PlayerSessionService {

    /** 本类专用日志，分类 BUSINESS_SESSION */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SESSION, PlayerSessionService.class);

    /** retcode：0 表示成功，非 0 表示各类业务错误 */
    public static final int RET_OK = 0;

    /** 会话无效：Channel 未绑定 uid 或会话已被移除 */
    public static final int RET_SESSION_INVALID = 1;

    /** 密码错误 */
    public static final int RET_PASSWORD_ERROR = 2;

    /** 账号被封禁（status != 1） */
    public static final int RET_ACCOUNT_BANNED = 3;

    /** 账号不存在 */
    public static final int RET_ACCOUNT_NOT_FOUND = 4;

    /** 服务端内部异常 */
    public static final int RET_INTERNAL_ERROR = 5;

    /** 已达最大同时在线人数，新连接无法分配名额 */
    public static final int RET_SERVER_FULL = 6;

    /** 会话令牌无效或已过期（顶号、登出后旧 token 失效） */
    public static final int RET_TOKEN_INVALID = 7;

    /** 登录请求被限流 */
    public static final int RET_RATE_LIMITED = 8;

    /** 客户端线协议版本过旧，需强制升级 */
    public static final int RET_PROTOCOL_INCOMPATIBLE = 9;

    /** 客户端资源包版本过旧（AssetBundle） */
    public static final int RET_CLIENT_TOO_OLD = ClientVersionGateService.ERR_CLIENT_TOO_OLD;
    /** 资源 Manifest 落后（RESOURCE_OUTDATED），强制跳转更新页 */
    public static final int RET_RESOURCE_OUTDATED = ClientVersionGateService.ERR_RESOURCE_OUTDATED;
    /** 超过强制升级截止时间仍未升级 */
    public static final int RET_FORCE_UPGRADE = ClientVersionGateService.ERR_FORCE_UPGRADE;

    /** 账号/玩家数据读写仓储 */
    private final PlayerDataRepository repository;

    /** 全局会话生命周期管理 */
    private final GameSessionManager sessionManager;

    /** 场景管理，OnlinePlayer 构造需要 */
    private final SceneManager sceneManager;

    /** 在线玩家 Tick 注册表 */
    private final PlayerTickRegistry playerTickRegistry;

    /** 异步补全全量玩家数据 */
    private final PlayerDataAsyncLoadService playerDataAsyncLoadService;

    /** 周期将核心快照写回 DB */
    private final PlayerDataPeriodicPersistenceService periodicPersistenceService;

    /** 读取全局开关（如周期持久化间隔） */
    private final LunarCoreProperties lunarCoreProperties;

    /** 登录多步写库事务封装 */
    private final PlayerLoginApplicationService loginApplicationService;

    /** 断连/登出统一清理 */
    private final ConnectionLifecycleService connectionLifecycleService;

    /** 登录限流 */
    private final GameLoginRateLimiter loginRateLimiter;

    /** 游戏账号密码校验（与 Admin 共用哈希策略） */
    private final AccountPasswordService accountPasswordService;

    private final ChatService chatService;

    private final ProtocolCompatService protocolCompatService;

    private final SessionCryptoBinder sessionCryptoBinder;

    private final ObjectProvider<StaminaService> staminaProvider;

    private final ObjectProvider<BusinessMetrics> businessMetricsProvider;

    private final ObjectProvider<ClientVersionGateService> clientVersionGateProvider;

    private final ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceModeProvider;

    private final ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineAchievementProvider;

    private final ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider;

    private final ObjectProvider<HomeNettyService> homeNettyProvider;

    private final ObjectProvider<cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService> visibilityProvider;

    private final ObjectProvider<cn.itcast.demo.mylunarcore.qol.QolLoginHookService> qolLoginHookProvider;

    /**
     * Spring 构造注入全部依赖。
     */
    public PlayerSessionService(PlayerDataRepository repository,
                                GameSessionManager sessionManager,
                                SceneManager sceneManager,
                                PlayerTickRegistry playerTickRegistry,
                                PlayerDataAsyncLoadService playerDataAsyncLoadService,
                                PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                LunarCoreProperties lunarCoreProperties,
                                PlayerLoginApplicationService loginApplicationService,
                                ConnectionLifecycleService connectionLifecycleService,
                                GameLoginRateLimiter loginRateLimiter,
                                AccountPasswordService accountPasswordService,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ProtocolCompatService protocolCompatService,
                                SessionCryptoBinder sessionCryptoBinder,
                                ObjectProvider<StaminaService> staminaProvider,
                                ObjectProvider<BusinessMetrics> businessMetricsProvider,
                                ObjectProvider<ClientVersionGateService> clientVersionGateProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceModeProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineAchievementProvider) {
        this(repository, sessionManager, sceneManager, playerTickRegistry, playerDataAsyncLoadService,
                periodicPersistenceService, lunarCoreProperties, loginApplicationService, connectionLifecycleService,
                loginRateLimiter, accountPasswordService, chatServiceProvider, protocolCompatService,
                sessionCryptoBinder, staminaProvider, businessMetricsProvider, clientVersionGateProvider,
                maintenanceModeProvider, offlineAchievementProvider, null, null, null);
    }

    public PlayerSessionService(PlayerDataRepository repository,
                                GameSessionManager sessionManager,
                                SceneManager sceneManager,
                                PlayerTickRegistry playerTickRegistry,
                                PlayerDataAsyncLoadService playerDataAsyncLoadService,
                                PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                LunarCoreProperties lunarCoreProperties,
                                PlayerLoginApplicationService loginApplicationService,
                                ConnectionLifecycleService connectionLifecycleService,
                                GameLoginRateLimiter loginRateLimiter,
                                AccountPasswordService accountPasswordService,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ProtocolCompatService protocolCompatService,
                                SessionCryptoBinder sessionCryptoBinder,
                                ObjectProvider<StaminaService> staminaProvider,
                                ObjectProvider<BusinessMetrics> businessMetricsProvider,
                                ObjectProvider<ClientVersionGateService> clientVersionGateProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceModeProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineAchievementProvider,
                                ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider,
                                ObjectProvider<HomeNettyService> homeNettyProvider) {
        this(repository, sessionManager, sceneManager, playerTickRegistry, playerDataAsyncLoadService,
                periodicPersistenceService, lunarCoreProperties, loginApplicationService, connectionLifecycleService,
                loginRateLimiter, accountPasswordService, chatServiceProvider, protocolCompatService,
                sessionCryptoBinder, staminaProvider, businessMetricsProvider, clientVersionGateProvider,
                maintenanceModeProvider, offlineAchievementProvider, friendOnlineStatusProvider, homeNettyProvider,
                null);
    }

    public PlayerSessionService(PlayerDataRepository repository,
                                GameSessionManager sessionManager,
                                SceneManager sceneManager,
                                PlayerTickRegistry playerTickRegistry,
                                PlayerDataAsyncLoadService playerDataAsyncLoadService,
                                PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                LunarCoreProperties lunarCoreProperties,
                                PlayerLoginApplicationService loginApplicationService,
                                ConnectionLifecycleService connectionLifecycleService,
                                GameLoginRateLimiter loginRateLimiter,
                                AccountPasswordService accountPasswordService,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ProtocolCompatService protocolCompatService,
                                SessionCryptoBinder sessionCryptoBinder,
                                ObjectProvider<StaminaService> staminaProvider,
                                ObjectProvider<BusinessMetrics> businessMetricsProvider,
                                ObjectProvider<ClientVersionGateService> clientVersionGateProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceModeProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineAchievementProvider,
                                ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider,
                                ObjectProvider<HomeNettyService> homeNettyProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService> visibilityProvider) {
        this(repository, sessionManager, sceneManager, playerTickRegistry, playerDataAsyncLoadService,
                periodicPersistenceService, lunarCoreProperties, loginApplicationService, connectionLifecycleService,
                loginRateLimiter, accountPasswordService, chatServiceProvider, protocolCompatService,
                sessionCryptoBinder, staminaProvider, businessMetricsProvider, clientVersionGateProvider,
                maintenanceModeProvider, offlineAchievementProvider, friendOnlineStatusProvider, homeNettyProvider,
                visibilityProvider, null);
    }

    @Autowired
    public PlayerSessionService(PlayerDataRepository repository,
                                GameSessionManager sessionManager,
                                SceneManager sceneManager,
                                PlayerTickRegistry playerTickRegistry,
                                PlayerDataAsyncLoadService playerDataAsyncLoadService,
                                PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                LunarCoreProperties lunarCoreProperties,
                                PlayerLoginApplicationService loginApplicationService,
                                ConnectionLifecycleService connectionLifecycleService,
                                GameLoginRateLimiter loginRateLimiter,
                                AccountPasswordService accountPasswordService,
                                ObjectProvider<ChatService> chatServiceProvider,
                                ProtocolCompatService protocolCompatService,
                                SessionCryptoBinder sessionCryptoBinder,
                                ObjectProvider<StaminaService> staminaProvider,
                                ObjectProvider<BusinessMetrics> businessMetricsProvider,
                                ObjectProvider<ClientVersionGateService> clientVersionGateProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.ops.MaintenanceModeService> maintenanceModeProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator> offlineAchievementProvider,
                                ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider,
                                ObjectProvider<HomeNettyService> homeNettyProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService> visibilityProvider,
                                ObjectProvider<cn.itcast.demo.mylunarcore.qol.QolLoginHookService> qolLoginHookProvider) {
        this.repository = repository;
        this.sessionManager = sessionManager;
        this.sceneManager = sceneManager;
        this.playerTickRegistry = playerTickRegistry;
        this.playerDataAsyncLoadService = playerDataAsyncLoadService;
        this.periodicPersistenceService = periodicPersistenceService;
        this.lunarCoreProperties = lunarCoreProperties;
        this.loginApplicationService = loginApplicationService;
        this.connectionLifecycleService = connectionLifecycleService;
        this.loginRateLimiter = loginRateLimiter;
        this.accountPasswordService = accountPasswordService;
        this.chatService = chatServiceProvider.getIfAvailable();
        this.protocolCompatService = protocolCompatService;
        this.sessionCryptoBinder = sessionCryptoBinder;
        this.staminaProvider = staminaProvider;
        this.businessMetricsProvider = businessMetricsProvider;
        this.clientVersionGateProvider = clientVersionGateProvider;
        this.maintenanceModeProvider = maintenanceModeProvider;
        this.offlineAchievementProvider = offlineAchievementProvider;
        this.friendOnlineStatusProvider = friendOnlineStatusProvider;
        this.homeNettyProvider = homeNettyProvider;
        this.visibilityProvider = visibilityProvider;
        this.qolLoginHookProvider = qolLoginHookProvider;
    }

    /**
     * 登录入口：若请求携带非空 SessionToken 则走免密分支，否则走账号密码分支。
     *
     * @param req      客户端登录请求 Protobuf
     * @param channel  当前 Netty 连接
     * @param clientIp 客户端 IP，写入账号登录审计
     * @return 登录响应；未捕获异常时返回 {@link #RET_INTERNAL_ERROR}
     */
    public PlayerSessionProto.PlayerLoginScRsp handleLogin(PlayerSessionProto.PlayerLoginCsReq req,
                                                           Channel channel,
                                                           String clientIp) {
        try {
            var maintenance = maintenanceModeProvider.getIfAvailable();
            if (maintenance != null && maintenance.rejectLogin()) {
                return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                        .setRetcode(RET_RATE_LIMITED)
                        .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                        .build();
            }
            int clientWire = req.getWireVersion();
            // 未声明时按 1 处理，强制升级到 PROTOCOL_WIRE_VERSION
            if (clientWire == 0) {
                clientWire = 1;
            }
            if (!protocolCompatService.isCompatible(clientWire)) {
                return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                        .setRetcode(RET_PROTOCOL_INCOMPATIBLE)
                        .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                        .build();
            }
            ClientVersionGateService gate = clientVersionGateProvider.getIfAvailable();
            if (gate != null) {
                ClientVersionGateService.GateResult resGate = gate.check(req.getClientResVersion());
                if (!resGate.allowed()) {
                    int code = resGate.retcode() == ClientVersionGateService.ERR_RESOURCE_OUTDATED
                            ? RET_RESOURCE_OUTDATED
                            : (resGate.retcode() == ClientVersionGateService.ERR_FORCE_UPGRADE
                            ? RET_FORCE_UPGRADE : RET_CLIENT_TOO_OLD);
                    var builder = PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                            .setRetcode(code)
                            .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                            .setRequiredClientResVersion(resGate.requiredResVersion())
                            .setClientUpdateUrl(resGate.updateUrl());
                    if (resGate.forceUpgradeDeadline() > 0) {
                        builder.setForceUpgradeDeadline(resGate.forceUpgradeDeadline());
                    }
                    return builder.build();
                }
            }
            if (!loginRateLimiter.tryAcquire(clientIp, req.getUsername())) {
                return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                        .setRetcode(RET_RATE_LIMITED)
                        .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                        .build();
            }
            long clientFeatures = ClientFeatureFlags.normalizeClientMask(req.getSupportedFeatures());
            int inputMethods = InputCapability.normalize(req.getInputMethods());
            clientFeatures |= InputCapability.toFeatureBits(inputMethods);
            if (inputMethods != 0) {
                clientFeatures |= ClientFeatureFlags.LAYOUT_HINT;
            }
            clientFeatures &= ClientFeatureFlags.SERVER_ALL;
            String deviceId = req.getDeviceId() == null ? "" : req.getDeviceId();
            String tokenReq = req.getSessionToken(); // 客户端上报的会话令牌（可为空串）
            if (tokenReq != null && !tokenReq.isBlank()) { // 有 token 则优先免密登录
                return handleLoginByToken(tokenReq.trim(), channel, clientIp, clientFeatures, inputMethods, deviceId);
            }
            return handleLoginByPassword(req, channel, clientIp, clientFeatures, inputMethods, deviceId); // 无 token 则账号密码登录
        } catch (Exception e) {
            log.error("handleLogin error, username={}", req.getUsername(), e);
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_INTERNAL_ERROR)
                    .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                    .build();
        }
    }

    /**
     * 基于会话令牌免密登录：token 解析 uid → 校验账号状态 → 建立会话。
     *
     * @param sessionToken 客户端持有的会话令牌
     * @param channel      当前连接
     * @param clientIp     客户端 IP
     * @return 登录响应
     */
    private PlayerSessionProto.PlayerLoginScRsp handleLoginByToken(String sessionToken,
                                                                   Channel channel,
                                                                   String clientIp,
                                                                   long clientFeatures,
                                                                   int inputMethods,
                                                                   String deviceId) {
        Long uid = sessionManager.resolveToken(sessionToken); // 从 token 表解析 uid
        if (uid == null) { // token 不存在或已失效
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_TOKEN_INVALID)
                    .build();
        }
        PlayerEntity player = repository.loadPlayerByUid(uid); // 按 uid 加载玩家主实体
        if (player == null) { // 数据不一致：token 有效但玩家行不存在
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_INTERNAL_ERROR)
                    .build();
        }
        AccountEntity account = repository.findAccountByUnsignedAccountId(player.getAccountId()); // 加载关联账号
        if (account == null) {
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_ACCOUNT_NOT_FOUND)
                    .build();
        }
        if (account.getStatus() != 1) { // status=1 表示正常，其他值表示封禁等
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_ACCOUNT_BANNED)
                    .build();
        }
        if (!sessionManager.canAcceptNewOnlineSlot(player.getUid())) { // 新登录且服务器已满
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_SERVER_FULL)
                    .build();
        }
        return completeSuccessfulLogin(account, player, channel, clientIp, clientFeatures, inputMethods, deviceId);
    }

    /**
     * 基于账号密码登录：校验账号 → 加载或创建玩家 → 建立会话。
     *
     * @param req      含 username/password 的登录请求
     * @param channel  当前连接
     * @param clientIp 客户端 IP
     * @return 登录响应
     */
    private PlayerSessionProto.PlayerLoginScRsp handleLoginByPassword(PlayerSessionProto.PlayerLoginCsReq req,
                                                                      Channel channel,
                                                                      String clientIp,
                                                                      long clientFeatures,
                                                                      int inputMethods,
                                                                      String deviceId) {
        AccountEntity account = repository.findAccountByUsername(req.getUsername()); // 用户名唯一索引查询
        if (account == null) {
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_ACCOUNT_NOT_FOUND)
                    .build();
        }
        if (account.getStatus() != 1) { // 账号非正常状态
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_ACCOUNT_BANNED)
                    .build();
        }
        if (!accountPasswordService.matches(req.getPassword(), account.getPassword())) {
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_PASSWORD_ERROR)
                    .build();
        }
        if (accountPasswordService.needsRehash(account.getPassword())) {
            String encoded = accountPasswordService.encode(req.getPassword());
            repository.updateAccountPassword(account.getId(), encoded);
            account.setPassword(encoded);
        }
        PlayerEntity player = repository.loadPlayerByUsername(req.getUsername()); // 读取该账号下玩家角色
        if (player == null) { // 首次登录：自动创建默认角色
            player = repository.createDefaultPlayerForAccount(account);
        }
        if (!sessionManager.canAcceptNewOnlineSlot(player.getUid())) { // 在线人数上限检查
            return PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(RET_SERVER_FULL)
                    .build();
        }
        return completeSuccessfulLogin(account, player, channel, clientIp, clientFeatures, inputMethods, deviceId);
    }

    /**
     * 登录成功后的统一收尾：更新审计、创建会话、注册 Tick、颁发 token、构建响应。
     *
     * @param account  已校验通过的账号
     * @param player   已加载或新创建的玩家实体
     * @param channel  当前连接
     * @param clientIp 客户端 IP
     * @return 成功登录响应（retcode=0）
     */
    private PlayerSessionProto.PlayerLoginScRsp completeSuccessfulLogin(AccountEntity account,
                                                                        PlayerEntity player,
                                                                        Channel channel,
                                                                        String clientIp,
                                                                        long clientFeatures,
                                                                        int inputMethods,
                                                                        String deviceId) {
        Timestamp now = new Timestamp(System.currentTimeMillis()); // 当前时间，写入 DB 审计字段
        loginApplicationService.recordSuccessfulLogin(account, player.getUid(), now, clientIp); // 事务内更新账号+玩家登录时间
        BusinessMetrics metrics = businessMetricsProvider.getIfAvailable();
        if (metrics != null) {
            metrics.recordLoginSuccess();
            metrics.recordDailyLoginUv(player.getUid());
        }
        PlayerData core = repository.loadCoreData(player.getUid()); // 同步加载核心切片并挂载到 Session
        GameSession session = sessionManager.createOrReplace(player.getUid(), channel,
                UkcpChannelAccessor.ukcp(channel)); // 创建或顶号替换会话
        session.setPlayerData(core);
        long enabledFeatures = clientFeatures & ClientFeatureFlags.SERVER_ALL;
        session.setEnabledFeatures(enabledFeatures);
        String layoutId = InputCapability.recommendedLayoutId(inputMethods, deviceId);
        session.setInputMethods(inputMethods);
        session.setDeviceId(deviceId == null ? "" : deviceId);
        session.setRecommendedLayoutId(layoutId);
        var visibility = visibilityProvider == null ? null : visibilityProvider.getIfAvailable();
        if (visibility != null) {
            session.setQaTester(visibility.isQaTester(player.getUid()));
        }
        if (core.getPlayer() != null) {
            session.setNickname(core.getPlayer().getNickname());
            session.setLevel(core.getPlayer().getLevel());
            session.bindDataVersion(core.getPlayer().getDataVersion());
            session.clearDirty();
        }
        sessionManager.updateActive(player.getUid()); // 初始化最后活跃时间
        session.setSessionState(PlayerSessionState.HALL); // 登录后落在主界面，需「开始游戏」才进场景
        channel.attr(PlayerChannelAttributes.PLAYER_UID).set(player.getUid()); // Channel 绑定 uid，供后续 Handler 使用
        // 注册 OnlinePlayer 至 Tick 循环：负责定时同步、周期持久化、体力恢复等
        playerTickRegistry.register(new OnlinePlayer(player.getUid(), sessionManager, sceneManager,
                playerDataAsyncLoadService, periodicPersistenceService, lunarCoreProperties,
                staminaProvider.getIfAvailable()));
        // 「先可玩、后补全」：登录响应仅含核心字段；全量数据异步加载完成后以 LOGIN 原因推送
        playerDataAsyncLoadService.reloadFullAsync(player.getUid(), SyncReason.LOGIN);
        if (chatService != null) {
            try {
                chatService.flushOffline((int) player.getUid());
            } catch (Exception ignored) {
                // 离线消息推送失败不阻断登录
            }
        }
        var offlineAchievement = offlineAchievementProvider.getIfAvailable();
        if (offlineAchievement != null) {
            try {
                Instant lastLogout = player.getLastLogout() == null
                        ? Instant.EPOCH
                        : player.getLastLogout().toInstant();
                offlineAchievement.compensateOnLogin((int) player.getUid(), lastLogout);
            } catch (Exception ignored) {
                // 成就补偿失败不阻断登录
            }
        }
        FriendOnlineStatusService friendOnline = friendOnlineStatusProvider == null
                ? null : friendOnlineStatusProvider.getIfAvailable();
        if (friendOnline != null) {
            try {
                friendOnline.publishOnline((int) player.getUid());
            } catch (Exception ignored) {
                // 在线状态推送失败不阻断登录
            }
        }
        HomeNettyService homeNetty = homeNettyProvider == null ? null : homeNettyProvider.getIfAvailable();
        if (homeNetty != null) {
            try {
                var visitorNotify = homeNetty.buildVisitorLogNotify((int) player.getUid());
                if (visitorNotify.getUnreadCount() > 0 || visitorNotify.getEntriesCount() > 0) {
                    session.send(new GamePacket(CmdIds.HOME_VISITOR_LOG_SC_NOTIFY, visitorNotify.toByteArray()));
                }
            } catch (Exception ignored) {
                // 家园未读推送失败不阻断登录
            }
        }
        var qolHook = qolLoginHookProvider == null ? null : qolLoginHookProvider.getIfAvailable();
        if (qolHook != null) {
            try {
                long lastLogoutMs = player.getLastLogout() == null
                        ? 0L
                        : player.getLastLogout().getTime();
                qolHook.onLoginSuccess((int) player.getUid(), lastLogoutMs);
            } catch (Exception ignored) {
                // QoL 推送失败不阻断登录
            }
        }
        String sessionToken = sessionManager.bindSessionToken(player.getUid()); // 颁发新 token，旧 token 作废
        String sessionCryptoKey = sessionCryptoBinder.bindNewSessionKey(channel);
        Map<Integer, Integer> currency = PlayerCurrencyHelper.parseCurrency(player.getCurrencyJson()); // 解析货币 JSON
        // 构建登录响应中的 PlayerInfo 子消息
        PlayerSessionProto.PlayerInfo playerInfo = PlayerSessionProto.PlayerInfo.newBuilder()
                .setUid((int) player.getUid()) // protobuf 字段为 int32，uid 需在业务上保证不溢出
                .setNickname(player.getNickname() == null ? "" : player.getNickname())
                .setLevel(Math.max(0, player.getLevel()))
                .setExp((int) player.getExp()) // long 转 int，与协议字段类型一致
                .setWorldLevel(Math.max(0, player.getWorldLevel()))
                .setStamina(Math.max(0, player.getStamina()))
                .putAllCurrency(currency) // 货币 map
                .build();
        BigDecimal posX = player.getPosX(); // 数据库 DECIMAL 坐标
        BigDecimal posY = player.getPosY();
        BigDecimal posZ = player.getPosZ();
        float x = posX == null ? 0.0f : posX.floatValue(); // null 安全转 protobuf float
        float y = posY == null ? 0.0f : posY.floatValue();
        float z = posZ == null ? 0.0f : posZ.floatValue();
        // 构建场景信息子消息
        PlayerSessionProto.SceneInfo sceneInfo = PlayerSessionProto.SceneInfo.newBuilder()
                .setSceneId(Math.max(0, player.getSceneId())) // 当前所在场景配置 id
                .setPosX(x)
                .setPosY(y)
                .setPosZ(z)
                .build();
        long serverTimeSeconds = System.currentTimeMillis() / 1000L; // 秒级服务器时间
        var loginBuilder = PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                .setRetcode(RET_OK) // 成功
                .setPlayerInfo(playerInfo) // 玩家基础信息
                .setServerTime(serverTimeSeconds) // 服务器时间
                .setSceneInfo(sceneInfo) // 出生/上次离线位置
                .setSessionToken(sessionToken) // 新颁发的会话令牌，客户端下次可免密登录
                .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                .setSessionCryptoKey(sessionCryptoKey == null ? "" : sessionCryptoKey)
                .setEnabledFeatures(enabledFeatures)
                .setInputMethods(inputMethods)
                .setRecommendedLayoutId(layoutId == null ? "" : layoutId);
        ClientVersionGateService gate = clientVersionGateProvider.getIfAvailable();
        if (gate != null && gate.current() != null && gate.current().forceUpgradeDeadline() > 0) {
            loginBuilder.setForceUpgradeDeadline(gate.current().forceUpgradeDeadline());
        }
        return loginBuilder.build();
    }

    /**
     * 处理心跳请求：刷新会话活跃时间，防止被超时清理任务踢下线。
     *
     * @param req     心跳请求（预留扩展字段）
     * @param channel 当前连接
     * @return 心跳响应；未登录返回 {@link #RET_SESSION_INVALID}
     */
    public PlayerSessionProto.PlayerHeartBeatScRsp handleHeartbeat(PlayerSessionProto.PlayerHeartBeatCsReq req,
                                                                   Channel channel) {
        Long uid = channel.attr(PlayerChannelAttributes.PLAYER_UID).get(); // 从 Channel 属性读取 uid
        if (uid == null) { // 尚未登录或已登出
            return PlayerSessionProto.PlayerHeartBeatScRsp.newBuilder()
                    .setRetcode(RET_SESSION_INVALID)
                    .setServerTime(System.currentTimeMillis() / 1000L)
                    .build();
        }
        sessionManager.updateActive(uid); // 刷新 GameSession.lastActiveMillis
        GameSession session = sessionManager.getOrNull(uid);
        if (session != null) {
            long now = System.currentTimeMillis();
            long client = req.getClientTime();
            if (client > 1_000_000_000_000L) {
                session.setRttMs((int) Math.max(0L, Math.min(5_000L, now - client)));
            }
        }
        return PlayerSessionProto.PlayerHeartBeatScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setServerTime(System.currentTimeMillis() / 1000L) // 回传服务器时间供客户端对时
                .build();
    }

    /**
     * 处理登出请求：持久化登出时间、移除会话、解除 Channel 绑定。
     *
     * @param req     登出请求
     * @param channel 当前连接
     * @return 登出响应；DB 写失败不阻塞会话清理
     */
    public PlayerSessionProto.PlayerLogoutScRsp handleLogout(PlayerSessionProto.PlayerLogoutCsReq req,
                                                               Channel channel) {
        Long uid = channel.attr(PlayerChannelAttributes.PLAYER_UID).get();
        if (uid == null) { // 未登录状态调用登出
            return PlayerSessionProto.PlayerLogoutScRsp.newBuilder()
                    .setRetcode(RET_SESSION_INVALID)
                    .build();
        }
        connectionLifecycleService.cleanupOnLogout(uid, channel);
        return PlayerSessionProto.PlayerLogoutScRsp.newBuilder()
                .setRetcode(RET_OK)
                .build();
    }

    /**
     * 查询当前连接绑定的会话基础信息（uid、昵称、等级快照）。
     *
     * @param channel 当前连接
     * @return 会话信息响应；未绑定 uid 或 session 已移除时返回无效会话
     */
    public PlayerSessionProto.GetSessionInfoScRsp handleGetSessionInfo(Channel channel) {
        Long uid = channel.attr(PlayerChannelAttributes.PLAYER_UID).get();
        if (uid == null) {
            return PlayerSessionProto.GetSessionInfoScRsp.newBuilder()
                    .setRetcode(RET_SESSION_INVALID)
                    .build();
        }
        GameSession session = sessionManager.getOrNull(uid); // 从 L1 缓存取会话
        if (session == null) { // Channel 仍绑定 uid 但会话已被清理（竞态边缘情况）
            return PlayerSessionProto.GetSessionInfoScRsp.newBuilder()
                    .setRetcode(RET_SESSION_INVALID)
                    .build();
        }
        return PlayerSessionProto.GetSessionInfoScRsp.newBuilder()
                .setRetcode(RET_OK)
                .setUid((int) session.getUid())
                .setNickname(session.getNickname() == null ? "" : session.getNickname())
                .setLevel(Math.max(0, session.getLevel()))
                .setSessionState(GameFlowNettyService.toProtoState(session.getSessionState())) // 主界面=0，便于客户端判断流程
                .build();
    }
}
