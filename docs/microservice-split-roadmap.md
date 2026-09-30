# 微服务拆分路线图

> 原则（ADR-0001）：高一致域（钱包 / 抽卡 / 战斗）留在单体，直至分布式事务与观测成熟。

## 阶段 0 — 现状（演示桩）

| 服务 | 端口 | 状态 |
|------|------|------|
| auth-service | — | 演示 |
| player-service | — | 演示 |
| ai-assist-service | 18083 | 可独立部署（熔断） |
| match-service | 18085 | HTTP 占位 |
| chat-service | 18084 | HTTP 占位 |
| battle-service | — | **不建**（明确禁止） |

真实组队 / 世界聊天 / 匹配仍在单体 `PartyService` / `ChatService` / Matchmaking。

## 阶段 1 — 无状态洪峰域（优先）

1. **匹配大厅**：入队/出队/超时 → MQ 事件（MatchFound）回写单体会话。
2. **世界聊天**：上行经 chat-service，广播 Redis Pub/Sub；私聊离线落库仍可单体。
3. 引入 Spring Cloud LoadBalancer + Micrometer Tracing（Zipkin/OTLP），HTTP 同步调用先可观测再异步化。

## 阶段 2 — Admin / 热更隔离

- 将 ConfigImport / HotReload / 审批流迁到独立 admin-ops 进程，与游戏 tick 隔离。
- 通过内部 Token（支持双密钥轮换）调用单体 `/internal/**`。

## 阶段 3 — 高一致域（暂缓）

钱包 / 抽卡 / 战斗 **保持单体**，直到：

- Outbox + 幂等消费验证通过；
- 跨服务 Saga/TCC 或单写者分片方案压测达标；
- 切服迁移包（钱包+战斗快照）故障演练通过。

## 异步化准备

- 本地已有 `LocalTxLogService`（Outbox 雏形）。
- 拆分服务先发领域事件，再逐步去掉同步 HTTP 写路径。
