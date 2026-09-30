# 压测基线结论（2026-08）

> 数值分两档：**开发参考机**（约 8 核 / 16GB）与 **推荐生产最小规格**（16 核 / 32GB）。上线前必须在目标硬件重跑 `scripts/load/k6_baseline.js` 后回填 SLA。

## 1. 硬件档位参考

| 档位 | CPU / 内存 | 心跳 CCU（稳定） | 匹配 req/s | 备注 |
|------|------------|------------------|------------|------|
| 开发机 | 8C / 16G | 8k–12k | 3k–5k | KCP 加密开；JVM 默认 |
| 生产最小 | 16C / 32G | 18k–25k | 6k–10k | Hikari 40；Redis 同机房 |
| 生产标准 | 32C / 64G ×2 节点 | 单节点 25k–35k | 12k+（可拆匹配） | Center remote + Redis |

| 场景 | 单核约当量 | 开发机建议上限 | p95 延迟参考 |
|------|------------|----------------|--------------|
| 在线心跳 TCP/KCP | ~2.5k–4k CCU/核 | 8k–12k | &lt;80ms（同城） |
| 匹配入队/出队 | ~800–1.5k req/s/核 | 3k–5k | &lt;120ms |
| 网关 `/auth/health`（k6） | — | 200 VUs | &lt;500ms |

资源粗估（开发机、KCP+AES-GCM+HMAC）：

- CPU：心跳满载约 60–75%
- 堆：建议 `-Xms4g -Xmx8g`；metaspace 256m
- Redis：票据/快照 &lt;2GB（TTL 正常）

## 2. 优化建议

1. VirtualThreads：`lunarcore.netty.virtual-threads-enabled=true`
2. KCP：`lunarcore.kcp.retransmit.algo=adaptive`
3. 告警：见 `deploy/prometheus/rules/business-alerts.yml`
4. **上线前 1.5 倍峰值校准**：以预期峰值 CCU/QPS ×1.5 跑 `k6_baseline.js`（`PROFILE=prod`），按实测 p95/失败率回调 Prometheus 阈值（当前登录失败 1%/5m、5%/5m 为初值，避免误报或漏报）

## 3. 复现命令

```bash
k6 run scripts/load/k6_baseline.js
k6 run -e SCENARIO=heartbeat scripts/load/k6_baseline.js
k6 run -e SCENARIO=matchmaking scripts/load/k6_baseline.js
```

## 4. CI（夜间性能回归）

GitHub Actions `nightly-load` job（见 `.github/workflows/ci.yml`）：定时跑 k6 烟雾阈值，失败则阻断合并到 release 分支。

## 5. 上线前 1.5× 峰值压测与告警校准

初值告警（如登录失败率 `>1%/5m`、`>5%/5m`）仅为起点，上线前请：

1. 在目标硬件跑 `k6 run -e PROFILE=prod scripts/load/k6_baseline.js`，负载取预期峰值的 **1.5 倍**。
2. 记录稳定态 p95/p99、错误率；若 1.5× 下错误率仍 &lt;0.3%，可将 warning 从 1% 下调到 0.5%，避免漏报。
3. 若压测噪声导致误报，将 `business-alerts.yml` 中对应阈值上调，并拉长 `for` 窗口（如 5m→10m）。
4. 回填本节「回填清单」与 Grafana 面板注释，冻结为该版本基线。

```bash
# 示例：1.5× 生产目标 VU（按 PROFILE 内基线再 ×1.5）
k6 run -e PROFILE=prod -e VUS_MULT=1.5 scripts/load/k6_baseline.js
```

## 6. 回填清单

- [ ] 目标机单进程心跳 CCU
- [ ] 目标机匹配 req/s 与超时率
- [ ] 开启 KCP 加密 / HMAC 后的吞吐衰减比例
- [ ] VirtualThreads 开/关对比 p99
- [ ] Redisson 开/关钱包扣费 p99
- [ ] **1.5× 峰值**下登录/抽卡/战斗错误率与告警阈值终值
