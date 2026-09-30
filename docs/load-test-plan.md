# 正式压测计划

## 目标（开发机初值，非最终 SLA）

| 场景 | 目标 QPS / CCU | p95 | 错误率 | 脚本 |
|------|----------------|-----|--------|------|
| 登录/会话 | 500 req/s | &lt;200ms | &lt;1% | `SCENARIO=login` |
| 场景移动（协议压测待接） | 8k CCU | &lt;80ms | &lt;1% | 专用客户端 |
| 回合战结算 | 1k settle/s | &lt;150ms | &lt;1% | 专用客户端 |
| 抽卡洪峰 | 2k draw/s | &lt;300ms | &lt;2% | `SCENARIO=gacha_burst` |
| 匹配入队 | 3k req/s | &lt;120ms | &lt;1% | `SCENARIO=matchmaking` |

当前 `k6_baseline.js` 以 HTTP 健康检查近似洪峰；**协议压测客户端就绪前不得将上表直接当作线上 SLA**。

## 执行节奏

1. 每次大版本前：`PROFILE=prod` 全场景复跑，回填 `load-test-baseline.md`。
2. CI `nightly-load`：烟雾（5 VU / 30s），失败告警不阻断日常 PR。
3. Release 分支：`PROFILE=prod` 失败则阻断合并。

## 命令

```bash
k6 run -e PROFILE=dev scripts/load/k6_baseline.js
k6 run -e PROFILE=prod -e SCENARIO=matchmaking -e BASE_URL=https://gateway:18443 scripts/load/k6_baseline.js
```
