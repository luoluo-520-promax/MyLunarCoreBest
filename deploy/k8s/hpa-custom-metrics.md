# Kubernetes HPA 自定义指标（配合 BusinessMetrics）

Prometheus 已暴露：

| 指标 | 用途 |
|------|------|
| `lunarcore_match_queue_depth` | 匹配队列长度 → 扩 match/player Pod |
| `lunarcore_battle_pool_usage` | 战斗实例池使用率 → 扩 battle/game Pod |
| `lunarcore_battle_active` | 进行中战斗数 |

示例 HPA（需 prometheus-adapter）：

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: lunarcore-game
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: lunarcore-game
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Pods
      pods:
        metric:
          name: lunarcore_match_queue_depth
        target:
          type: AverageValue
          averageValue: "30"
    - type: Pods
      pods:
        metric:
          name: lunarcore_battle_pool_usage
        target:
          type: AverageValue
          averageValue: "700m"  # 0.7
```
