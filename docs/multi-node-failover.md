# 多节点切服与故障转移验证清单

前置：`docker compose -f deploy/docker-compose.multi-node.yml up -d`，两套主工程 JVM（node-a / node-b）共享同一 MySQL/Redis。

## 配置基线

```properties
lunarcore.redis.enabled=true
lunarcore.redis.host=127.0.0.1
lunarcore.redis.port=6379
lunarcore.center.mode=remote
lunarcore.center.remote-base-url=http://<center-host>:8080
lunarcore.center.advertise-host=<本机对外IP>
lunarcore.center.advertise-port=9000
lunarcore.kcp-crypto.enabled=true
```

node-b 使用不同 `advertise-port`（如 `9001`）与 `server.port`。

## 必测用例

| ID | 场景 | 期望 |
|----|------|------|
| MN-1 | 登录后拿迁移票据切服 | 票据一次性消费；目标节点会话可恢复 |
| MN-2 | 票据过期后切服 | 明确拒绝，需重新登录 |
| MN-3 | 停掉 node-a（进程 kill） | Zone 租约过期后 Center 摘除；新进玩家落到存活节点 |
| MN-4 | node-a 标记 draining | 不再承接新进入；存量可迁出或自然退出 |
| MN-5 | Redis 短暂不可用 | 票据/在线表失败有明确错误；恢复后可继续 |
| MN-6 | 跨节点组队（RedisPartyStore） | 队员分别在不同节点仍可见同一队伍 |
| MN-7 | 匹配队列跨节点 | 同分段可凑桌；超时踢出与成功率指标正确 |
| MN-8 | 切服前场景/战斗快照 | `SceneSnapshotService` + `BattleSnapshotService` 可在目标节点/重连恢复 |
| MN-9 | 迁移限流 | `lunarcore.migration.max-per-second` 触发 retcode=8 |
| MN-10 | Chaos Mesh | 见 `deploy/chaos/player-migration-chaos.yaml`（分区/杀 Redis） |

```properties
lunarcore.migration.max-per-second=200
lunarcore.migration.max-retries=3
lunarcore.redis.redisson-enabled=true
```

## 压测建议

在目标硬件上跑 `scripts/load/k6_baseline.js`，记录登录失败率、p95、匹配成功率，回填 `deploy/prometheus/rules/business-alerts.yml` 与 Bucket4j 限流参数。

## 高可用扩展（未默认启用）

- MySQL：主从 / Group Replication；应用只配主写地址，读可拆从库。
- Redis：Sentinel（compose profile `ha`）或 Cluster；生产勿单点。
- 故障切换预案：见 `docs/production-hardening.md` §稳定性与数据一致性。
