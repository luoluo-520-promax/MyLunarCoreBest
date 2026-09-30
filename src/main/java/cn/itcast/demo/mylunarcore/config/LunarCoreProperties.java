// 全局游戏配置属性类所在包（对应 application.yml 中 lunarcore.*）
package cn.itcast.demo.mylunarcore.config;

// Lombok：自动生成 getter/setter/equals 等
import lombok.Data;
// 绑定配置前缀 lunarcore 到本类字段
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局游戏配置（lunarcore.*），由 EnableConfigurationProperties 注册为容器单例。
 */
@Getter
@ConfigurationProperties(prefix = "lunarcore") // yml 中 lunarcore.netty-port 等映射到本类
public class LunarCoreProperties {

    // 游戏监听主端口（TCP/KCP 等可共用或引用）
    @Setter
    private int nettyPort = 9000;

    /**
     * Netty 业务线程与 I/O 相关参数。
     */
    // 嵌套对象：对应 lunarcore.netty.* 配置段
    private final NettyProperties netty = new NettyProperties();

    /**
     * TCP (length-prefixed was removed; now reference Lunar frame) game listener.
     */
    // 是否启用 TCP 游戏协议监听
    @Setter
    private boolean gameTcpEnabled = true;

    /**
     * UDP KCP game listener on {@link #nettyPort}.
     */
    // 是否在同一端口启用 KCP（UDP）游戏监听
    @Setter
    private boolean gameKcpEnabled = true;

    // 会话超时、最大在线人数等
    private final SessionProperties session = new SessionProperties();

    // KCP 协议参数（间隔、MTU 等）
    private final KcpProperties kcp = new KcpProperties();

    // 游戏主循环定时器配置
    private final GameLoopProperties gameLoop = new GameLoopProperties();

    /**
     * 玩家数据统一同步（登录 / 变更 / 定时）。
     */
    private final SyncProperties sync = new SyncProperties();

    /**
     * 游戏服入站限流（登录、单连接包频率）。
     */
    private final RateLimitProperties rateLimit = new RateLimitProperties();

    /**
     * 热修复与客户端资源 URL 等（JSON），见 {@link cn.itcast.demo.mylunarcore.common.HotfixDataService}。
     */
    private final HotfixProperties hotfix = new HotfixProperties();

    /**
     * 是否在进程内启动控制台线程监听 {@code /reload}（配置与资源热更新，不含代码热更新）。
     */
    @Setter
    private boolean reloadConsoleEnabled = false;

    /**
     * Classpath or file URL, e.g. {@code classpath:data/ActivityScheduling.json}.
     */
    // 活动排期 JSON 资源路径
    @Setter
    private String activityScheduleResource = "classpath:data/ActivityScheduling.json";

    /**
     * 统一活动配置数组，如 {@code file:data/ActivityConfigs.json}。
     */
    @Setter
    private String activityConfigsResource = "file:data/ActivityConfigs.json";

    /**
     * 单活动详情目录（相对 {@link #dataDir}），存放 activity.{id}.json。
     */
    @Setter
    private String activityDetailDir = "activities";

    /**
     * 运营导入配置根目录（相对进程工作目录），如 data/ActivityScheduling.json。
     */
    @Setter
    private String dataDir = "data";

    /**
     * 场景遭遇映射配置（相对 dataDir），monsterId → battleStageId / 掉落。
     */
    @Setter
    private String encounterConfigsResource = "EncounterConfigs.json";

    /**
     * 传输层安全：TCP 上启用 TLS（握手阶段非对称协商会话密钥，记录层对称加密，语义与 HTTPS 一致）。
     */
    private final TlsProperties tls = new TlsProperties();

    /**
     * 中心服路由：local=进程内登记；remote=HTTP 查询远端中心服。
     */
    private final CenterProperties center = new CenterProperties();

    /**
     * Redis 基础设施：迁移票据、在线表、排行榜等；默认关闭，单机可用内存回退。
     */
    private final RedisProperties redis = new RedisProperties();

    /**
     * Zone 准入：同 Plane/Floor 人数上限。
     */
    private final ZoneProperties zone = new ZoneProperties();

    /**
     * 无缝大世界实验：Cell 分片、边界移交、实时战斗权威（默认关闭，崩铁式不启用）。
     */
    private final WorldProperties world = new WorldProperties();

    /**
     * 反作弊最小集：移动速度校验等。
     */
    private final AntiCheatProperties antiCheat = new AntiCheatProperties();

    /**
     * 抽卡消耗：按钱包货币扣费（与 Banner 展示 cost_item_id 对齐）。
     */
    private final GachaEconomyProperties gachaEconomy = new GachaEconomyProperties();

    /**
     * 匹配队列：超时、分段、补位。
     */
    private final MatchmakingProperties matchmaking = new MatchmakingProperties();

    /**
     * KCP 应用层加密（DTLS 替代方案）。
     */
    private final KcpCryptoProperties kcpCrypto = new KcpCryptoProperties();

    /**
     * 战斗断线快照（Redis/内存）。
     */
    private final BattleSnapshotProperties battleSnapshot = new BattleSnapshotProperties();

    /**
     * 钱包本地预扣 + WAL 异步落盘。
     */
    private final WalletWalProperties walletWal = new WalletWalProperties();

    /**
     * 配置热更灰度。
     */
    private final ConfigGrayProperties configGray = new ConfigGrayProperties();

    /**
     * 协议层命令号动态盐 HMAC。
     */
    private final ProtocolHmacProperties protocolHmac = new ProtocolHmacProperties();

    /**
     * 产品埋点。
     */
    private final AnalyticsProperties analytics = new AnalyticsProperties();

    /**
     * 历史数据归档保留策略。
     */
    private final DataRetentionProperties dataRetention = new DataRetentionProperties();

    /**
     * AI 辅助：规则教练（方案 A）与 LLM+RAG（方案 B）开关、配额与模型接入。
     */
    private final AiAssistProperties aiAssist = new AiAssistProperties();

    /**
     * 内部 HTTP（{@code /internal/**}）共享密钥；空值时过滤器拒绝全部内部请求。
     */
    @Setter
    private String internalApiToken = "dev-internal-token";

    /**
     * 密钥轮换宽限期：旧 INTERNAL_API_TOKEN，与 {@link #internalApiToken} 同时有效。
     * 环境变量：INTERNAL_API_TOKEN_PREVIOUS。
     */
    @Setter
    private String internalApiTokenPrevious = "";

    /**
     * 账号安全策略（明文兼容开关等）。
     */
    private final SecurityProperties security = new SecurityProperties();

    /**
     * 管理后台网络隔离（IP 白名单等）。
     */
    private final AdminProperties admin = new AdminProperties();

    /**
     * 内部 API（/internal/**）额外网络隔离。
     */
    private final InternalApiProperties internalApi = new InternalApiProperties();

    /** 告警 Webhook（飞书/钉钉）。 */
    private final AlertWebhookProperties alertWebhook = new AlertWebhookProperties();

    /** 日志采样（战斗 DEBUG）。 */
    private final LogSamplingProperties logSampling = new LogSamplingProperties();

    /** 数据源路由（读写分离/影子库）。 */
    private final DatasourceRoutingProperties datasource = new DatasourceRoutingProperties();

    @Data
    public static class AlertWebhookProperties {
        private boolean enabled = false;
        /** feishu | dingtalk */
        private String provider = "feishu";
        private String url = "";
    }

    @Data
    public static class LogSamplingProperties {
        private boolean enabled = true;
        /** 战斗 DEBUG 全局采样率；0=仅白名单。 */
        private double battleDebugSampleRate = 0.0;
        /** 逗号分隔 UID 白名单。 */
        private String battleDebugUidAllowlist = "";
    }

    @Data
    public static class DatasourceRoutingProperties {
        private boolean routingEnabled = false;
        private boolean fallbackReplicaToPrimary = true;
        /** UID Hash 分库数（逻辑库）。 */
        private int shardCount = 16;
    }

    @Data
    public static class AdminProperties {
        /**
         * 逗号分隔 IP / CIDR 白名单；空=不启用（本地开发）。
         * 生产建议：{@code 10.0.0.0/8,192.168.0.0/16} 或 VPN 出口 IP。
         */
        private String ipWhitelist = "";
    }

    @Data
    public static class InternalApiProperties {
        /**
         * 内部服务 IP/CIDR 白名单；空=不启用。
         * 生产建议限制 AI sidecar / 网关出口。
         */
        private String ipWhitelist = "";
    }

    @Data
    public static class NettyProperties {
        /**
         * 业务包处理与数据库访问使用的线程数；小于等于 0 时取 max(4, CPU 核心数×2)。
         */
        private int businessThreads = 0; // 0 表示使用默认推算值
        /**
         * 是否用 Java 21+ 虚拟线程执行业务包分发（IO EventLoop 仍为平台线程）。
         * 适合阻塞 JDBC 场景；默认关闭以保持与现有压测基线一致。
         */
        private boolean virtualThreadsEnabled = false;
    }

    @Data
    public static class GameLoopProperties {
        private boolean enabled = true; // 是否启动 GameServer 主循环
        /** GlobalTick：活动排期、注册表心跳/摘除、玩家被动同步（毫秒）。 */
        private long periodMs = 1000L;
        /**
         * ZoneTick：世界怪刷新与仇恨判定（毫秒）；建议 50–100（10–20Hz）。
         * <=0 表示与 GlobalTick 合并执行。
         */
        private long zonePeriodMs = 100L;
        /**
         * BattleTick：战局 TTL 等独立时钟（毫秒）；<=0 表示挂在 GlobalTick。
         */
        private long battlePeriodMs = 200L;
        /** 战局超时回收秒数；<=0 表示关闭 TTL 回收。 */
        private long battleTtlSeconds = 3600L;
        /** Scene-IO 独立调度线程数。 */
        private int sceneTickThreads = 2;
        /** Battle-CPU 独立调度线程数。 */
        private int battleTickThreads = 4;
        /** Battle 工作队列容量；满则拒绝并触发场景节流。 */
        private int battleWorkQueueCapacity = 256;
        /** true=Scene/Battle 使用独立 ScheduledExecutor（推荐生产开启）。 */
        private boolean isolatedTickPools = true;
        /** 同时活跃战局上限（BattleInstancePool 配额）。 */
        private int maxActiveBattles = 500;
        /** 单帧 Battle Tick CPU 预算（毫秒）。 */
        private int battleTickBudgetMs = 40;
        /** 超额战局是否排队（false=直接拒绝开战）。 */
        private boolean battleQueueEnabled = true;
        /** 在线 Auto 战局最小 Tick 间隔（毫秒）。 */
        private long autoBattleTickIntervalMs = 400L;
        /** 掉线托管战局最小 Tick 间隔（毫秒，降频）。 */
        private long hostedBattleTickIntervalMs = 800L;
    }

    @Data
    public static class KcpProperties {
        /** KCP interval (ms), used with nodelay. */
        private int interval = 20; // KCP 内部时钟间隔
        private int mtu = 1400;    // 单个 UDP 包最大载荷建议值
        /** 重传 / 拥塞：lunarcore.kcp.retransmit.* */
        private final KcpRetransmitProperties retransmit = new KcpRetransmitProperties();
    }

    @Data
    public static class KcpRetransmitProperties {
        /** fixed | adaptive（生产建议 adaptive + fastack） */
        private String algo = "adaptive";
        private long rttThrottleMs = 250L;
        private int maxInflight = 256;
        /** 启用 fastack 自适应（强网提吞吐 / 弱网保实时） */
        private boolean fastAck = true;
        /** 强网 RTT 阈值（毫秒） */
        private long strongRttMs = 50L;
        /** 弱网 RTT 阈值（毫秒） */
        private long weakRttMs = 200L;
    }

    @Data
    public static class BattleSnapshotProperties {
        /** 是否启用战斗中间态快照（断线重连/热恢复）。 */
        private boolean enabled = true;
        /** Redis/内存快照 TTL（秒）。 */
        private long ttlSeconds = 7200L;
        /** 断线回放保留的最近行动增量条数。 */
        private int deltaRingSize = 10;
    }

    @Data
    public static class WalletWalProperties {
        /** 启用本地预扣 + 异步落盘。 */
        private boolean enabled = true;
    }

    @Data
    public static class ConfigGrayProperties {
        /** 开启后仅尾号/指定服生效新配置；关闭=全量。 */
        private boolean enabled = false;
        /** 逗号分隔 UID 尾号，如 0,1,2 */
        private String uidTails = "";
        /** 逗号分隔服务器 ID 白名单 */
        private String serverIds = "";
        private String note = "";

        public java.util.Set<Integer> parsedUidTails() {
            java.util.Set<Integer> out = new java.util.LinkedHashSet<>();
            if (uidTails == null || uidTails.isBlank()) {
                return out;
            }
            for (String part : uidTails.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Integer.parseInt(p));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            return out;
        }

        public java.util.Set<String> parsedServerIds() {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            if (serverIds == null || serverIds.isBlank()) {
                return out;
            }
            for (String part : serverIds.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
            return out;
        }
    }

    @Data
    public static class ProtocolHmacProperties {
        /** 是否在帧 payload 上附加命令号动态盐 HMAC。 */
        private boolean enabled = false;
    }

    @Data
    public static class HotfixProperties {
        /**
         * Hotfix 参数文件，如 {@code classpath:hotfix.json}。
         */
        private String resource = "classpath:hotfix.json"; // 热更 JSON 路径
    }

    @Data
    public static class SessionProperties {
        /**
         * 以秒为单位：超过此时间未收到心跳则断开连接
         */
        private long timeoutSeconds = 60;

        /**
         * 最大同时在线人数（不同 uid）；0 表示不限制。仅在新占用一个在线名额时校验（同账号顶号重登不占新增名额）。
         */
        private int maxOnlinePlayers = 0;
    }

    @Data
    public static class SyncProperties {
        /**
         * 被动（定时）全量同步间隔（毫秒）；0 表示关闭定时同步。
         */
        private long passiveIntervalMs = 60_000L;
        /**
         * 异步玩家数据加载线程数；<=0 时按 CPU 核心数与在线负载估算。
         */
        private int asyncLoadThreads = 0;
        /**
         * 异步任务队列容量；<=0 使用默认值。
         */
        private int asyncLoadQueueCapacity = 1024;
        /**
         * 是否启用按玩家周期持久化。
         */
        private boolean periodicPersistenceEnabled = true;
        /**
         * 每个玩家独立持久化周期（毫秒）；<=0 关闭。
         */
        private long periodicPersistenceIntervalMs = 30_000L;
        /**
         * 是否启用进程内 PlayerData 本地缓存（Caffeine）。
         */
        private boolean cacheEnabled = true;
        /**
         * 缓存 TTL（毫秒）；0 表示仅 LRU 驱逐、不按时间过期。
         */
        private long cacheTtlMs = 45_000L;
        /**
         * 优雅停机时同步落盘所有在线玩家的超时（毫秒）。
         */
        private long shutdownPersistTimeoutMs = 30_000L;
    }

    @Data
    public static class RateLimitProperties {
        /** 登录接口 IP 维度限流：每分钟最大尝试次数 */
        private boolean loginEnabled = true;
        private int loginIpPerMinute = 30;
        /** 登录接口账号维度限流：每分钟最大尝试次数 */
        private int loginAccountPerMinute = 10;
        /** 单连接入站包频率限制 */
        private boolean packetEnabled = true;
        private int packetsPerSecond = 100;
        /** 抽卡 UID/IP 限流 */
        private boolean gachaEnabled = true;
        private int gachaUidPerMinute = 60;
        private int gachaIpPerMinute = 120;
        /** 敏感接口总开关（抽卡/体力等） */
        private boolean sensitiveEnabled = true;
        private int staminaIpPerMinute = 30;
        private int staminaUidPerMinute = 20;
    }

    @Data
    public static class SecurityProperties {
        /**
         * 是否允许无前缀明文密码比对（仅迁移期）。生产与默认均应关闭。
         */
        private boolean allowPlaintextPasswordMatch = false;
    }

    @Data
    public static class CenterProperties {
        /**
         * {@code local} 使用 {@link cn.itcast.demo.mylunarcore.center.LocalCenterServer}；
         * {@code remote} 使用 {@link cn.itcast.demo.mylunarcore.center.RemoteCenterServer}。
         */
        private String mode = "local";
        /** 本游戏节点标识，用于 isLocalNode 比较。 */
        private String localNodeId = "local";
        /** mode=remote 时远端中心服 HTTP 根地址，如 http://center:8080。 */
        private String remoteBaseUrl = "";
        /** 本节点对外广告地址（跨节点迁移重连）。 */
        private String advertiseHost = "127.0.0.1";
        /** 本节点对外游戏端口；0 表示回退 lunarcore.netty-port。 */
        private int advertisePort = 0;
        /** Zone 租约时长（毫秒）；超时未心跳则从注册表摘除。 */
        private long zoneLeaseMs = 15_000L;
        /** 本节点 Zone 心跳间隔（毫秒），建议 <= zoneLeaseMs/3。 */
        private long heartbeatMs = 3_000L;
    }

    @Data
    public static class RedisProperties {
        /** 是否启用 Redis；关闭时票据/在线表/排行榜回退进程内存。 */
        private boolean enabled = false;
        private String host = "127.0.0.1";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        /** 迁移票据 key 前缀。 */
        private String ticketKeyPrefix = "lunar:mig:ticket:";
        /** 在线表 key（SET of uid）。 */
        private String onlineKey = "lunar:online:uids";
        /** 排行榜 ZSET key 前缀，完整 key = prefix + boardType。 */
        private String leaderboardKeyPrefix = "lunar:lb:";
        /** 世界聊天 Pub/Sub 频道。 */
        private String chatWorldChannel = "lunar:chat:world";
        /** 私聊 Pub/Sub 频道（跨节点消息路由）。 */
        private String chatPrivateChannel = "lunar:chat:private";
        /** 组队 partyId → JSON 前缀。 */
        private String partyKeyPrefix = "lunar:party:";
        /** 组队 uid → partyId 前缀。 */
        private String partyUidKeyPrefix = "lunar:party:uid:";
        /** 迁移票据 TTL（秒）。 */
        private long ticketTtlSeconds = 120L;
        /**
         * 启用 Redisson {@code RLock}（需同时 {@code enabled=true}）。
         * 关闭时回退 {@link cn.itcast.demo.mylunarcore.tx.RedisDistributedLockService} SET NX。
         */
        private boolean redissonEnabled = false;
    }

    @Data
    public static class MatchmakingProperties {
        /** 队列等待超时（毫秒），超时踢出并记 match.timeout。0=关闭。 */
        private long queueTimeoutMs = 60_000L;
        /** 等级分段带宽（用于分段匹配桶）。 */
        private int levelBand = 10;
        /** 战力分段带宽。 */
        private int powerBand = 500;
        /** 超时后是否允许降级扩段再匹配。 */
        private boolean expandSegmentOnWait = true;
        /** 扩段前最短等待（毫秒）。 */
        private long expandAfterMs = 15_000L;
        /** 人数不足时是否用机器人补位（联调/低峰）。 */
        private boolean fillWithBots = false;
        /** 补位机器人起始伪 uid（负数）。 */
        private int botUidBase = -1000;
        /**
         * 机器人相对玩家战力/ELO 的强度倍率（1.0=同档）。
         * 低峰补位时按队列玩家平均 power × 该倍率生成 bot。
         */
        private double botPowerRatio = 0.95;
        /** 机器人等级相对玩家平均等级的偏移。 */
        private int botLevelOffset = 0;
        /** 批次匹配间隔（毫秒）；每批统一算 ELO 成房。 */
        private long batchIntervalMs = 100L;
        /** 每批最多处理排队人数。 */
        private int batchSize = 500;
    }

    @Data
    public static class KcpCryptoProperties {
        /** 是否启用 KCP 应用层对称加密（会话密钥在 TCP/TLS 登录后下发）。 */
        private boolean enabled = false;
        /** AES key 长度：16/24/32。 */
        private int keyBytes = 16;
        /**
         * 仅当生产关闭 KCP 加密且确认走 TCP+TLS 时设为 true；默认 false。
         * ProductionSecretsValidator 在 prod 下校验。
         */
        private boolean allowPlaintext = false;
    }

    @Data
    public static class AnalyticsProperties {
        /** 是否输出产品埋点事件。 */
        private boolean enabled = true;
        /** 埋点日志/文件类别；后续可接 Kafka/ClickHouse。 */
        private String sink = "log";
    }

    @Data
    public static class DataRetentionProperties {
        /** 抽卡流水保留天数。 */
        private int gachaHistoryDays = 180;
        /** 战斗审计保留天数。 */
        private int battleAuditDays = 90;
        /** 聊天冷归档天数（内存历史仍短窗）。 */
        private int chatArchiveDays = 30;
        /** 流水类（钱包 ledger 等）热表保留天数，超期归档到冷存储。 */
        private int ledgerArchiveDays = 90;
        /** 管理审计日志保留天数。 */
        private int adminAuditDays = 90;
        /** 业务重放守卫表保留天数。 */
        private int replayGuardDays = 30;
        /** 是否启用定时归档任务。 */
        private boolean archiveJobEnabled = false;
        /**
         * 冷归档 JDBC URL（ClickHouse 等）；空则仅 DELETE 热表。
         * 例：jdbc:clickhouse://ch:8123/lunarcore_cold
         */
        private String coldArchiveJdbcUrl = "";
        /**
         * 按业务类别覆盖保留天数，格式：{@code gacha=180,ledger=30,chat=14,audit=90,battle=60}。
         * 空则使用上方各字段默认值。
         */
        private String categoryRetention = "";
    }

    @Data
    public static class ZoneProperties {
        /**
         * 同 Zone（单分线）最大在线人数；0 表示不限制。
         * 满员时 EnterScene 返回 retcode=6；开启动态分线后自动开新 line。
         * 配合 AOI 异步化后默认抬升至 1000。
         */
        private int maxPlayers = 1000;
        /** 是否拒绝 draining 状态 Zone 的新加入。 */
        private boolean rejectDraining = true;
        /** 是否按 ZoneTick 耗时动态下调有效容量。 */
        private boolean adaptiveCapacityEnabled = true;
        /** 单 Zone tick 预算（毫秒）；超过则逐步降低有效 maxPlayers。 */
        private long adaptiveTickBudgetMs = 8L;
        /** 动态分线：主城热门区满员后自动开 line。 */
        private boolean dynamicLineEnabled = true;
        /** 同一 Plane/Floor 最大分线数。 */
        private int maxLines = 8;
        /** AOI 广播异步化，避免拖垮战斗时钟。 */
        private boolean aoiAsyncEnabled = true;
        private int aoiAsyncWorkers = 2;
        private int aoiAsyncQueueCapacity = 8192;
        /** 每 Zone 单线程 Actor 邮箱串行进出/坐标更新。 */
        private boolean actorMailboxEnabled = true;
        /** AOI 基准格子边长（米）；密度高时会动态缩小。 */
        private float aoiCellSize = 20f;
        /** 单格玩家密度超过该值时缩小 AOI 格子。 */
        private int aoiDensePlayersPerCell = 12;
        /** 动态 AOI 最小格子边长。 */
        private float aoiMinCellSize = 8f;
        /** 远离所有玩家的 NPC/怪物休眠距离。 */
        private float entitySleepDistance = 80f;
        /** 休眠实体心跳频率（Hz）。 */
        private float entitySleepHz = 1f;
        /** LOD：近距离完整同步半径。 */
        private float lodNearRadius = 25f;
        /** LOD：中距离简化同步半径。 */
        private float lodMidRadius = 50f;
        /** 单玩家下行带宽软上限（KB/s）；超限缩小 AOI/降频。 */
        private int playerBandwidthLimitKBps = 256;
        /** 迁移：是否先迁关键位置再异步补全次要数据。 */
        private boolean migrationHotColdSplit = true;
        /** 动态 Zone 分裂阈值（活跃人数）；超过则创建 split Zone。 */
        private int splitThreshold = 250;
        /** 分裂时源 Zone 保留玩家比例（0–100），其余按 UID 哈希迁出。 */
        private int splitRetainPercent = 50;
        /** 战区分配是否优先低负载分线。 */
        private boolean shardPreferLowLoad = true;
    }

    @Data
    public static class WorldProperties {
        /** Cell 边长（世界单位）；用于无缝分片与边界检测。 */
        private float cellSize = 64f;
        /** 是否启用跨 Cell 边界移交检测（实验，默认关）。 */
        private boolean cellHandoffEnabled = false;
        /** 是否启用世界内实时战斗权威（实验；关闭时仍走遭遇进回合战）。 */
        private boolean realtimeCombatEnabled = false;
        /** 实时战斗：单技能最大申报伤害（超限按服务端公式重算/拒绝）。 */
        private int realtimeMaxDeclaredDamage = 50_000;
        /** 实时战斗：技能全局最小 CD（毫秒）。 */
        private long realtimeSkillMinCdMs = 200L;
    }

    @Data
    public static class GachaEconomyProperties {
        /** 是否启用抽卡扣费（关闭仅联调）。 */
        private boolean costEnabled = true;
        /** 抽卡消耗货币 ID（与 Banner cost_item_id 展示对齐，默认 101）。 */
        private int costCurrencyId = 101;
        /** 单抽消耗数量。 */
        private int costPerDraw = 1;
    }

    @Data
    public static class AntiCheatProperties {
        /** 是否启用移动速度校验。 */
        private boolean moveSpeedCheckEnabled = true;
        /** 水平最大速度（单位/秒）；超出则拒绝本次坐标并回写服务器位置。 */
        private float maxMoveSpeed = 25f;
        /** 允许的瞬时突发倍率（网络抖动/斜坡）。 */
        private float moveSpeedBurstFactor = 1.8f;
        /** 两次移动最小间隔（毫秒）；过密包直接丢弃坐标变更。 */
        private long minMoveIntervalMs = 30L;
        /** 是否启用时间戳/序列号/challenge 完整性校验。 */
        private boolean packetIntegrityEnabled = true;
        /** 允许的客户端时钟偏差（毫秒）。 */
        private long maxClientClockSkewMs = 5000L;
        /** 是否启用服务端碰撞体/阻挡网格校验。 */
        private boolean collisionCheckEnabled = true;
        /** 角色胶囊体水平半径。 */
        private float capsuleRadius = 0.4f;
        /** 角色胶囊体高度。 */
        private float capsuleHeight = 1.8f;
        /** 连续穿模告警次数阈值（达阈值记反作弊警告）。 */
        private int collisionWarnThreshold = 5;
        /** 位置纠正是否平滑插值（否则瞬移回写）。 */
        private boolean smoothPositionCorrection = true;
    }

    @Data
    public static class AiAssistProperties {
        /** 总开关：关闭后 AskCoachHint / AskAiAssist 返回功能关闭。 */
        private boolean enabled = true;
        /** 是否启用规则教练（方案 A）。 */
        private boolean ruleCoachEnabled = true;
        /** 是否启用 LLM 助手（方案 B）；关闭时自然语言请求降级到规则教练。 */
        private boolean llmEnabled = false;
        /** 是否允许战斗内战术提示（方案 D；默认关闭）。 */
        private boolean battleHintEnabled = false;
        /**
         * AI 策略代理实验开关：输出可一键采纳的行动指令 JSON（仍需客户端确认）。
         */
        private boolean battleStrategyProxyEnabled = false;
        /** 是否采集战斗结束特征到 data/battle_features.jsonl。 */
        private boolean battleFeatureCollectEnabled = true;
        /** 匹配是否启用等级/战力互补评分（方案 D）。 */
        private boolean matchScoreEnabled = true;
        /** 端侧攻略包相对 dataDir 的路径（方案 E）。 */
        private String guidePackResource = "GuidePack.json";
        /** 主流平台外链/视频攻略目录相对 dataDir 的路径。 */
        private String externalGuideCatalogResource = "ExternalGuideCatalog.json";
        /** 是否在攻略意图问答中附带主流平台视频/链接。 */
        private boolean externalGuideEnabled = true;
        /** LLM HTTP endpoint，如 https://api.example.com/v1/chat/completions。 */
        private String llmEndpoint = "";
        /** LLM API Key；生产环境请用环境变量注入。 */
        private String llmApiKey = "";
        /** LLM 请求超时（毫秒）。 */
        private long llmTimeoutMs = 3000L;
        /** OpenAI 兼容模型名。 */
        private String llmModel = "gpt-4o-mini";
        /** 是否允许大厅聊天 @助手 触发。 */
        private boolean chatMentionEnabled = true;
        /** 聊天触发前缀，默认 @助手。 */
        private String chatMentionPrefix = "@助手";
        /**
         * 是否优先调用旁路 ai-assist-service；失败降级本地规则/LLM。
         */
        private boolean remoteEnabled = false;
        /** 旁路服务根地址，如 http://127.0.0.1:18083 */
        private String remoteBaseUrl = "";
        /** 与旁路服务约定的内部 token。 */
        private String remoteInternalToken = "dev-internal-token";
        /** 旁路 HTTP 超时（毫秒）。 */
        private long remoteTimeoutMs = 2500L;
        /** 单玩家每分钟 AI/教练请求配额（兼容旧配置；未拆分时作兜底）。 */
        private int perUidPerMinute = 20;
        /** 规则教练每分钟配额（便宜配额）。 */
        private int coachPerUidPerMinute = 60;
        /** LLM/远程推理每分钟配额（贵配额）。 */
        private int llmPerUidPerMinute = 10;
        /** 单玩家每日 LLM/远程调用预算（防刷 Token）；0 表示不限制。 */
        private int dailyLlmBudget = 200;
        /** 配额桶空闲超过该分钟数后淘汰，防内存膨胀。 */
        private int quotaIdleEvictMinutes = 30;
        /** 生产默认异步：AskAiAssist 先回执再 AiHintNotify；true 时同步返回完整答案（联调）。 */
        private boolean syncMode = false;
        /** 推理线程池大小；<=0 时取 max(2, CPU)。 */
        private int inferenceThreads = 0;
        /** 推理队列容量；满则降级规则答案。VLM 建议 <=100 防解码打满 CPU。 */
        private int inferenceQueueCapacity = 100;
        /** 回答缓存 TTL 秒；0 关闭缓存。 */
        private long answerCacheTtlSeconds = 120L;
        /** 回答缓存最大条目。 */
        private long answerCacheMaxSize = 10_000L;
        /** RAG 最低召回分；低于则丢弃噪声块。 */
        private double ragMinScore = 1.5;
        /** LLM 回答 max_tokens 提示上限。 */
        private int llmMaxTokens = 512;
        /** 安全词表相对 dataDir 的路径。 */
        private String safetyRulesResource = "AssistSafetyRules.json";
        /** 探索导航 / POI 解说 / 弱点 / 百科内容包相对 dataDir 的路径。 */
        private String featureContentResource = "AssistFeatureContent.json";
        /** 教练提示配置相对 dataDir 的路径。 */
        private String coachTipsResource = "CoachTips.json";
        /** 环境解说冷却秒数（同一 POI 会话内只推一次）。 */
        private int environmentNarrationCooldownSeconds = 45;
        /** 任务渐进提示最高阶（1-3）。 */
        private int questGuidanceMaxStage = 3;
        /** 助手上下文中背包投影槽位上限（跳过已丢弃物品）。 */
        private int contextMaxInventorySlots = 128;
        /** 助手上下文中角色投影数量上限。 */
        private int contextMaxAvatars = 24;
        /** 每个角色写入上下文的天赋条数上限。 */
        private int contextMaxTalentsPerAvatar = 16;
        /** 是否采集全服角色出场使用率（开战 pick / 胜利 win）。 */
        private boolean avatarUsageStatsEnabled = true;
        /** 使用率快照相对 dataDir 的路径。 */
        private String avatarUsageStatsResource = "avatar_usage_stats.json";
        /** 助手上下文中注入的热门角色 TopN。 */
        private int avatarUsageContextTopN = 10;
        /**
         * 开启远程旁路时是否跳过本地 LLM（微服务作为唯一 LLM 出口）；
         * 远程失败仍降级规则教练。
         */
        private boolean preferRemoteOnly = true;
        /** 多轮对话：保留最近 N 轮（问答各算一轮中的一侧）。 */
        private int conversationHistoryMaxTurns = 8;
        /** 多轮历史 TTL 秒（Redis/内存）；超时后会话摘要清空。 */
        private long conversationHistoryTtlSeconds = 1800L;
        /** 历史总字符超限时触发摘要压缩。 */
        private int conversationHistoryMaxChars = 2400;
        /** Redis 会话历史 key 前缀（会再拼 envKeyPrefix）。 */
        private String conversationRedisKeyPrefix = "lunar:assist:conv:";
        /** 环境隔离前缀：dev/prod 等，避免污染共享 Redis/向量集合。 */
        private String envKeyPrefix = "dev";
        /** 免费玩家每日 LLM 预算；<=0 回退 dailyLlmBudget。 */
        private int freeDailyLlmBudget = 10;
        /** 付费/高等级玩家每日 LLM 预算。 */
        private int vipDailyLlmBudget = 50;
        /** 判定 VIP 的最低玩家等级（无付费标记时用等级近似）。 */
        private int vipMinLevel = 40;
        /** 同一问题短时限流：窗口秒。 */
        private int duplicateQuestionWindowSeconds = 60;
        /** 同一问题短时限流：窗口内最多次数。 */
        private int duplicateQuestionMaxPerWindow = 3;
        /** 远程熔断：连续失败次数阈值。 */
        private int remoteCircuitFailureThreshold = 5;
        /** 远程熔断打开后半开探测等待毫秒。 */
        private long remoteCircuitOpenMs = 30_000L;
        /** 相似问句缓存命中阈值（Jaccard，0~1）。 */
        private double similarQuestionCacheThreshold = 0.90;
        /** 三级漏斗二级缓存命中阈值（默认 0.85）。 */
        private double funnelSimilarCacheThreshold = 0.85;
        /** 是否启用三级意图漏斗（本地/缓存/远程）。 */
        private boolean intentFunnelEnabled = true;
        /** 玩家长期记忆 TTL 天。 */
        private int longTermMemoryTtlDays = 30;
        /** AI 策略灰度：策略版本标识（如 prompt-v2 / model-b）。 */
        private String strategyVersion = "baseline";
        /** AI 策略灰度：按 UID 尾号命中的新策略版本；空=不启用策略灰度。 */
        private String grayStrategyVersion = "";
        /** AI 策略灰度 UID 尾号，逗号分隔，如 0,1,2。 */
        private String grayStrategyUidTails = "";
        /** 灰度回滚：fallback 率超过该阈值时强制 baseline。 */
        private double grayAutoRollbackFallbackRate = 0.25;
        /** 默认回复语言（可被请求 locale / Accept-Language 覆盖）。 */
        private String defaultLocale = "zh-CN";
        /** 是否在回答末尾追加通用合规免责声明。 */
        private boolean complianceDisclaimerEnabled = true;
        /** 通用合规免责文案。 */
        private String complianceDisclaimer = "助手建议仅供参考，最终以游戏内实际结果与官方说明为准。";
        /** 是否启用轻量语义安全分类（在关键词之外）。 */
        private boolean semanticSafetyEnabled = true;
        /** 是否对送入 LLM 的文本做 PII 脱敏。 */
        private boolean piiRedactionEnabled = true;
        /** 是否启用主动教练推送（连败/体力满等）。 */
        private boolean proactiveCoachEnabled = true;
        /** 连续战斗失败 N 次触发主动提示。 */
        private int proactiveFailStreak = 3;
        /** 主动提示同一类别冷却秒。 */
        private int proactiveCooldownSeconds = 600;
        /** 体力达到上限触发提示（需玩家 staminaCap 可用时）。 */
        private boolean proactiveStaminaFullEnabled = true;
        /** RAG 热更是否优先增量刷新（失败再全量 rebuild）。 */
        private boolean ragIncrementalReload = true;
        /** 是否启用屏幕截图 VLM 解读。 */
        private boolean vlmEnabled = true;
        /** 截图 JPEG 最大字节（默认 50KB）。 */
        private int vlmMaxJpegBytes = 51200;
        /** 远程 VLM HTTP 地址；空则走本地启发式。 */
        private String vlmEndpoint = "";
        /** VLM API Key。 */
        private String vlmApiKey = "";
        /** VLM 模型名，如 qwen-vl-plus。 */
        private String vlmModel = "qwen-vl-plus";
        /** VLM 请求超时毫秒。 */
        private long vlmTimeoutMs = 4000L;
        /** 是否启用角色人设包装回答。 */
        private boolean personaEnabled = true;
        /** 人设配置相对 dataDir 路径。 */
        private String personaConfigResource = "AssistPersonaConfig.json";
        /** 是否启用 TTS 音频旁路。 */
        private boolean ttsEnabled = true;
        /** 远程 TTS HTTP；空则生成本地占位 OPUS 帧。 */
        private String ttsEndpoint = "";
        /** TTS API Key。 */
        private String ttsApiKey = "";
        /** 语音输入是否减免 LLM 配额（鼓励 ASR）。 */
        private boolean voiceQuotaDiscountEnabled = true;
        /** BOSS 门口徘徊触发主动辅导的秒数。 */
        private int proactiveBossLingerSeconds = 15;
        /** 打扰系数阈值（0~1）；低于该值才允许主动弹窗。 */
        private double proactiveInterruptScoreMax = 0.45;
        /** RAG 新版本语料时间衰减：新鲜窗口天数。 */
        private int ragFreshnessDays = 30;
        /** 新鲜语料分数上调比例（0.3=上调 30%）。 */
        private double ragFreshnessBoost = 0.30;
    }

    @Data
    public static class TlsProperties {
        /**
         * 是否在 Netty TCP 游戏链路上启用 TLS。开启后客户端需使用 TLS 连接同一端口；
         * 与 HTTPS 相同，TLS 握手完成非对称密钥交换，后续应用数据使用对称算法（如 AES-GCM）保护。
         */
        private boolean gameTcpEnabled = false;

        /**
         * 密钥库路径，如 {@code classpath:keystore.p12} 或绝对路径。
         */
        private String keyStore = "classpath:keystore.p12";

        private String keyStorePassword = "changeit"; // 密钥库文件密码

        private String keyStoreType = "PKCS12"; // 密钥库格式

        /**
         * 密钥条目中私钥密码；PKCS12 通常与 keyStorePassword 相同。
         */
        private String keyPassword = "changeit";
    }
}
