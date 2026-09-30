# KCP 加密、DTLS 与 TCP+TLS 备选

## 结论

| 通道 | 加密 | 说明 |
|------|------|------|
| UDP/KCP（默认游戏口） | **AES-GCM**（应用层） | `KcpSessionCryptoCodec`：`AES/GCM/NoPadding`，IV=12，Tag=128 |
| UDP 传输层 | **无原生 DTLS**（当前） | 见下方演进建议 |
| TCP 游戏口 | 可选 **TLS 1.2+** | `lunarcore.tls.game-tcp-enabled=true` |
| 协议完整性 | HMAC | 与 AES 正交 |

生产强制：`lunarcore.kcp-crypto.enabled=true`；若 `allow-plaintext=true` 则必须开启 TCP+TLS。

## AES-GCM 密钥

1. 登录成功后由 `SessionCryptoBinder` 生成会话密钥。
2. 经登录响应 `session_crypto_key`（Base64）下发。
3. **Nonce 防重放**：`KcpGcmNonceGuard`。
4. **密钥轮换**：会话级（登录换钥）；运维级主密钥放 Vault/KMS。

## DTLS / WireGuard 演进

| 方案 | 适用 | 客户端适配 |
|------|------|------------|
| **现状：KCP + AES-GCM** | 崩铁式箱庭、快速上线 | 登录后启用编解码；握手包明文 |
| **网关 DTLS 终结** | 合规要求传输层加密 | 客户端 OpenSSL/BoringSSL DTLS；网关解密后内网明文/KCP |
| **WireGuard 隧道** | 企业专线/测试岛 | 系统级隧道，游戏协议无感；运维成本高 |
| **TCP+TLS 备选** | 运营商 UDP 劣化 / 审核包 | 同一 Protobuf 帧走 TLS TCP；切换 `game-tcp-enabled` |

推荐路径：**生产保持 KCP+AES-GCM+HMAC**；同时保留 TCP+TLS 开关供弱网地区与审核渠道；DTLS 作为中期网关能力评估，不阻塞当前发版。

## 接入层 TLS 终止（Nginx / Envoy）

KCP/UDP 本身无 TLS。生产建议在公网边缘做传输层防护，再转发至内网 Game 端口：

| 模式 | 做法 | 说明 |
|------|------|------|
| **TCP+TLS 终结** | Nginx/Envoy 监听 443/9443，TLS 终止后反代到内网 `game-tcp:9000` | 审核包/弱网优先；与 `lunarcore.tls.game-tcp-enabled` 对齐 |
| **UDP 边缘限流** | 云 WAF / L4 对 9000/UDP 做源限速与地域 ACL | KCP 仍走应用层 AES-GCM；减少游戏口裸奔 |
| **双通道** | 公网只暴露 Envoy；Game 节点仅 VPC 内网 | 统一证书轮换与安全策略 |

示例（Nginx TCP TLS 反代，非 UDP）：

```nginx
stream {
  upstream lunar_game_tcp {
    server 10.0.1.10:9000;
  }
  server {
    listen 9443 ssl;
    ssl_certificate     /etc/certs/game.crt;
    ssl_certificate_key /etc/certs/game.key;
    proxy_pass lunar_game_tcp;
  }
}
```

原则：**减少游戏口公网暴露**；会话密钥仍由登录下发，KCP 路径继续 AES-GCM+Nonce 防重放。

### 客户端适配（TCP+TLS）

1. 优先尝试 KCP（9000/UDP）；失败或配置强制时连 `host:9000` TCP+TLS。
2. TLS SNI / 证书钉扎与 Admin HTTPS 证书体系分离。
3. 登录流程一致：拿到 `session_crypto_key` 后，KCP 路径启用 AES-GCM；TCP+TLS 路径可仅依赖 TLS（仍建议开 HMAC）。

## 相关代码

- `KcpSessionCryptoCodec` / `KcpGcmNonceGuard` / `SessionCryptoBinder`
- `GameNettyTlsSupport`
- `ProductionSecretsValidator`
