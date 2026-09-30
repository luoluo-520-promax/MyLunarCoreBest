package cn.itcast.demo.mylunarcore.hall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨服在线私聊可靠投递：每条消息分配单调序列号，等待 ACK；超时重试，保证同会话有序。
 */
@Service
public class ReliablePrivateChatDelivery {

    private static final Logger log = LoggerFactory.getLogger(ReliablePrivateChatDelivery.class);

    public static final int MAX_RETRY = 3;
    public static final long ACK_TIMEOUT_MS = 2_000L;

    public record PendingMessage(long msgId, long seq, int senderId, int targetId, String content,
                                 long sentAtMs, int retries) {}

    private final Map<Integer, AtomicLong> seqByPair = new ConcurrentHashMap<>();
    private final Map<Long, PendingMessage> pending = new ConcurrentHashMap<>();
    private final AtomicLong msgIdGen = new AtomicLong(1);

    /** 为 sender→target 会话分配下一序列号并登记待 ACK。 */
    public PendingMessage enqueue(int senderId, int targetId, String content) {
        int pairKey = pairKey(senderId, targetId);
        long seq = seqByPair.computeIfAbsent(pairKey, k -> new AtomicLong(0)).incrementAndGet();
        long msgId = msgIdGen.getAndIncrement();
        PendingMessage pm = new PendingMessage(msgId, seq, senderId, targetId,
                content == null ? "" : content, System.currentTimeMillis(), 0);
        pending.put(msgId, pm);
        return pm;
    }

    /** 客户端 ACK：按 msgId 确认，返回是否命中。 */
    public boolean ack(long msgId, int playerId) {
        PendingMessage pm = pending.get(msgId);
        if (pm == null) {
            return false;
        }
        if (pm.targetId() != playerId && pm.senderId() != playerId) {
            return false;
        }
        pending.remove(msgId);
        return true;
    }

    /**
     * 扫描超时未 ACK 消息，返回需要重试的列表（并更新 retries；超限则丢弃并记日志）。
     */
    public java.util.List<PendingMessage> drainRetries(long nowMs) {
        java.util.List<PendingMessage> out = new java.util.ArrayList<>();
        // 快照 key，避免 ConcurrentHashMap 迭代中 put/remove 导致漏扫
        for (Long msgId : java.util.List.copyOf(pending.keySet())) {
            PendingMessage pm = pending.get(msgId);
            if (pm == null) {
                continue;
            }
            if (nowMs - pm.sentAtMs() < ACK_TIMEOUT_MS) {
                continue;
            }
            if (pm.retries() >= MAX_RETRY) {
                pending.remove(msgId, pm);
                log.warn("private_chat_delivery_exhausted msgId={} target={} seq={}",
                        pm.msgId(), pm.targetId(), pm.seq());
                continue;
            }
            PendingMessage next = new PendingMessage(pm.msgId(), pm.seq(), pm.senderId(), pm.targetId(),
                    pm.content(), nowMs, pm.retries() + 1);
            if (pending.replace(msgId, pm, next)) {
                out.add(next);
            }
        }
        return out;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** 期望下一 seq（用于乱序检测）。 */
    public long expectedNextSeq(int senderId, int targetId) {
        AtomicLong a = seqByPair.get(pairKey(senderId, targetId));
        return a == null ? 1L : a.get() + 1;
    }

    private static int pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return lo * 31 + hi;
    }
}
