// 全局会话注册表：uid 与会话令牌的双向索引及超时清理
package cn.itcast.demo.mylunarcore.player;

// 全局配置：会话超时秒数、最大在线人数等
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

// 在线玩家 Tick 注册表，会话移除时联动注销
import cn.itcast.demo.mylunarcore.common.PlayerTickRegistry;
import cn.itcast.demo.mylunarcore.center.OnlinePresenceService;

// Spring Bean 作用域常量
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

// Bean 作用域注解
import org.springframework.context.annotation.Scope;

// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J 日志接口
import org.slf4j.Logger;

// Spring 组件注解
import org.springframework.stereotype.Component;

// KCP 连接对象
import io.jpower.kcp.netty.Ukcp;

// Netty 通道
import io.netty.channel.Channel;

// 密码学安全随机数，生成会话令牌
import java.security.SecureRandom;

// 字节数组转十六进制字符串
import java.util.HexFormat;

// Optional 包装，避免 null 返回
import java.util.Optional;

// 会话快照集合类型
import java.util.Collection;

// 可变列表，承载 sessionsByUid.values() 的快照拷贝
import java.util.ArrayList;

// 并发哈希表，uid -> GameSession 主索引
import java.util.concurrent.ConcurrentHashMap;

// 单线程定时线程工厂
import java.util.concurrent.Executors;

// 调度线程池接口
import java.util.concurrent.ScheduledExecutorService;

// 时间单位
import java.util.concurrent.TimeUnit;

/**
 * L1 在线会话管理器。
 * <p>
 * 使用 {@link ConcurrentHashMap} 按 uid O(1) 索引 {@link GameSession}，
 * 纳秒级内存访问，与 L2 MySQL 玩家数据解耦；同时维护 sessionToken 双向映射供免密登录。
 * </p>
 */
@Component // 注册为 Spring 单例 Bean
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON) // 明确声明单例作用域
public class GameSessionManager {

    /** 本类专用日志，分类 BUSINESS_SESSION */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SESSION, GameSessionManager.class);

    /** 全局配置，读取 session.timeoutSeconds、maxOnlinePlayers 等 */
    private final LunarCoreProperties properties;

    /** Tick 注册表，removeSession 时 unregister 对应 uid */
    private final PlayerTickRegistry playerTickRegistry;

    /** 跨节点在线表（Redis 或内存） */
    private final OnlinePresenceService onlinePresenceService;

    /** 主索引：uid -> 当前在线 GameSession */
    private final ConcurrentHashMap<Long, GameSession> sessionsByUid = new ConcurrentHashMap<>();

    /** 正向索引：sessionToken -> uid，供 resolveToken 免密登录 */
    private final ConcurrentHashMap<String, Long> tokenToUid = new ConcurrentHashMap<>();

    /** 反向索引：uid -> sessionToken，供 bindSessionToken 使旧 token 失效 */
    private final ConcurrentHashMap<Long, String> uidToToken = new ConcurrentHashMap<>();

    /** 密码学安全随机数生成器，用于 newTokenBytes */
    private final SecureRandom secureRandom = new SecureRandom();

    /** 单线程定时任务：周期性扫描并清理超时会话 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    /**
     * 构造注入并启动超时清理定时任务。
     *
     * @param properties         全局配置
     * @param playerTickRegistry Tick 注册表
     */
    public GameSessionManager(LunarCoreProperties properties,
                              PlayerTickRegistry playerTickRegistry,
                              OnlinePresenceService onlinePresenceService) {
        this.properties = properties;
        this.playerTickRegistry = playerTickRegistry;
        this.onlinePresenceService = onlinePresenceService;
        startCleaner(); // 启动后台超时扫描
    }

    /**
     * 周期性扫描所有会话的 lastActiveMillis，超时则移除会话并关闭 Channel。
     */
    private void startCleaner() {
        long timeoutMillis = properties.getSession().getTimeoutSeconds() * 1000L; // 配置秒数转毫秒
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis(); // 当前系统时间
            sessionsByUid.forEach((uid, session) -> { // 遍历所有在线会话（弱一致快照）
                long last = session.getLastActiveMillis(); // 该会话最后活跃时间
                if (now - last > timeoutMillis) { // 超过配置的无心跳/无业务时长
                    log.info("Session timeout, uid={}", uid);
                    removeSession(uid); // 清理会话、token、Tick
                    Channel ch = session.getChannel();
                    if (ch != null && ch.isActive()) {
                        ch.close(); // 主动关闭底层 TCP/KCP 连接
                    }
                }
            });
        }, 5, 5, TimeUnit.SECONDS); // 启动后 5 秒首次执行，之后每 5 秒扫描一次
    }

    /**
     * 创建或替换指定 uid 的会话：同一 uid 重复登录（顶号）会关闭旧 Channel。
     *
     * @param uid     玩家 uid
     * @param channel 新连接的 Netty Channel
     * @param ukcp    KCP 对象，TCP-only 时为 null
     * @return 当前生效的 GameSession
     */
    public GameSession createOrReplace(long uid, Channel channel, Ukcp ukcp) {
        GameSession old = sessionsByUid.put(uid, new GameSession(uid, channel, ukcp)); // put 返回被替换的旧会话
        if (old != null) { // 发生顶号
            log.info("Replace existing session: uid={}, oldChannelId={}, newChannelId={}",
                    uid, safeChannelId(old.getChannel()), safeChannelId(channel));
            Channel oldChannel = old.getChannel();
            if (oldChannel != null && oldChannel.isActive()) {
                oldChannel.close(); // 踢掉旧连接
            }
        }
        onlinePresenceService.markOnline(uid);
        return sessionsByUid.get(uid); // 返回新建立的会话
    }

    /**
     * 新连接占用在线名额前调用：已达上限且该 uid 当前不在线则拒绝新登录。
     *
     * @param uid 待登录玩家 uid
     * @return true 表示可接受；false 表示服务器已满
     */
    public boolean canAcceptNewOnlineSlot(long uid) {
        int max = properties.getSession().getMaxOnlinePlayers(); // <=0 表示不限制
        if (max <= 0) {
            return true;
        }
        if (sessionsByUid.containsKey(uid)) {
            return true; // 已在线玩家重连/顶号不占新增名额
        }
        return sessionsByUid.size() < max; // 新 uid 登录需检查当前总人数
    }

    /**
     * @return 当前在线会话数量（sessionsByUid.size()）
     */
    public int getOnlinePlayerCount() {
        return sessionsByUid.size();
    }

    /**
     * 根据会话令牌解析 uid。
     *
     * @param token 客户端持有的 sessionToken
     * @return 有效 token 对应的 uid；无效或空 token 返回 null
     */
    public Long resolveToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return tokenToUid.get(token.trim()); // 查 token -> uid 正向表
    }

    /**
     * 为指定 uid 颁发新会话令牌，并使旧令牌从两张索引表中移除（顶号、重复登录场景）。
     *
     * @param uid 玩家 uid
     * @return 新生成的 64 字符 hex 令牌
     */
    public String bindSessionToken(long uid) {
        String previous = uidToToken.remove(uid); // 取出并移除旧 token
        if (previous != null) {
            tokenToUid.remove(previous); // 同步删除 token -> uid 映射，旧 token 立即失效
        }
        String newToken = newTokenBytes(); // 生成 256bit 随机 hex
        uidToToken.put(uid, newToken); // 写入 uid -> token
        tokenToUid.put(newToken, uid); // 写入 token -> uid
        GameSession s = sessionsByUid.get(uid);
        if (s != null) {
            s.setSessionToken(newToken); // 会话对象同步持有最新 token（便于调试）
        }
        return newToken;
    }

    /**
     * 生成 32 字节随机数的 hex 字符串表示。
     *
     * @return 64 字符小写 hex（256 bit 熵）
     */
    private String newTokenBytes() {
        byte[] b = new byte[32]; // 256 bit
        secureRandom.nextBytes(b); // 密码学安全随机填充
        return HexFormat.of().formatHex(b); // 转为连续 hex 字符串
    }

    /**
     * 刷新指定 uid 会话的最后活跃时间戳（心跳或业务包处理时调用）。
     *
     * @param uid 玩家 uid
     */
    public void updateActive(long uid) {
        GameSession session = sessionsByUid.get(uid);
        if (session != null) {
            session.setLastActiveMillis(System.currentTimeMillis());
        }
    }

    /**
     * @param uid 玩家 uid
     * @return Optional 包装的会话，不存在则为 empty
     */
    public Optional<GameSession> findByUid(long uid) {
        return Optional.ofNullable(sessionsByUid.get(uid));
    }

    /**
     * @param uid 玩家 uid
     * @return 会话引用，不存在时 null
     */
    public GameSession getOrNull(long uid) {
        return sessionsByUid.get(uid);
    }

    /**
     * @return 当前全部在线会话的快照集合（ArrayList 拷贝，避免遍历期间结构性修改问题）
     */
    public Collection<GameSession> snapshotSessions() {
        return new ArrayList<>(sessionsByUid.values());
    }

    /**
     * 移除指定 uid 的会话：清理 token 双向索引、从 sessionsByUid 删除、注销 Tick。
     *
     * @param uid 玩家 uid
     */
    public void removeSession(long uid) {
        String t = uidToToken.remove(uid); // 取出 token
        if (t != null) {
            tokenToUid.remove(t); // 删除 token 正向索引
        }
        sessionsByUid.remove(uid); // 删除 uid 主索引
        playerTickRegistry.unregister(uid); // 停止对该玩家的 Tick 调度
        onlinePresenceService.markOffline(uid);
    }

    /**
     * 优雅关闭超时清理线程。
     */
    public void shutdownGracefully() {
        cleaner.shutdown();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleaner.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 安全获取 Channel id 字符串，避免 null 或异常导致日志格式化失败。
     *
     * @param ch Netty 通道，可为 null
     * @return 通道 longText id 或 "unknown"
     */
    private String safeChannelId(Channel ch) {
        try {
            return ch.id().asLongText();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
