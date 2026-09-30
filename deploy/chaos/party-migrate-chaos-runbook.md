# Chaos Mesh：组队迁移 Saga + DeadLetter 演练清单

目标：验证跨节点迁移失败后补偿正确，DeadLetter 可重试并最终一致。

## 试验项

| 场景 | 注入 | 期望 |
|------|------|------|
| 网络延迟 | NetworkChaos delay 2s | 触发 5s 超时回滚，客户端收到 percent/action=retry |
| Pod 删除 | PodChaos kill battle/player | Saga ROLLBACK，DeadLetter 入队 |
| 磁盘满 | IOChaos | 迁移拒绝（circuit）或回滚，不丢补偿日志 |
| 重试风暴 | 连续 chaosFailNext | retryQueue 有界，超 MAX_RETRY 进 DeadLetter |

## 本地单测

```bash
mvn -Dtest=TenGapsOptimizationFlowTest#chaosMonkey_deadLetterRetry test
```

## 集群演练

```bash
kubectl apply -f deploy/chaos/player-migration-chaos.yaml
# 观察指标：party_migrate_dead_letter / MigrateFallbackService.failureRate
```

失败率阈值：`MigrateFallbackService.FAIL_RATE_THRESHOLD=0.01`，超限拒绝新迁移。
