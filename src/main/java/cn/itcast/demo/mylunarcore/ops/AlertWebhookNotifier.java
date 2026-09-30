package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警 Webhook：ERROR/WARN 关键事件推送到飞书/钉钉。
 */
@Component
public class AlertWebhookNotifier {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, AlertWebhookNotifier.class);

    private final LunarCoreProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final AtomicLong sent = new AtomicLong();

    public AlertWebhookNotifier(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public void notifyWarn(String title, String detail) {
        send("WARN", title, detail);
    }

    public void notifyError(String title, String detail) {
        send("ERROR", title, detail);
    }

    private void send(String level, String title, String detail) {
        String url = properties.getAlertWebhook().getUrl();
        if (url == null || url.isBlank() || !properties.getAlertWebhook().isEnabled()) {
            return;
        }
        try {
            String body = buildPayload(level, title, detail, properties.getAlertWebhook().getProvider());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((r, ex) -> {
                        if (ex != null) {
                            log.debug("alert webhook failed: {}", ex.toString());
                        } else {
                            sent.incrementAndGet();
                        }
                    });
        } catch (Exception e) {
            log.debug("alert webhook skip: {}", e.toString());
        }
    }

    public static String buildPayload(String level, String title, String detail, String provider) {
        String text = "[" + level + "] " + title + "\n" + (detail == null ? "" : detail);
        if ("dingtalk".equalsIgnoreCase(provider)) {
            return "{\"msgtype\":\"text\",\"text\":{\"content\":"
                    + jsonString(text) + "}}";
        }
        // 默认飞书
        return "{\"msg_type\":\"text\",\"content\":{\"text\":"
                + jsonString(text) + "}}";
    }

    private static String jsonString(String s) {
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
        return "\"" + escaped + "\"";
    }

    public long sentCount() {
        return sent.get();
    }

    public Map<String, Object> status() {
        return Map.of(
                "enabled", properties.getAlertWebhook().isEnabled(),
                "provider", properties.getAlertWebhook().getProvider(),
                "sent", sent.get());
    }
}
