package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.net.NetTraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 告警触发时的自动化根因分析骨架：汇总 trace/日志片段，推断最可能瓶颈。
 */
@Service
public class TraceRootCauseAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(TraceRootCauseAnalyzer.class);

    public record Finding(String category, String evidence, double confidence) {}

    public record Analysis(String traceId, List<Finding> findings, String summary) {}

    public Analysis analyze(String traceId, List<String> logSnippets, long dbSlowMs, long redisTimeoutMs) {
        String tid = traceId == null || traceId.isBlank() ? NetTraceContext.current() : traceId;
        List<Finding> findings = new ArrayList<>();
        if (dbSlowMs > 200) {
            findings.add(new Finding("database", "slow_sql_ms=" + dbSlowMs, 0.85));
        }
        if (redisTimeoutMs > 50) {
            findings.add(new Finding("redis", "redis_timeout_ms=" + redisTimeoutMs, 0.8));
        }
        if (logSnippets != null) {
            for (String line : logSnippets) {
                if (line == null) {
                    continue;
                }
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("deadlock") || lower.contains("lock wait")) {
                    findings.add(new Finding("database", line, 0.9));
                } else if (lower.contains("timeout") && lower.contains("redis")) {
                    findings.add(new Finding("redis", line, 0.85));
                } else if (lower.contains("pool") && lower.contains("exhausted")) {
                    findings.add(new Finding("connection_pool", line, 0.88));
                }
            }
        }
        if (findings.isEmpty()) {
            findings.add(new Finding("unknown", "no_strong_signal", 0.3));
        }
        findings.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        String summary = "trace=" + tid + " top=" + findings.get(0).category()
                + " conf=" + findings.get(0).confidence();
        log.warn("trace_rca {}", summary);
        return new Analysis(tid, findings, summary);
    }

    public Map<String, Object> toOpsPayload(Analysis analysis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", analysis.traceId());
        m.put("summary", analysis.summary());
        m.put("findings", analysis.findings());
        m.put("hint", "kubectl exec <pod> -- jstack 1; 对照 Tempo/Jaeger 该 trace");
        return m;
    }
}
