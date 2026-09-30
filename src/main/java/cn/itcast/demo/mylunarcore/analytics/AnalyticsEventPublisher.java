package cn.itcast.demo.mylunarcore.analytics;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产品埋点发布器（Analytics）。
 *
 * <p>职责：把登录、抽卡、战斗、充值等关键业务事件，以统一的 JSON 结构写入业务数据日志，
 * 便于下游数据管道（如 ClickHouse / Kafka / 日志采集）做留存、付费、平衡性分析。
 *
 * <p>开关控制：是否上报由 {@link LunarCoreProperties#getAnalytics()}{@code .isEnabled()} 决定，
 * 关闭时所有 track 调用直接短路返回，零开销。
 *
 * <p>当前实现为"结构化日志"方案：每条事件一行 JSON（event/playerId/ts/props），
 * 后续可平滑替换为消息队列发送而不影响调用方。
 */
@Service
public class AnalyticsEventPublisher {

    /** 业务数据日志记录器：LOG 分类为 BUSINESS_DATA。 */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, AnalyticsEventPublisher.class);

    /** 运行时配置，读取埋点开关。 */
    private final LunarCoreProperties properties;
    /** JSON 序列化器，把事件 Map 转成一行 JSON 文本。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyticsEventPublisher(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 通用埋点入口。
     *
     * <p>组装标准事件结构（event 名、玩家 ID、时间戳、业务属性），
     * 序列化失败时退化为简化格式输出（只打事件名与玩家 ID），保证埋点不阻塞业务。
     *
     * @param eventName 事件名（如 login_success、gacha_draw）
     * @param playerId  玩家 ID
     * @param props     业务附加属性（可为 null）
     */
    public void track(String eventName, int playerId, Map<String, Object> props) {
        // 全局开关：关闭埋点则直接丢弃
        if (!properties.getAnalytics().isEnabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", eventName);
        event.put("playerId", playerId);
        event.put("ts", Instant.now().toString());
        event.put("props", props == null ? Map.of() : props);
        try {
            log.info("analytics {}", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            // JSON 序列化异常兜底，至少保留事件名与玩家 ID
            log.info("analytics event={} playerId={}", eventName, playerId);
        }
    }

    /** 记录登录结果事件：成功为 login_success，失败为 login_fail。 */
    public void login(int playerId, boolean success) {
        track(success ? "login_success" : "login_fail", playerId, Map.of());
    }

    /** 记录抽卡事件：携带卡池类型与抽卡次数。 */
    public void gacha(int playerId, int bannerType, int times) {
        track("gacha_draw", playerId, Map.of("bannerType", bannerType, "times", times));
    }

    /** 记录战斗结算事件：携带胜负与战斗 ID。 */
    public void battleResult(int playerId, boolean win, long battleId) {
        track(win ? "battle_win" : "battle_lose", playerId, Map.of("battleId", battleId));
    }

    /** 记录内购支付事件：携带订单号与支付金额（单位：分）。 */
    public void iapPaid(int playerId, String orderId, int amountCents) {
        track("iap_paid", playerId, Map.of("orderId", orderId, "amountCents", amountCents));
    }

    /** AI 问答事件：来源、场景、是否缓存命中、策略版本。 */
    public void aiAsk(int playerId, String scene, String source, boolean cacheHit, String strategyVersion) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("scene", scene == null ? "" : scene);
        props.put("source", source == null ? "" : source);
        props.put("cacheHit", cacheHit);
        props.put("strategyVersion", strategyVersion == null ? "" : strategyVersion);
        track("ai_ask", playerId, props);
    }

    /** AI 反馈事件：有用/无用与原因码。 */
    public void aiFeedback(int playerId, String requestId, boolean useful, String reasonCode) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("requestId", requestId == null ? "" : requestId);
        props.put("useful", useful);
        props.put("reasonCode", reasonCode == null ? "" : reasonCode);
        track("ai_feedback", playerId, props);
    }
}
