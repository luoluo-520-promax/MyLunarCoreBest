# -*- coding: utf-8 -*-
"""
将「战斗卡顿 / 场景人数 / 建模穿模」本轮新增能力同步进桌面原 bak 总结报告，
覆盖保存原文件（不另存新文档）。

目标文件：~/Desktop/MyLunarCore项目各方面详细总结报告.bak.docx
"""

from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.shared import Pt

DOC_PATH = Path.home() / "Desktop" / "MyLunarCore项目各方面详细总结报告.bak.docx"


def set_run_font(run, size=11, bold=False):
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    run.font.size = Pt(size)
    run.bold = bold


def replace_paragraph_text(paragraph, new_text, bold=False):
    style = paragraph.style
    for r in list(paragraph.runs):
        r.text = ""
    if paragraph.runs:
        paragraph.runs[0].text = new_text
        set_run_font(paragraph.runs[0], bold=bold)
    else:
        run = paragraph.add_run(new_text)
        set_run_font(run, bold=bold)
    paragraph.style = style


def insert_paragraph_after(paragraph, text, style_name="List Bullet"):
    new_para = paragraph._parent.add_paragraph(text, style=style_name)
    paragraph._p.addnext(new_para._p)
    for r in new_para.runs:
        set_run_font(r)
    return new_para


def find_para(doc, exact=None, startswith=None, contains=None):
    for p in doc.paragraphs:
        t = p.text.strip()
        if exact is not None and t == exact:
            return p
        if startswith is not None and t.startswith(startswith):
            return p
        if contains is not None and contains in t:
            return p
    return None


def set_cell_text(cell, text):
    if not cell.paragraphs:
        cell.text = text
        return
    p = cell.paragraphs[0]
    for r in list(p.runs):
        r.text = ""
    if p.runs:
        p.runs[0].text = text
        set_run_font(p.runs[0])
    else:
        run = p.add_run(text)
        set_run_font(run)
    for extra in cell.paragraphs[1:]:
        extra.clear()


def add_table_row(table, values):
    row = table.add_row()
    for i, value in enumerate(values):
        if i < len(row.cells):
            set_cell_text(row.cells[i], value)
    return row


def table_has(table, needle):
    for row in table.rows:
        for cell in row.cells:
            if needle in cell.text:
                return True
    return False


def find_table_by_header(doc, *needles):
    for table in doc.tables:
        if not table.rows:
            continue
        header = " ".join(c.text.strip() for c in table.rows[0].cells)
        if all(n in header for n in needles):
            return table
    return None


def main():
    if not DOC_PATH.exists():
        raise SystemExit(f"文档不存在: {DOC_PATH}")

    doc = Document(str(DOC_PATH))
    n = 0

    # ---------- 报告头 ----------
    p = find_para(doc, startswith="报告生成日期")
    if p is not None:
        replace_paragraph_text(
            p,
            "报告生成日期：2026年09月02日（在手感/拥堵防雪崩 CmdId 1105–1106 基础上，已同步"
            "「战斗卡顿 / 场景人数过多 / 建模穿模」三项性能深化："
            "BattleInstancePool 活跃战局配额与优先级 Tick、CombatAttributeSheet 增量属性、"
            "BattleAssistDecisionCache + BattleTurnPredictor、BattleAudit/Snapshot 异步落盘、"
            "KcpBandwidthProbe 结合丢包与带宽探测；"
            "动态 AOI densify、SceneStateDiffer 增量同步、EntitySleep 远距休眠、LOD 广播与"
            "PlayerBandwidthLimiter 背压、迁移热冷拆分；"
            "SceneCollisionProxy 胶囊体+阻挡多边形、MoveSpeedGuard 碰撞纠正与平滑插值、"
            "进场景 SceneCollisionMeshScNotify（CmdId 1107）预同步客户端碰撞）",
        )
        n += 1

    p = find_para(doc, startswith="报告依据：当前仓库 README")
    if p is not None and "PerformanceOptimizationFlowsTest" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；本轮再将战斗配额/增量属性/异步审计、动态 AOI/LOD/背压、服务端碰撞预同步写入"
            " 7.3/7.4/7.18/7.27.1、第八章与成熟度清单，回归 PerformanceOptimizationFlowsTest"
            "（13 条）及 Battle/Scene/KCP 相关批测。",
        )
        n += 1

    # ---------- GameServer / 时钟 ----------
    p = find_para(doc, startswith="主工程启动类是 MyLunarCoreApplication")
    if p is not None and "BattleInstancePool" not in p.text:
        replace_paragraph_text(
            p,
            "主工程启动类是 MyLunarCoreApplication。游戏侧由 GameServer 驱动全局/场景/战斗时钟；"
            "生产默认开启 isolatedTickPools：Scene-IO 与 Battle-CPU 使用独立 ScheduledExecutor，"
            "BattleTick 积压超过约 50ms 时下发 ThrottleScNotify（1106）将场景 NPC 刷新由约 5Hz 降至 2Hz。"
            "高并发战局由 BattleInstancePool 限制同时活跃数（默认 maxActiveBattles≈500），"
            "按 PLAYER_ACTIVE > ONLINE_AUTO > OFFLINE_HOSTED 分配 Tick 时间片；"
            "超额可排队或拒绝开战（retcode=8）。"
            "网络侧由 GameNettyServer（TCP）与 GameKcpServer（KCP）监听同一业务端口（默认 9000）；"
            "管理侧由嵌入式 Web 容器提供 8080 端口的后台接口。",
        )
        n += 1

    # ---------- 7.3 场景 ----------
    p = find_para(doc, startswith="玩家进入某个 Plane/Floor 对应的 Zone 实例后")
    if p is not None and "SceneStateDiffer" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "。"
            "人数密集优化：AoiGrid 支持按格子密度 densify（缩小 cellSize，配置 aoiCellSize/"
            "aoiDensePlayersPerCell/aoiMinCellSize）；SceneStateDiffer 仅同步变更字段；"
            "LodBroadcastAdvisor 对远距实体省略朝向/动画（PROXY/SIMPLIFIED）；"
            "PlayerBandwidthLimiter 超限时降低 AOI 尺度与同步频率；"
            "EntitySleepService 对远离所有玩家的怪/NPC 降为约 1Hz 心跳或休眠；"
            "跨节点迁移优先写热数据（位置/状态），场景完整快照异步补全。"
            "穿模防护：SceneCollisionProxy 加载阻挡多边形 + 角色胶囊体，MoveSpeedGuard 非法位移"
            "回退最近合法点并可平滑纠正；进场景推送 SceneCollisionMeshScNotify（1107）供客户端预碰撞。",
        )
        n += 1

    if find_para(doc, startswith="动态 AOI：ZoneManager.densifyAoiIfNeeded") is None:
        anchor = find_para(doc, startswith="玩家进入某个 Plane/Floor 对应的 Zone 实例后")
        if anchor is not None:
            b1 = insert_paragraph_after(
                anchor,
                "动态 AOI：ZoneManager.densifyAoiIfNeeded + AoiGrid.adjustCellSize；"
                "增量同步 SceneStateDiffer；LOD LodBroadcastAdvisor；背压 PlayerBandwidthLimiter。",
                "List Bullet",
            )
            insert_paragraph_after(
                b1,
                "实体休眠 EntitySleepService；碰撞 SceneCollisionProxy + SceneCollisionMeshPushService"
                "（CmdId 1107）；MoveSpeedGuard.CORRECTED_COLLISION + SmoothPositionCorrection。",
                "List Bullet",
            )
            n += 1

    # ---------- 7.4 战斗 ----------
    p = find_para(doc, startswith="场景中触发遭遇后进入战斗实例")
    if p is not None and "CombatAttributeSheet" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "。"
            "卡顿深化：BattleManager.put 受 BattleInstancePool 配额约束；"
            "BattleAutoService.tick 经 pool.dispatchTick 按优先级推进，掉线托管降频；"
            "EntityState/CombatAttributeSheet + BuffModifierCatalog 仅在 Buff 叠层变化时增量重算攻防；"
            "HeuristicBattleAssistPolicy 经 BattleAssistDecisionCache 复用同战况决策；"
            "空闲时 BattleTurnPredictor 预计算下一回合候选行动；"
            "BattleAuditService / BattleSnapshotService.saveAsync 热路径入队、后台批量刷盘"
            "（worker 未启动时同步兜底，保证单测与早期启动一致性）。",
        )
        n += 1

    if find_para(doc, startswith="战局配额：BattleInstancePool") is None:
        anchor = find_para(doc, startswith="预输入缓冲：PlayerInputBufferService")
        if anchor is None:
            anchor = find_para(doc, startswith="Hit-stop：HitStopComposer")
        if anchor is not None:
            insert_paragraph_after(
                anchor,
                "战局配额：BattleInstancePool（maxActiveBattles / 优先级 Tick / 排队或拒绝）；"
                "增量属性 CombatAttributeSheet + BuffModifierCatalog；"
                "AI 缓存 BattleAssistDecisionCache；预计算 BattleTurnPredictor；"
                "异步审计/快照 BattleAuditService、BattleSnapshotService.saveAsync。",
                "List Bullet",
            )
            n += 1

    # ---------- 移动反作弊 ----------
    p = find_para(doc, startswith="移动侧检查超速与过频上报")
    if p is not None and "SceneCollisionProxy" not in p.text:
        replace_paragraph_text(
            p,
            "移动侧检查超速与过频上报；并结合 SceneCollisionProxy 做阻挡相交测试："
            "非法坐标回退最近合法点（MoveCheckResult.CORRECTED_COLLISION），"
            "可选 SmoothPositionCorrection 平滑插值，连续穿模累计告警。"
            "战斗侧 BattleAuditService 异步入队留下审计线索；"
            "BattleDeterministicValidator 读取增量属性表攻防再跑 BattleDamageFormula。"
            "这属于基础防护，不是完整的反外挂产品，但能挡住一类明显作弊与穿墙瞬移。",
        )
        n += 1

    # ---------- 7.18 快照/安全 ----------
    p = find_para(doc, startswith="回合战不能「客户端报多少伤害服务器就信多少」")
    if p is not None and "saveAsync" not in p.text:
        replace_paragraph_text(
            p,
            "回合战不能「客户端报多少伤害服务器就信多少」。"
            "BattleDeterministicValidator 与 BattleDamageFormula 用服务端公式做确定性校验，"
            "攻防优先取 EntityState.resolveAttributes()（CombatAttributeSheet 增量结果），"
            "并校验顿帧结束时间戳防缩短 Hit-stop；BattleAuditService 异步批量写审计日志。"
            "BattleSnapshotService 除最终态外保留最近约 10 条 BattleDeltaRecord；"
            "热路径 saveAsync 入队后台落盘，断线重连 / GetBattleInfo 时推送 "
            "BattleReplayDeltaScNotify（1105），客户端按时间戳强制回放关键动作。",
        )
        n += 1

    # ---------- 新增 7.28 ----------
    if find_para(doc, contains="7.27.1 战斗卡顿/场景人数/穿模") is None and find_para(
        doc, contains="7.27.1 战斗卡顿 / 场景人数过多 / 建模穿模"
    ) is None:
        anchor = find_para(doc, startswith="实现入口：HitStopComposer")
        if anchor is None:
            anchor = find_para(doc, contains="7.27 战斗手感与拥堵防雪崩")
        if anchor is not None:
            h = insert_paragraph_after(
                anchor,
                "7.27.1 战斗卡顿 / 场景人数过多 / 建模穿模（CmdId 1107）",
                "Heading 2",
            )
            p1 = insert_paragraph_after(
                h,
                "在 7.27 手感与拥堵防雪崩之上，本轮针对高并发卡顿、主城/公会战广播风暴与客户端穿模"
                "三类短板落地服务端能力。"
                "（1）战斗：实例池配额与优先级时间片；Buff 增量属性；Auto AI 决策缓存与回合预计算；"
                "审计/快照异步队列；KCP 带宽探测结合丢包调参并限制弱网上行。"
                "（2）场景：网格密度自适应 AOI、状态差分、远距 LOD、连接级带宽背压、远距实体休眠、"
                "迁移热数据优先。"
                "（3）碰撞：服务端胶囊体+阻挡多边形权威校验，进场景下发 1107 简化网格供客户端预碰撞，"
                "纠正采用平滑插值并记穿模告警。",
                "Normal",
            )
            insert_paragraph_after(
                p1,
                "实现入口：BattleInstancePool、CombatAttributeSheet、BuffModifierCatalog、"
                "BattleAssistDecisionCache、BattleTurnPredictor、BattleAuditService、"
                "BattleSnapshotService、KcpBandwidthProbe、AoiGrid/ZoneManager.densifyAoiIfNeeded、"
                "SceneStateDiffer、LodBroadcastAdvisor、PlayerBandwidthLimiter、EntitySleepService、"
                "SceneCollisionProxy、SceneCollisionMeshPushService、MoveSpeedGuard、"
                "SmoothPositionCorrection、PlayerMigrationService（热冷拆分）；"
                "协议 SceneCollisionMeshScNotify；CmdIds.SCENE_COLLISION_MESH_SC_NOTIFY=1107；"
                "配置 lunarcore.game-loop.max-active-battles 等、lunarcore.zone.aoi-* / "
                "entity-sleep-* / lod-* / player-bandwidth-limit-kbps、"
                "lunarcore.anti-cheat.collision-*；"
                "测试 PerformanceOptimizationFlowsTest + 相关单测/回归。",
                "List Bullet",
            )
            n += 1

    # ---------- 第八章 KCP ----------
    p = find_para(doc, startswith="KCP 拥塞与重传：默认 AdaptiveKcpRetransmitAlgo")
    if p is not None and "KcpBandwidthProbe" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；并列 KcpBandwidthProbe：采样收发字节估计带宽，结合 RTT/丢包给出 interval、"
            "fastAck、noDelay、limitUplink 与位置同步 Hz 建议；"
            "KcpRttMonitor.recommendedIntervalMs / shouldThrottle 已接入探测结果；"
            "GameServerKcpListener 收包记 ingress。",
        )
        n += 1

    # ---------- Zone 人数 ----------
    p = find_para(doc, startswith="Zone 默认人数上限约 300")
    if p is not None and "densify" not in p.text and "LOD" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；密集区可 densify AOI、差分同步与 LOD/背压，远距实体休眠，降低广播与 AI CPU。",
        )
        n += 1

    # ---------- 成熟度已完成 ----------
    p = find_para(doc, startswith="移动反作弊、抽卡扣费流水")
    if p is not None and "BattleInstancePool" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；战局配额池/增量属性/AI 缓存/异步审计快照；动态 AOI+差分+LOD+背压+实体休眠；"
            "服务端碰撞代理与 1107 网格预同步。",
        )
        n += 1

    p = find_para(doc, startswith="Admin 热更/导入/审计")
    if p is not None and "1107" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；性能深化：BattleInstancePool、CombatAttributeSheet、KcpBandwidthProbe、"
            "SceneCollisionMeshScNotify（1107）。",
        )
        n += 1

    # ---------- 中期路线 ----------
    p = find_para(doc, startswith="箱庭回合制战斗虽是 CPU 密集")
    if p is not None and "maxActiveBattles" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "。"
            "单体 JVM 内已叠加活跃战局配额与优先级降频、属性增量与 AI 缓存，"
            "以及场景侧 AOI/LOD/休眠，进一步推迟「拆战斗服务」的必要性。",
        )
        n += 1

    # ---------- 配置表 ----------
    table = find_table_by_header(doc, "配置项") or find_table_by_header(doc, "配置键")
    if table is not None:
        rows = [
            (
                "lunarcore.game-loop.max-active-battles",
                "500",
                "同时活跃战局上限（BattleInstancePool）",
            ),
            (
                "lunarcore.game-loop.battle-tick-budget-ms",
                "40",
                "单帧 Battle Tick CPU 预算（毫秒）",
            ),
            (
                "lunarcore.zone.aoi-cell-size",
                "20",
                "AOI 基准格子边长；密集时可动态缩小",
            ),
            (
                "lunarcore.anti-cheat.collision-check-enabled",
                "true",
                "服务端碰撞体/阻挡网格校验",
            ),
            (
                "lunarcore.zone.player-bandwidth-limit-kbps",
                "256",
                "单玩家下行带宽软上限（KB/s）",
            ),
        ]
        for key, default, desc in rows:
            short = key.split(".")[-1]
            if not table_has(table, short) and not table_has(table, key):
                add_table_row(table, [key, default, desc])
                n += 1

    # ---------- 玩家一天串场 ----------
    p = find_para(doc, startswith="为了让非技术同学建立整体画面")
    if p is not None and "碰撞网格" not in p.text and "1107" not in p.text:
        text = p.text.rstrip("。")
        text += (
            "；进场景会收到服务端阻挡网格（1107）做本地预碰撞，"
            "主城人多时 AOI/同步自动降档，多队刷本受战局配额保护避免拖垮出手"
        )
        replace_paragraph_text(p, text + "。")
        n += 1

    # ---------- 结语 ----------
    p = find_para(doc, startswith="MyLunarCore 已经不是「只有空壳的演示仓库」")
    if p is not None and "BattleInstancePool" not in p.text:
        text = p.text.rstrip("。")
        text += (
            "。性能侧已具备活跃战局配额与优先级 Tick、增量属性与 AI 决策缓存、"
            "审计/快照异步落盘、KCP 带宽探测；场景侧动态 AOI/差分/LOD/背压/休眠；"
            "移动侧服务端碰撞权威与 1107 预同步，减少穿模与广播风暴"
        )
        replace_paragraph_text(p, text + "。")
        n += 1

    p = find_para(doc, startswith="本报告基于仓库当前代码与文档快照整理")
    if p is not None:
        replace_paragraph_text(
            p,
            "本报告基于仓库当前代码与文档快照整理，2026年09月02日 已在本文档内同步"
            "「战斗卡顿 / 场景人数过多 / 建模穿模」性能深化（CmdId 1107；"
            "此前手感/拥堵为 1105–1106、AI 增强 1098–1104、体验优化三期 1078–1097、"
            "二期 1070–1076、沉浸 1046–1069、闭环 1020–1045、PROTOCOL_WIRE_VERSION=3），"
            "可用于汇报、培训与决策讨论。"
            "若后续架构或配置有重大变更，建议修订对应章节后刷新目录域，以便目录页码与正文一致。",
        )
        n += 1

    # ---------- 目录提示（若有） ----------
    p = find_para(doc, startswith="以下为便于翻阅的章节索引")
    if p is None:
        p = find_para(doc, contains="打开后请先更新自动目录")
    # 若正文已有 7.28，提醒目录需刷新——在目录说明段追加一句
    p = find_para(doc, startswith="若你用 Word 自动目录")
    if p is not None and "7.28" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；新增「7.27.1 战斗卡顿/场景人数/穿模」后请再次「更新整个目录」。",
        )
        n += 1

    doc.save(str(DOC_PATH))
    print(f"已覆盖更新原文档: {DOC_PATH}")
    print(f"本轮变更点数: {n}")


if __name__ == "__main__":
    main()
