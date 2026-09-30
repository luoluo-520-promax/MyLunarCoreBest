# 日志聚合与链路追踪

## 现状

- 每包 `TraceId` → MDC（`NetTraceContext`）
- HTTP Admin / AI 透传 `X-Trace-Id`
- 本地结构化滚动日志：`logs/structured/app.json.log`（见 `logback-spring.xml`）
- 指标：Micrometer → Prometheus → Grafana（`deploy/`）

## 推荐接入（Loki）

```yaml
# docker-compose 片段示意
services:
  loki:
    image: grafana/loki:2.9.4
    ports: ["3100:3100"]
  promtail:
    image: grafana/promtail:2.9.4
    volumes:
      - ./logs:/var/log/mylunarcore:ro
      - ./deploy/loki/promtail-config.yml:/etc/promtail/config.yml:ro
```

Promtail 抓取 `app.json.log`，Grafana Explore 按 `{app="mylunarcore"} |= "traceId"` 检索。

也可替换为 ELK（Filebeat → Elasticsearch）。

## 按 TraceId 排查

1. 客户端/网关记录响应头或包头 TraceId
2. Loki/ELK：`traceId:"xxxx"`
3. 串联：游戏服 → Admin → ai-assist-service

## 必须脱敏字段

经 `SensitiveDataMasker` / Logback `%maskMsg`：

| 类别 | 示例字段 |
|------|----------|
| 凭证 | password、token、jwt、Bearer、api_key、shared_secret |
| 支付 | receipt、channel_tx、purchase_token、transaction_id |
| PII | email、手机号、身份证号（含裸 18 位模式） |

禁止在 INFO 日志打印完整收据或服务账号 JSON。

## Zipkin 采样

```properties
management.tracing.sampling.probability=0.1
management.zipkin.tracing.endpoint=http://zipkin:9411/api/v2/spans
```

开发默认 `probability=0.0`（不导出）。生产建议 5%–20%，高峰可降采样。

## OpenTelemetry（可选）

挂载 Java Agent 导出 OTLP，与现有 MDC TraceId 对齐为同一 `trace_id` 属性。
