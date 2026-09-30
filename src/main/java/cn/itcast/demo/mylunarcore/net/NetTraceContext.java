// Netty 连接级 traceId 生成与 Channel 属性绑定
package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络链路追踪上下文。
 * <p>
 * 为每条入站 GamePacket 生成唯一 traceId（格式：channelShortId-cmdId-序号），
 * 写入 Channel 属性与 SLF4J MDC，并支持跨服务（ai-assist / admin）透传。
 */
public final class NetTraceContext {

    /** SLF4J MDC 键名，与 logback pattern 中 %X{traceId} 对应。 */
    public static final String MDC_KEY = "traceId";
    public static final String MDC_CMD_ID = "cmdId";
    public static final String MDC_ZONE_ID = "zoneId";
    public static final String MDC_BATTLE_ID = "battleId";

    /** HTTP 透传头：游戏服 → AI sidecar / Admin。 */
    public static final String HTTP_HEADER = "X-Trace-Id";
    /** W3C Trace Context 透传头（与 OpenTelemetry / Tempo / Jaeger 对齐）。 */
    public static final String TRACEPARENT_HEADER = "traceparent";

    /** Channel 属性键：缓存当前连接最近一次请求的 traceId，出站日志复用。 */
    public static final AttributeKey<String> TRACE_ID = AttributeKey.valueOf("traceId");

    /** 全局递增序号，保证同 Channel 同 cmdId 的并发包 traceId 仍唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private NetTraceContext() {
    }

    /**
     * 每个入站包生成新的 traceId（覆盖 Channel 缓存），写入并返回。
     */
    public static String next(Channel channel, GamePacket packet) {
        int cmdId = packet == null ? 0 : packet.getCmdId();
        String traceId = channel.id().asShortText() + "-" + cmdId + "-" + SEQ.incrementAndGet();
        channel.attr(TRACE_ID).set(traceId);
        putBusinessTags(cmdId, null, null);
        return traceId;
    }

    /** 附加业务维度标签，便于按 cmdId / zoneId / battleId 过滤。 */
    public static void putBusinessTags(Integer cmdId, Integer zoneId, Long battleId) {
        if (cmdId != null) {
            MDC.put(MDC_CMD_ID, String.valueOf(cmdId));
        }
        if (zoneId != null) {
            MDC.put(MDC_ZONE_ID, String.valueOf(zoneId));
        }
        if (battleId != null) {
            MDC.put(MDC_BATTLE_ID, String.valueOf(battleId));
        }
    }

    public static void clearBusinessTags() {
        MDC.remove(MDC_CMD_ID);
        MDC.remove(MDC_ZONE_ID);
        MDC.remove(MDC_BATTLE_ID);
    }

    /**
     * 生成简化 W3C traceparent（version-traceId-spanId-flags）。
     * 完整采样由 OTel Java Agent 接管；此处保证日志与 HTTP 可关联。
     */
    public static String toTraceparent(String traceId) {
        String hex = Integer.toHexString(Math.abs((traceId == null ? "0" : traceId).hashCode()));
        while (hex.length() < 32) {
            hex = hex + "0";
        }
        String tid = hex.substring(0, 32);
        String sid = hex.substring(0, 16);
        return "00-" + tid + "-" + sid + "-01";
    }

    /**
     * 确保 Channel 上存在 traceId：已有则复用，否则按 channelId + cmdId + 序号 生成并缓存。
     *
     * @deprecated 优先使用 {@link #next} 做到请求级追踪
     */
    @Deprecated
    public static String ensure(Channel channel, GamePacket packet) {
        return next(channel, packet);
    }

    /** 当前线程 MDC 中的 traceId；无则空串。 */
    public static String current() {
        String v = MDC.get(MDC_KEY);
        return v == null ? "" : v;
    }

    public static void putMdc(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static void clearMdc() {
        MDC.remove(MDC_KEY);
        clearBusinessTags();
    }
}
