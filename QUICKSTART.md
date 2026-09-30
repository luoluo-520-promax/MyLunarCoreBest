# MyLunarCore 新人快速上手

最小化：仅 MySQL + 主工程（Redis 可选）。

## 1. 前置

- JDK 17+
- Maven 3.9+
- Docker（推荐）或本地 MySQL 8

## 2. 启动依赖

```bash
docker compose -f docker-compose.dev.yml up -d
```

默认暴露 MySQL（及可选 Redis）。账号密码见 `docker-compose.dev.yml`。

## 3. 启动主工程

```bash
mvn -DskipTests spring-boot:run
```

或 IDE 运行 `MyLunarCoreApplication`（profile 默认 `application.properties`，非 prod）。

健康检查：`http://127.0.0.1:8080/actuator/health`

游戏口：TCP/KCP `9000`。

## 4. 常用联调

| 项 | 说明 |
|----|------|
| Admin | `http://127.0.0.1:8080/admin/`（开发无 IP 白名单） |
| 内部 Token | 默认 `dev-internal-token`（**禁止用于生产**） |
| IAP | 开发 `mylunarcore.iap.mock-verify=true` |
| 协议 | `PROTOCOL_WIRE_VERSION=2`，客户端须声明 wire_version≥2 |
| 脚本 | `scripts/quickstart-main-flow.ps1` |

Postman/Insomnia 环境变量示例见 `docs/error-codes.md` 末尾。

## 5. 生产 profile 注意

```bash
# 必须注入强密钥，否则 ProductionSecretsValidator fail-fast
export INTERNAL_API_TOKEN='Prod-Internal-Token-9f3a!SecureKey01'
export DB_PASSWORD='S3cure-Db-Pass!Word-Length32xx'
export DB_URL='jdbc:mysql://...'
export DB_USER='...'
export ADMIN_IP_WHITELIST='10.0.0.0/8'
export SSL_KEY_STORE_PASSWORD='...'
export TLS_KEY_STORE_PASSWORD='...'
export TLS_KEY_PASSWORD='...'
mvn spring-boot:run -Dspring-boot.run.profiles=prod
```

## 6. FAQ

**Q: 登录 retcode=9？**  
A: 客户端协议版本 &lt; 2，升级客户端。

**Q: 多节点切服失败 retcode=3？**  
A: 开启 Redis，配置 `center.mode=remote` 与 advertise 地址，见 `docs/multi-node-failover.md`。

**Q: microservices/ 要不要跑？**  
A: **实验性**，生产勿用。AI 旁路可用 `docker compose -f microservices/docker-compose.ai-assist.yml up`。

**Q: 编译 protobuf 失败？**  
A: 先 `mvn -DskipTests compile`，确保 protoc 插件成功。

**Q: Admin 403？**  
A: 检查 CSRF/登录态，或 prod 下 IP 白名单。
