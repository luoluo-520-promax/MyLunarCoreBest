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
     * 热修复与客户端资源 URL 等（JSON），见 {@link cn.itcast.demo.mylunarcore.common.HotfixDataService}。
     */
    private final HotfixProperties hotfix = new HotfixProperties();

    /**
     * 是否在进程内启动控制台线程监听 {@code /reload}（配置与资源热更新，不含代码热更新）。
     */
    @Setter
    private boolean reloadConsoleEnabled = true;

    /**
     * Classpath or file URL, e.g. {@code classpath:data/ActivityScheduling.json}.
     */
    // 活动排期 JSON 资源路径
    @Setter
    private String activityScheduleResource = "classpath:data/ActivityScheduling.json";

    /**
     * 传输层安全：TCP 上启用 TLS（握手阶段非对称协商会话密钥，记录层对称加密，语义与 HTTPS 一致）。
     */
    private final TlsProperties tls = new TlsProperties();

    @Data
    public static class NettyProperties {
        /**
         * 业务包处理与数据库访问使用的线程数；小于等于 0 时取 max(4, CPU 核心数×2)。
         */
        private int businessThreads = 0; // 0 表示使用默认推算值
    }

    @Data
    public static class GameLoopProperties {
        private boolean enabled = true; // 是否启动 GameServer 主循环
        /** Timer period for {@link cn.itcast.demo.mylunarcore.common.GameServer} main loop. */
        private long periodMs = 1000L; // 主循环 tick 间隔（毫秒）
    }

    @Data
    public static class KcpProperties {
        /** KCP interval (ms), used with nodelay. */
        private int interval = 20; // KCP 内部时钟间隔
        private int mtu = 1400;    // 单个 UDP 包最大载荷建议值
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
