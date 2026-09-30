# MyLunarCore

基于 **Spring Boot 4 + Netty/KCP + Protobuf + MySQL** 的崩铁式游戏私服工程。

## 架构定位（请先读）

| 部分 | 说明 |
|------|------|
| **主工程（本目录）** | 模块化单体：游戏核心、Admin、钱包/抽卡/战斗/场景、热更、公会基础。**生产仅运行此单体。** |
| **`microservices/`** | **实验性** Spring Cloud Alibaba 迁移脚手架。**不可用于生产账号/玩家域。** |

详情与拆分建议见 [microservices/README.md](microservices/README.md)。

## 快速启动（一键依赖 + 主工程）

新人完整步骤与 FAQ 见 **[QUICKSTART.md](QUICKSTART.md)**；架构决策见 [docs/adr/](docs/adr/)。

```bash
# 1) MySQL + Redis + 初始化 SQL
docker compose -f docker-compose.dev.yml up -d

# 2) 按需改 src/main/resources/application.properties 后启动
mvn -DskipTests spring-boot:run
```

- Admin HTTP：默认 `8080`
- 游戏口（Netty/KCP）：默认 `lunarcore.netty-port=9000`
- Profile：`dev` / `test` / `prod`（`prod` 会 fail-fast 拒绝弱密钥、要求 `INTERNAL_API_TOKEN`/`DB_PASSWORD` 环境变量、强制 KCP 加密）

多节点模拟中间件：`deploy/docker-compose.multi-node.yml`，验证清单：`docs/multi-node-failover.md`。

### 关键配置

| 配置 | 含义 |
|------|------|
| `INTERNAL_API_TOKEN` / `lunarcore.internal-api-token` | `/internal/**` 共享密钥；prod 禁止弱值且须环境变量 |
| `mylunarcore.iap.mock-verify` | 开发可 `true`；**prod 必须 `false`**；沙盒步骤见 `docs/iap-sandbox.md` |
| `lunarcore.center.mode` | `local` 单机；`remote` 多节点（**必须** `lunarcore.redis.enabled=true`） |
| `lunarcore.redis.enabled` | 共享票据/在线表/排行榜/世界聊天 |
| `lunarcore.kcp-crypto.enabled` | **prod 默认 true**；UDP 明文禁止 |
| `lunarcore.world.cell-handoff-enabled` / `realtime-combat-enabled` | 无缝 Cell / 实时战斗权威，**实验默认关** |

### 文档索引

| 文档 | 内容 |
|------|------|
| `docs/production-hardening.md` | 生产加固基线 |
| `docs/load-test-baseline.md` | 压测结论与回填清单 |
| `docs/planner-config-guide.md` | 策划配置手册 |
| `docs/config-versioning.md` | 配置版本、回滚与灰度 |
| `docs/protocol-docs.md` | 协议文档生成 |
| `docs/iap-sandbox.md` | IAP 沙盒 |
| `docs/multi-node-failover.md` | 跨节点故障转移 |
| `docs/feature-gap-fill.md` | 玩法/技术缺口补齐说明 |
| `docs/kcp-crypto.md` | KCP AES-GCM / TLS |
| `docs/backup-recovery.md` | 备份与恢复演练 |
| `docs/release-process.md` | 版本发布与回滚 |
| `docs/error-codes.md` | 业务错误码手册 |
| `docs/observability-logging.md` | Loki/链路追踪 |
| `docs/network-isolation.md` | Admin/游戏口网络隔离 |
| `docs/admin-api.md` | Admin HTTP 联调摘要 |

### 发布演练

```powershell
.\scripts\publish_drill.ps1
.\scripts\publish_drill.ps1 -Extended
```

压测校准：`scripts/load/k6_baseline.js`；结论模板见 `docs/load-test-baseline.md`。

## 微服务脚手架（实验）

```bash
docker compose -f microservices/docker-compose.yml up -d
cd microservices && mvn -U clean package
```

启动顺序：auth → player → ai-assist → api-gateway（可选另启 chat:18084、match:18085，不经网关）。

**警告：** 默认 JWT `change-me-...`、演示密码 `123456`/`admin123` 仅限本地。

## 能力归属（近期）

- 继续留在单体：钱包、战斗、场景、抽卡写路径、公会
- 可先拆/旁路：**匹配大厅、世界聊天**、Admin/配置热更、排行榜、AI 助手 sidecar
- 内容骨架：对话树/过场、家园基建、公会 Raid 接口（见主工程对应包）
- 缺口补齐：公会战闭环、家园产出/互访、剧情进度、竞技场 ELO、日周刷新、存档导出（见 `docs/feature-gap-fill.md`）
