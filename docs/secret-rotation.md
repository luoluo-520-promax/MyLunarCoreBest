# 密钥轮换计划（INTERNAL_API_TOKEN 等）

## 目标

生产密钥定期轮换（建议每月），轮换窗口内新旧密钥同时有效，避免切服中断。

## INTERNAL_API_TOKEN

配置（`lunarcore.*` / 环境变量）：

| 配置项 | 环境变量 | 说明 |
|--------|----------|------|
| `internal-api-token` | `INTERNAL_API_TOKEN` | 当前有效密钥 |
| `internal-api-token-previous` | `INTERNAL_API_TOKEN_PREVIOUS` | 宽限期旧密钥（可空） |

校验逻辑：`provided ∈ {current, previous}`（恒定时间比较）。

### 轮换步骤

1. 生成新 token（≥32 字节随机）。
2. 全节点先把 **旧 current** 写入 `INTERNAL_API_TOKEN_PREVIOUS`，再把 **新值** 写入 `INTERNAL_API_TOKEN`，滚动重启/热加载。
3. 调用方改为新 token。
4. 观察 ≥1 个发布周期无 401 后清空 `PREVIOUS`。

## 其他密钥

- IAP：见 `docs/iap-sandbox.md`（Apple Shared Secret / Google 服务账号）。
- KCP AES：见 `docs/kcp-crypto.md`；轮换需客户端同步版本。
- Admin 密码：走工单强制重置，不记入仓库。

## 自动化建议

- CI/Cron 提醒到期（不自动改生产密钥）。
- 启动时 `ProductionSecretsValidator` 拒绝弱口令 / 默认值。
