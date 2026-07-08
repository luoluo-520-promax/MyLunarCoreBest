// 短窗口内重复请求去重并回放首次响应
package cn.itcast.demo.mylunarcore.net;

// 解码后的命令包（cmdId + payload）
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Channel 自定义属性键（玩家 uid 等）
import cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes;
// 可同时拦截入站读与出站写的 Handler 基类
import io.netty.channel.ChannelDuplexHandler;
// Netty 处理器上下文
import io.netty.channel.ChannelHandlerContext;
// 异步 write 回调 Promise
import io.netty.channel.ChannelPromise;
// Channel 属性键类型
import io.netty.util.AttributeKey;

// SHA-256 摘要算法入口
import java.security.MessageDigest;
// java.time：幂等 TTL
import java.time.Duration;
// Arrays：拷贝与 equals/hash 辅助
import java.util.Arrays;
// LRU 顺序映射基础结构
import java.util.LinkedHashMap;
// Map 接口（匿名 LinkedHashMap 需 Entry）
import java.util.Map;

/**
 * 服务端消息幂等（短窗口去重 + 响应回放）。
 *
 * <p>幂等键：uid(若无则按 channel) + cmdId + payloadHash。</p>
 * <p>策略：同一幂等键在 TTL 内重复到达时，直接回放第一次的响应包，并跳过业务处理。</p>
 */
public class PacketIdempotencyHandler extends ChannelDuplexHandler {

    // 当前正在处理的请求对应的幂等键，写出响应时写入缓存
    private static final AttributeKey<IdempotencyKey> CURRENT_KEY =
            AttributeKey.valueOf("mylunarcore.idempotency.currentKey");

    private final Duration ttl; // 缓存条目存活时间

    private final LruCache cache; // 有限容量的 LRU 缓存

    public PacketIdempotencyHandler(Duration ttl, int maxEntries) {
        this.ttl = ttl; // 保存 TTL
        this.cache = new LruCache(maxEntries); // 创建 LRU 缓存
    }

    /**
     * 入站读取：若为重复请求则在 TTL 内直接回放缓存响应。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof GamePacket packet)) {
            super.channelRead(ctx, msg); // 非 GamePacket 原样传递
            return;
        }

        IdempotencyKey key = buildKey(ctx, packet); // 计算幂等键
        cache.evictExpired(ttl); // 先清理过期项
        CachedResponse cached = cache.get(key); // 查找是否已有首个响应
        if (cached != null && !cached.isExpired(ttl)) { // 命中且未过期
            // 回放第一次响应并丢弃重复请求
            ctx.writeAndFlush(cached.toPacket()); // 回放缓存中的首个下行包
            return; // 不再进入业务链路
        }

        // 记录本次请求键，等待首个响应写出时入缓存
        ctx.channel().attr(CURRENT_KEY).set(key); // 绑定当前请求的幂等键
        super.channelRead(ctx, msg); // 继续传递给下游 Handler
    }

    /**
     * 出站写入：捕获本次请求的第一个下行 GamePacket 写入缓存。
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof GamePacket packet) { // 仅缓存 GamePacket
            IdempotencyKey key = ctx.channel().attr(CURRENT_KEY).getAndSet(null); // 取出并清空当前键
            if (key != null) { // 确有上行请求在等待缓存
                // 仅缓存“本次请求的第一个下行包”，避免把后续 notify 等也绑定到幂等回放
                cache.putIfAbsent(key, CachedResponse.from(packet)); // 仅首次写入缓存
            }
        }
        super.write(ctx, msg, promise); // 继续写出到链路下游
    }

    /**
     * 构造幂等键：主体（uid 或 channel）+ cmdId + payload 摘要。
     */
    private IdempotencyKey buildKey(ChannelHandlerContext ctx, GamePacket packet) {
        Long uid = ctx.channel().attr(PlayerChannelAttributes.PLAYER_UID).get(); // 若已登录则取 uid
        String principal = uid != null ? "uid:" + uid : "ch:" + ctx.channel().id().asShortText(); // 未登录退回 channel id
        byte[] payload = packet.getPayload() == null ? new byte[0] : packet.getPayload(); // 空负载统一为空数组
        byte[] hash = sha256(payload); // 负载摘要
        return new IdempotencyKey(principal, packet.getCmdId(), hash); // 组装 record
    }

    /**
     * 计算 SHA-256；算法不可用时退化拷贝弱摘要。
     */
    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); // 标准摘要实例
            return digest.digest(data); // 返回 32 字节摘要
        } catch (Exception e) {
            // 极端兜底：使用内容本身（截断）作为“弱 hash”
            return Arrays.copyOf(data, Math.min(data.length, 32));
        }
    }

    /** 幂等键：谁发的 + 什么命令 + 负载摘要 */
    private record IdempotencyKey(String principal, int cmdId, byte[] payloadHash) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true; // 同一引用
            if (!(o instanceof IdempotencyKey that)) return false; // 类型不匹配
            return cmdId == that.cmdId // 命令号一致
                    && principal.equals(that.principal) // 主体一致
                    && Arrays.equals(payloadHash, that.payloadHash); // 负载摘要一致
        }

        @Override
        public int hashCode() {
            int result = principal.hashCode(); // 起始哈希
            result = 31 * result + cmdId; // 混入 cmdId
            result = 31 * result + Arrays.hashCode(payloadHash); // 混入字节数组哈希
            return result; // 组合哈希
        }
    }

    /** 缓存的下行响应及创建时间 */
    private record CachedResponse(int cmdId, byte[] payload, long createdAtMillis) {
        /**
         * 从出站 {@link GamePacket} 拷贝字段构造缓存条目。
         */
        static CachedResponse from(GamePacket packet) {
            byte[] src = packet.getPayload() == null ? new byte[0] : packet.getPayload(); // 读取正文
            return new CachedResponse(packet.getCmdId(), Arrays.copyOf(src, src.length), System.currentTimeMillis()); // 拷贝防止外部修改
        }

        /** 判断是否超过创建后的 TTL。 */
        boolean isExpired(Duration ttl) {
            return System.currentTimeMillis() - createdAtMillis > ttl.toMillis(); // 超过 TTL 视为过期
        }

        /**
         * 复制负载并封装为可再次写出的 {@link GamePacket}。
         */
        GamePacket toPacket() {
            return new GamePacket(cmdId, Arrays.copyOf(payload, payload.length)); // 拷贝后封装下行包
        }
    }

    /** 访问顺序 LRU，超过 maxEntries 淘汰最旧 */
    private static final class LruCache {
        private final LinkedHashMap<IdempotencyKey, CachedResponse> map; // 线程安全由 synchronized 方法保证

        private LruCache(int maxEntries) {
            this.map = new LinkedHashMap<>(128, 0.75f, true) { // access-order=true 启用 LRU 语义
                @Override
                protected boolean removeEldestEntry(Map.Entry<IdempotencyKey, CachedResponse> eldest) {
                    return size() > maxEntries; // 超出容量剔除最旧（LinkedHashMap LRU）
                }
            };
        }

        synchronized CachedResponse get(IdempotencyKey key) {
            return map.get(key); // 读取缓存条目
        }

        synchronized void putIfAbsent(IdempotencyKey key, CachedResponse response) {
            map.putIfAbsent(key, response); // 仅首次放入
        }

        synchronized void evictExpired(Duration ttl) {
            if (map.isEmpty()) return; // 空表跳过
            map.entrySet().removeIf(e -> e.getValue() == null || e.getValue().isExpired(ttl)); // 移除过期响应
        }
    }
}
