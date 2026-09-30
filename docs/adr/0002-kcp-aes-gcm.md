# ADR-0002：游戏口选用 KCP + 应用层 AES-GCM（而非先上 DTLS）

- 状态：Accepted
- 日期：2026-08

## 背景

UDP/KCP 无 TLS；需要机密性与完整性，同时控制延迟与客户端改造成本。

## 决策

采用登录后会话密钥 + AES-GCM + HMAC；TCP+TLS 作为弱网/审核备选。DTLS/WireGuard 作为中期网关演进，不阻塞发版。

## 后果

握手前短暂明文；客户端需适配会话密钥下发。详见 `docs/kcp-crypto.md`。
