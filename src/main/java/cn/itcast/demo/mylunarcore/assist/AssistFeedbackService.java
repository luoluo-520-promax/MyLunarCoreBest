package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 收集玩家对助手回答的“有用/无用”反馈，供运营统计与效果评估。
 * <p>
 * retcode：0 成功；1 未登录（uid 非法）；5 requestId 非法。
 */
@Service
public class AssistFeedbackService {
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistFeedbackService.class);

    private final AtomicLong feedbackCount = new AtomicLong();
    private final AtomicLong usefulCount = new AtomicLong();
    private final AtomicLong notUsefulCount = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> reasonCounters = new ConcurrentHashMap<>();
    private final BusinessMetrics businessMetrics;
    private final AnalyticsEventPublisher analyticsEventPublisher;

    public AssistFeedbackService(BusinessMetrics businessMetrics,
                                 AnalyticsEventPublisher analyticsEventPublisher) {
        this.businessMetrics = businessMetrics;
        this.analyticsEventPublisher = analyticsEventPublisher;
    }

    public record FeedbackResult(int retcode) {
    }

    public FeedbackResult submit(long uid, String requestId, boolean useful, String reasonCode) {
        if (uid <= 0) {
            return new FeedbackResult(1);
        }
        String rid = requestId == null ? "" : requestId.trim();
        if (rid.isEmpty() || rid.length() > 128) {
            return new FeedbackResult(5);
        }
        String reason = normalizeReason(reasonCode);
        feedbackCount.incrementAndGet();
        if (useful) {
            usefulCount.incrementAndGet();
        } else {
            notUsefulCount.incrementAndGet();
            reasonCounters.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        }
        businessMetrics.recordAiFeedback(useful);
        analyticsEventPublisher.aiFeedback((int) (uid & 0xffffffffL), rid, useful, reason);
        log.info("ai_feedback uidHash={} requestId={} useful={} reasonCode={}",
                Integer.toHexString(Long.hashCode(uid)), rid, useful, reason);
        return new FeedbackResult(0);
    }

    public double usefulRate() {
        long total = feedbackCount.get();
        if (total <= 0) {
            return 0;
        }
        return usefulCount.get() * 1.0 / total;
    }

    public long getFeedbackCount() {
        return feedbackCount.get();
    }

    public long getUsefulCount() {
        return usefulCount.get();
    }

    public long getNotUsefulCount() {
        return notUsefulCount.get();
    }

    public Map<String, Long> reasonSnapshot() {
        Map<String, Long> snap = new ConcurrentHashMap<>();
        reasonCounters.forEach((k, v) -> snap.put(k, v.get()));
        return Map.copyOf(snap);
    }

    private static String normalizeReason(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return "other";
        }
        String r = reasonCode.trim().toLowerCase(Locale.ROOT);
        return switch (r) {
            case "inaccurate", "outdated", "offensive", "gacha_inducement", "other" -> r;
            default -> "other";
        };
    }
}
