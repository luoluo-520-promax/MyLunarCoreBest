# 表现层协议清单（状态机：开始 → 进行中 → 结束）

将「纯数据通知」升级为可驱动客户端表演的状态机协议。配置侧统一携带 `client_ui_params`。

## 字段约定：`client_ui_params`

```json
{
  "client_ui_params": {
    "fxId": "fx_gacha_ten",
    "cameraPreset": "up_banner_closeup",
    "sfxId": "sfx_gacha_roll",
    "timelineId": "timeline_gacha_v1",
    "textPlaceholders": { "bannerName": "角色跃迁" }
  }
}
```

适用配置：`data/Banners.json`、`DialogueTrees.json`、`CutsceneConfigs.json`、`HomeFacilityConfigs.json`、活动模板等。

## 模块对照

| 模块 | 状态机协议 | CmdId | 说明 |
|------|------------|-------|------|
| 抽卡 | GachaStart → DoGacha → GachaResultAck | 509/510 → 502/503 → 511/512 | 动画前握手、发奖、动画结束 ACK |
| 角色突破 | AscendStart → AscendResult → AscendAck | 待客户端联调号段 | 光效/镜头 |
| 剧情对话 | DialogueStart → DialogueChoice → DialogueEnd | 860–868 | 选项与分支表现 |
| 过场 | CutsceneStart → CutsceneSkip/Complete | 860–868 | 跳过/完成 |
| 家园建造 | HomeBuildStart → HomeBuildDone | 850–857 | 放置动画 |
| 公会战 | GuildWarMatch → Score → Settle | 984–989 | 结算表演 |

## 抽卡三次握手

1. **GachaStartCsReq**：客户端准备播动画，服务端返回 `presentation_session_id` + `client_ui`
2. **DoGachaCsReq**：扣费发奖，响应带同一 session 与结果列表
3. **GachaResultAckCsReq**：动画结束（或跳过），关闭 session，允许下一抽

## 服务端类

- `GachaPresentationService` — 会话状态机
- `ClientUiParams` — 配置/协议共用 DTO
- `GachaRebateService` — 抽卡返利积分（星尘）
