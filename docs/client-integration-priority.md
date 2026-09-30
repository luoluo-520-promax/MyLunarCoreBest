# 客户端联调优先级清单

服务端已下发丰富表现元数据；客户端未消费则玩家感知等同于普通回合制。按 **P0 → P1 → P2** 联调。

## 分级说明

| 级别 | 含义 | 验收标准 |
|------|------|----------|
| P0 | 核心手感/视觉 | 不联调则「手感/切图」明显落后头部二游 |
| P1 | 沉浸提升 | 联调后显著提升沉浸，缺省仍可玩 |
| P2 | 锦上添花 | 差异化体验，可排期 |

参考视频目录（策划投放）：`docs/refs/client-fx/`（DISSOLVE / RIFT / UltPreFx / QTE / Ping）。

---

## P0 — 影响核心手感/视觉

| CmdId | 常量 | 预期 UI/特效行为 | 关键字段 | 参考 |
|-------|------|------------------|----------|------|
| 切图 Notify 内嵌 | `SceneLoadMaskInfo` | `transition_type=DISSOLVE/RIFT` 播对应过渡；`BLACK` 仅未预加载命中 | `transition_type`, `duration_ms`, `hit_rate_bp` | `refs/dissolve.mp4` |
| 1051–1052 | `BattleManualUlt` | 收到 `client_pre_fx_ms` 后立刻播大招预表现，勿等权威结算 | `client_pre_fx_ms`, `estimated_cast_time_ms` | `refs/ult_prefx.mp4` |
| 213 | `BATTLE_FX_SC_NOTIFY` | 震屏档位 + `camera_shake_intensity` + `camera_fov_impact`；Hit-stop 严格按帧 | `camera_shake`, `camera_fov_impact`, `hit_stop_frames` | `refs/hitstop.mp4` |
| 1130 | `CANCEL_WINDOW_SC_NOTIFY` | 窗口内高亮可取消技能槽；超时灰显 | `window_ms`, `from_tier`, `allow_tiers` | `refs/cancel.mp4` |
| 1138–1139 | `RECONNECT_*` | 保留 session，恢复场景/战斗，不重载整包资源 | `session_id`, `scene_snapshot`, `battle_id` | — |

## P1 — 提升沉浸

| CmdId | 常量 | 预期行为 | 关键字段 |
|-------|------|----------|----------|
| 1131–1133 | `BATTLE_QTE_*` | 破盾/击杀弹出 QTE 按钮；成功上报得行动点/伤害加成 | `qte_id`, `deadline_ms`, `bonus_type` |
| 1134–1136 | `SCENE_PING_*` | 地面/怪物 Ping，AOI 内队友可见标记 | `ping_type`, `pos`, `target_entity_id` |
| 1124 | `VOICE_SIGNALING_SC_NOTIFY` | 进 Raid 自动加入 RTC 房间 | `roomId`, `token`, `rtcAppId` |
| 1137 | `RAID_DAMAGE_STATS_SC_NOTIFY` | 侧边 DPS 排名看板，按秒刷新 | `rankings[]`, `dps` |
| — | `DeviceHaptics` / waveform | 按 `waveform_id` 播触觉波形 | `haptic_intensity`, `waveform_id` |

## P2 — 锦上添花

| CmdId | 常量 | 预期行为 |
|-------|------|----------|
| 1140 | `TOUCH_HEATMAP_SC_NOTIFY` | 触摸热区放大/震动反馈 |
| 1141 | `UI_SCALE_SC_NOTIFY` | 按分辨率应用云端 `ui_scale` |
| 1142 | `VERSION_THEME_SC_NOTIFY` | 主题切换预加载下一版本资源 |
| TEAM_CARD | 本地海报合成 | 服务端元数据 → 客户端本地生成分享图 |
| anchor_chains | AR 渲染 | 按锚点链渲染 AR 指引 |

---

## 联调自检

1. Mock：`PresentationMockClient` / Admin `GET /api/admin/mock/presentation-preview`
2. CI：`ClientProtocolIntegrationTest` 校验 `SceneLoadMaskInfo.transition_type` 等解析
3. 灰度：先 QA UID 尾号，再全量
