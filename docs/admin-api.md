# Admin HTTP API（联调摘要）

Base：`http://localhost:8080`（仅内网，见 `docs/network-isolation.md`）

认证：Admin 登录 Session + CSRF；运维写接口需 `admin:ops:write`。

## 配置与热更

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/admin/ops/import/json-files` | 导入 JSON；`dryRun=true` 可演练 |
| POST | `/api/admin/ops/import/rollback` | 按文件列表回滚 |
| POST | `/api/admin/ops/rollback?confirm=ROLLBACK&reason=&version=` | 一键回滚（二次确认） |
| POST | `/api/admin/ops/reload` | 热重载 |
| GET | `/api/admin/ops/import/audit` | 发布审计 |
| GET | `/api/admin/config-versions` | 配置版本列表 |
| GET | `/api/admin/config-versions/diff` | 版本对比 |
| GET | `/api/admin/config-versions/rollback-impact` | 回滚影响评估 |

页面：`/admin/config-versions.html`、`/admin/config-rollback.html`

## 玩家存档

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/admin/ops/player-data/{uid}/export` | 导出聚合 JSON（高危，可走审批） |
| POST | `/api/admin/ops/player-data/{uid}/import` | 导入；`dryRun=true` 仅校验 |

## 敏感操作审批

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/admin/ops/approvals` | 创建审批单 |
| POST | `/api/admin/ops/approvals/{ticketId}/approve` | 通过 |
| POST | `/api/admin/ops/approvals/{ticketId}/reject` | 拒绝 |

## 聊天审核

| Method | Path | 说明 |
|--------|------|------|
| POST | `/api/admin/chat/mute` | 禁言 |
| POST | `/api/admin/chat/unmute` | 解禁 |
| POST | `/api/admin/chat/kick` | 踢出 |
| POST | `/api/admin/chat/reload-words` | 重载 `ChatSensitiveWords.json` |
| POST | `/api/admin/chat/report` | 举报处理入口 |

## 错误码与客服

| Method | Path | 说明 |
|--------|------|------|
| GET | `/api/admin/error-codes` | 业务错误码目录（含轻量 OpenAPI 摘要） |
| GET | `/api/admin/support/tickets` | 工单列表 |
| GET | `/api/admin/support/tickets/{ticketId}` | 工单详情 |
| POST | `/api/admin/support/tickets/{ticketId}/reply` | 回复 |
| POST | `/api/admin/support/tickets/{ticketId}/close` | 关闭 |

## 建议

前端联调可导入 Postman：将上表整理为 Collection；完整 OpenAPI（springdoc）可作为后续迭代，当前以本手册 + 协议文档为准。
