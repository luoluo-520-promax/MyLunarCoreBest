# 生产网络隔离与 Admin Nginx

## 端口角色

| 端口 | 用途 | 暴露建议 |
|------|------|----------|
| 9000 | 游戏 Netty/KCP | 公网（安全组限连接速率） |
| 8080 | Admin HTTP | **仅内网 / VPN / 跳板**；前挂 Nginx + IP 白名单 |
| 8081 | Actuator | 仅监控网段 |
| 3306 / 6379 | MySQL / Redis | 禁止公网；仅应用子网 |

## Nginx 反向代理 + IP 白名单（推荐）

应用侧已有 `lunarcore.admin.ip-whitelist` / `AdminIpWhitelistFilter`；生产仍应在边缘再拦一层。

```nginx
# /etc/nginx/conf.d/mylunarcore-admin.conf
upstream mylunar_admin {
    server 127.0.0.1:8080;
}

server {
    listen 443 ssl http2;
    server_name admin.internal.example.com;

    ssl_certificate     /etc/ssl/certs/admin.crt;
    ssl_certificate_key /etc/ssl/private/admin.key;

    # 办公网 / VPN
    allow 10.0.0.0/8;
    allow 192.168.0.0/16;
    deny  all;

    location /api/admin/ {
        proxy_pass http://mylunar_admin;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header User-Agent        $http_user_agent;
    }

    location /admin/ {
        proxy_pass http://mylunar_admin;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    }
}
```

应用配置：

```properties
lunarcore.admin.ip-whitelist=${ADMIN_IP_WHITELIST:10.0.0.0/8,192.168.0.0/16}
```

> 注意：仅当 Nginx 为可信代理时才信任 `X-Forwarded-For`；禁止将 8080 直接暴露公网。

## 审计字段

`admin_audit_log` 记录：`operator, action, resource, diff_json, success, client_ip, user_agent, before_json, after_json, created_at`。

## 建议

1. 云安全组：8080 仅办公网段；9000 可对公网但配合 Bucket4j 与云 DDoS。
2. `deploy/docker-compose.multi-node.yml`：中间件放入内部 network。
3. Admin CSRF + RBAC + `INTERNAL_API_TOKEN` 不可替代网络隔离。
4. 密钥：环境变量 / K8s Secret / Vault，禁止配置中心明文。
