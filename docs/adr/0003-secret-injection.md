# ADR-0003：生产密钥强制环境变量 / K8s Secret 注入

- 状态：Accepted
- 日期：2026-08

## 背景

`dev-internal-token`、弱 JWT、默认库密曾出现在配置文件，存在误上生产风险。

## 决策

`ProductionSecretsValidator`（prod）强制：

- `INTERNAL_API_TOKEN` / `DB_PASSWORD` 来自环境
- 长度 ≥32，含字母、数字、特殊字符
- 拒绝黑名单弱值；IAP mock 关闭；KCP/HMAC/Admin 白名单必开

过渡：K8s Secret / Spring Cloud Config（加密后端）/ Vault。

## 后果

本地 prod 启动需显式注入；文档见 `docs/production-hardening.md`、`QUICKSTART.md`。
