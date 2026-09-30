# 协议文档生成

Protobuf 源文件：`src/main/proto/*.proto`。

## 推荐：protoc-gen-doc

```bash
# 需本机安装 protoc 与 protoc-gen-doc
protoc -I src/main/proto \
  --doc_out=docs/protocol \
  --doc_opt=html,index.html \
  src/main/proto/*.proto
```

或 Markdown：

```bash
protoc -I src/main/proto \
  --doc_out=docs/protocol \
  --doc_opt=markdown,README.md \
  src/main/proto/*.proto
```

## 仓库基线

- `buf.yaml`：lint / breaking 检查。
- `CmdIds.java`：号段权威；`CmdIdUniquenessTest` 防重叠。
- 兼容：`ProtocolCompatService` + `PROTOCOL_WIRE_VERSION`；旧客户端应收到明确拒绝而非崩溃。

## 模块号段（摘要）

| 系统 | CmdId 约 |
|------|----------|
| 好友/邮件/聊天 | 100–118 |
| 组队 | 120–129 |
| 经济/IAP | 140–150 |
| 角色/皮肤 | 160–173 |
| 成就 | 950–954 |
| 新手引导 | 960–964 |
| 公会 | 970–989 |

按系统阅读对应 `*_system.proto` 与 `*NettyService` 调用顺序（登录 → 大厅 → 场景/战斗）。
