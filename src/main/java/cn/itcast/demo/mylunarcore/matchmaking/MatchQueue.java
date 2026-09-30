package cn.itcast.demo.mylunarcore.matchmaking;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 匹配队列：按 mode+等级/战力分段分桶；支持超时踢出与扩段匹配。
 */
public class MatchQueue {

    public record QueueEntry(int playerId, int mode, int level, int power, long enqueueTime) {
        public int levelSegment(int band) {
            int b = Math.max(1, band);
            return Math.max(0, level) / b;
        }

        public int powerSegment(int band) {
            int b = Math.max(1, band);
            return Math.max(0, power) / b;
        }
    }

    private final Map<String, Deque<QueueEntry>> buckets = new ConcurrentHashMap<>();

    public int enqueue(QueueEntry entry, int levelBand, int powerBand) {
        String key = bucketKey(entry.mode(), entry.levelSegment(levelBand), entry.powerSegment(powerBand));
        removePlayer(entry.playerId());
        Deque<QueueEntry> queue = buckets.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        queue.addLast(entry);
        return queueDepth();
    }

    /** 兼容旧调用：不分段。 */
    public int enqueue(QueueEntry entry) {
        return enqueue(entry, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public boolean cancel(int playerId, int mode) {
        boolean removed = false;
        for (Map.Entry<String, Deque<QueueEntry>> e : buckets.entrySet()) {
            if (!e.getKey().startsWith(mode + ":") && !e.getKey().equals(String.valueOf(mode))) {
                // 仍扫描全部，避免分段键遗漏
            }
            removed |= e.getValue().removeIf(x -> x.playerId() == playerId && x.mode() == mode);
        }
        return removed;
    }

    public void removePlayer(int playerId) {
        for (Deque<QueueEntry> queue : buckets.values()) {
            queue.removeIf(e -> e.playerId() == playerId);
        }
    }

    public List<QueueEntry> pollMatch(int mode, int teamSize, boolean preferCompatibility,
                                      int levelBand, int powerBand, boolean expandSegments) {
        List<String> keys = new ArrayList<>();
        for (String key : buckets.keySet()) {
            if (key.startsWith(mode + ":") || key.equals(String.valueOf(mode))) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        // 先在各分段内尝试；扩段时合并同 mode 所有人
        if (!expandSegments) {
            for (String key : keys) {
                List<QueueEntry> hit = pollFromBucket(buckets.get(key), teamSize, preferCompatibility);
                if (hit.size() == teamSize) {
                    return hit;
                }
            }
            return List.of();
        }
        Deque<QueueEntry> merged = new ConcurrentLinkedDeque<>();
        for (String key : keys) {
            Deque<QueueEntry> q = buckets.get(key);
            if (q != null) {
                merged.addAll(q);
            }
        }
        List<QueueEntry> hit = pollFromBucket(merged, teamSize, preferCompatibility);
        if (hit.size() == teamSize) {
            for (QueueEntry e : hit) {
                removePlayer(e.playerId());
            }
            return hit;
        }
        return List.of();
    }

    public List<QueueEntry> pollMatch(int mode, int teamSize) {
        return pollMatch(mode, teamSize, false, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }

    public List<QueueEntry> pollMatch(int mode, int teamSize, boolean preferCompatibility) {
        return pollMatch(mode, teamSize, preferCompatibility, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
    }

    /**
     * 踢出超时等待者，返回被踢玩家。
     */
    public List<QueueEntry> evictTimedOut(long nowMs, long timeoutMs) {
        if (timeoutMs <= 0) {
            return List.of();
        }
        List<QueueEntry> timedOut = new ArrayList<>();
        for (Deque<QueueEntry> queue : buckets.values()) {
            queue.removeIf(e -> {
                if (nowMs - e.enqueueTime() >= timeoutMs) {
                    timedOut.add(e);
                    return true;
                }
                return false;
            });
        }
        return timedOut;
    }

    public int queueDepth() {
        int n = 0;
        for (Deque<QueueEntry> q : buckets.values()) {
            n += q.size();
        }
        return n;
    }

    public List<Integer> drainAllPlayerIds() {
        List<Integer> ids = new ArrayList<>();
        for (Deque<QueueEntry> queue : buckets.values()) {
            QueueEntry entry;
            while ((entry = queue.pollFirst()) != null) {
                ids.add(entry.playerId());
            }
        }
        buckets.clear();
        return ids;
    }

    private static List<QueueEntry> pollFromBucket(Deque<QueueEntry> queue, int teamSize, boolean preferCompatibility) {
        if (queue == null || queue.size() < teamSize) {
            return List.of();
        }
        if (preferCompatibility && teamSize == 2) {
            return pollBestPair(queue);
        }
        List<QueueEntry> matched = new ArrayList<>();
        for (int i = 0; i < teamSize; i++) {
            QueueEntry entry = queue.pollFirst();
            if (entry == null) {
                for (int j = matched.size() - 1; j >= 0; j--) {
                    queue.addFirst(matched.get(j));
                }
                return List.of();
            }
            matched.add(entry);
        }
        return matched;
    }

    private static List<QueueEntry> pollBestPair(Deque<QueueEntry> queue) {
        List<QueueEntry> snapshot = new ArrayList<>(queue);
        if (snapshot.size() < 2) {
            return List.of();
        }
        double best = Double.NEGATIVE_INFINITY;
        int bestI = -1;
        int bestJ = -1;
        for (int i = 0; i < snapshot.size(); i++) {
            for (int j = i + 1; j < snapshot.size(); j++) {
                double s = MatchCompatibilityScorer.score(snapshot.get(i), snapshot.get(j));
                if (s > best) {
                    best = s;
                    bestI = i;
                    bestJ = j;
                }
            }
        }
        if (bestI < 0 || bestJ < 0) {
            return List.of();
        }
        QueueEntry a = snapshot.get(bestI);
        QueueEntry b = snapshot.get(bestJ);
        queue.removeIf(e -> e.playerId() == a.playerId() || e.playerId() == b.playerId());
        return List.of(a, b);
    }

    static String bucketKey(int mode, int levelSeg, int powerSeg) {
        return mode + ":" + levelSeg + ":" + powerSeg;
    }
}
