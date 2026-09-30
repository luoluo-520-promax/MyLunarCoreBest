# 协议迁移指南（角色号段与能力位）

## 背景

`PROTOCOL_WIRE_VERSION = 4`：在 wire v3 基础上补充跨节点迁移进度、战斗预测修正、活动流程 DSL、资源差量补丁、协议能力握手等（CmdId **1109–1125**）。

wire v3 回顾：多端输入协商、战斗 Auto/倍速、匹配 Ready Check、材料反查与家园拜访日志等体验能力（CmdId **1020–1045**）。

wire v2 回顾：角色相关命令从历史重叠号段 **120–129** 迁至 **160–173**；**120–129** 现专属 Party。

旧客户端若仍按 120–129 发角色包，会与组队冲突，必须按能力位与 wire 版本协商。`ProtocolCompatService.isCompatible` 仍要求 **wire ≥ 2**；wire &lt; 4 时服务端屏蔽 1109+ 新命令，登录后可通过 `PROTOCOL_CAPABILITY`（1120/1121）协商 `enabled_cmd_ids`。

## 登录握手

1. 客户端在 `PlayerLoginCsReq.supported_features` 声明能力位（见 `ClientFeatureFlags`）。
2. 客户端在版本字段声明可接受的 `wire_version`（≥2 角色新号段；≥3 输入协商；≥4 迁移/战斗修正/活动流程/资源差量）。
3. 服务端返回 `enabled_features = client ∩ SERVER_ALL`，业务入口按位开关。
4. wire v3：上报 `input_methods`（bit0=键鼠 bit1=触屏 bit2=手柄）与 `device_id`；响应回填 `input_methods` 与 `recommended_layout_id`。
5. wire v4：可选上报 `PROTOCOL_CAPABILITY_CS_REQ`（supported_cmd_versions 范围），响应 `enabled_cmd_ids`；不支持命令回 `UNSUPPORTED_CMD_SC_NOTIFY`（retcode=120）并附带 `min_required_wire_version`。
6. **禁止**客户端硬编码“某功能一定走某号段”；以 `enabled_features` / `enabled_cmd_ids` 为准动态注册 Handler。

### 关键能力位

| 位 | 常量 | 含义 |
|----|------|------|
| 1<<0 | GUILD | 公会基础 |
| 1<<1 | GUILD_WAR | 公会战 |
| 1<<2 | HOME | 家园 |
| 1<<3 | DIALOGUE_TREE | 对话/过场 |
| 1<<9 | CHARACTER_V2 | 角色号段 160–173（wire v2） |
| 1<<11 | INPUT_KEYBOARD_MOUSE | 键鼠（wire v3） |
| 1<<12 | INPUT_TOUCH | 触屏 |
| 1<<13 | INPUT_GAMEPAD | 手柄 |
| 1<<14 | LAYOUT_HINT | 推荐布局 / 分级切图 |
| 1<<15 | BATTLE_AUTO | 服务端代打 |

未声明 `supported_features`（0）时，服务端按旧客户端默认集处理（含基础公会/家园/对话，**不含**公会战与完整新功能）。

## 客户端适配步骤

1. 将角色 Handler 从 120–129 迁到 160–173。
2. 登录带上 `CHARACTER_V2 | …`；若支持多端输入，额外声明 INPUT_* / LAYOUT_HINT / BATTLE_AUTO，并填写 `input_methods`。
3. 若 `enabled_features` 无 `CHARACTER_V2`，降级提示升级，勿回退旧号段。
4. Party 仅使用 120–129。
5. 体验补齐号段 1020–1045：Auto/倍速、储备体力、预加载推送、家园拜访日志、好友在线、成就推送、材料反查、对话存档/过场回放、匹配 Ready Check。

## 服务端兼容策略

- `ProtocolCompatService` / `ProtocolCompatibilityHandler`：对明显旧包记录指标并拒绝或引导重登。
- 不兼容的 Protobuf 变更必须升级 `PROTOCOL_WIRE_VERSION`，并由 CI `buf breaking` + `WireVersionGuardTest` 强制。

## 版本升级流程

1. 修改 `.proto` 后跑 `buf breaking`。
2. 若 breaking：同步递增 `CmdIds.PROTOCOL_WIRE_VERSION` 与客户端协商文档。
