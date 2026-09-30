# 版本发布流程

## 版本号

- 主工程 Maven：`major.minor.patch`（当前 `0.0.1-SNAPSHOT`）
- 协议：`CmdIds.PROTOCOL_WIRE_VERSION`；不兼容变更必须升版本并出客户端兼容说明
- 配置包：`ConfigReleaseService` fingerprint / release_id

## 发布前

1. `mvn -DskipTests=false test`（含 `CmdIdUniquenessTest`）
2. CI：`buf lint` + `buf breaking`（见 `.github/workflows/ci.yml`）
3. `.\scripts\publish_drill.ps1`（可选 `-Extended`）
4. 压测关键路径：`scripts/load/k6_baseline.js`，回填 `docs/load-test-baseline.md`
5. Changelog：按 feat/fix/docs 归类，注明协议号段变更

## 发布中

1. 扩容/灰度节点：`lunarcore.config-gray.*` + `ConfigGrayReader`
2. 配置热更优先 dry-run：`POST /api/admin/ops/import/json-files` `dryRun=true`
3. 观察 Grafana 业务面板与 `business-alerts.yml`

## 回滚

1. 二次确认回滚：`POST /api/admin/ops/rollback?confirm=ROLLBACK&reason=...&version=...`
2. 或 `POST /api/admin/ops/import/rollback`（文件列表）
3. 记录原因到 `admin_audit_log`
4. 协议不兼容时：**停服回滚双方**，禁止只回服务端

## 停机

`GracefulShutdownCoordinator`：排空匹配 → 中止战斗 → 停 Netty/KCP → 同步落盘。发布窗口应覆盖进行中的抽卡/钱包事务回归用例。
