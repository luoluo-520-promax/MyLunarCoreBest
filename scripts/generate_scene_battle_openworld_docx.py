# -*- coding: utf-8 -*-
"""Generate scene/battle vs open-world 二游 assessment Word document to Desktop."""

from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.shared import Pt, Cm, RGBColor

DESKTOP = Path.home() / "Desktop"
OUTPUT = DESKTOP / "MyLunarCore场景战斗与大世界二游评估及优化方案.docx"


def set_doc_fonts(doc: Document):
    style = doc.styles["Normal"]
    style.font.name = "微软雅黑"
    style.font.size = Pt(11)
    style._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_title(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    run.bold = True
    run.font.size = Pt(20)
    run.font.color.rgb = RGBColor(25, 75, 140)
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_subtitle(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    run.font.size = Pt(10.5)
    run.font.color.rgb = RGBColor(100, 100, 100)
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_heading(doc: Document, text: str, level: int = 1):
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
        if level == 1:
            run.font.color.rgb = RGBColor(25, 75, 140)


def add_body(doc: Document, text: str):
    p = doc.add_paragraph(text)
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_bullet(doc: Document, text: str, level: int = 0):
    p = doc.add_paragraph(text, style="List Bullet")
    p.paragraph_format.left_indent = Cm(0.5 * (level + 1))
    for run in p.runs:
        run.font.name = "微软雅黑"
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")


def add_table(doc: Document, headers, rows):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr_cells = table.rows[0].cells
    for i, h in enumerate(headers):
        hdr_cells[i].text = h
        for p in hdr_cells[i].paragraphs:
            for run in p.runs:
                run.bold = True
                run.font.name = "微软雅黑"
                run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    for r_idx, row in enumerate(rows):
        row_cells = table.rows[r_idx + 1].cells
        for c_idx, val in enumerate(row):
            row_cells[c_idx].text = val
            for p in row_cells[c_idx].paragraphs:
                for run in p.runs:
                    run.font.name = "微软雅黑"
                    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    doc.add_paragraph()


def build_document() -> Document:
    doc = Document()
    set_doc_fonts(doc)

    add_title(doc, "MyLunarCore 场景与战斗系统评估")
    add_subtitle(doc, "对照大世界二游设定的符合度分析与可落地优化方案")
    add_subtitle(doc, "项目路径：c:\\Users\\ASUS\\IdeaProjects\\test\\MyLunarCore")
    add_subtitle(doc, "生成日期：2026年7月31日")
    doc.add_paragraph()

    # 一、结论
    add_heading(doc, "一、总体结论", 1)
    add_body(
        doc,
        "当前项目更接近「崩铁式」分区探索 + 独立回合制战局的服务器雏形，"
        "而不是原神 / 鸣潮 / 绝区零式的无缝大世界实时战斗。",
    )
    add_body(
        doc,
        "已具备：plane/floor 分区场景、Zone + AOI 同屏见人、中心服路由骨架、"
        "回合波次战斗、挑战/匹配门禁、AI 辅助探索旁白。",
    )
    add_body(
        doc,
        "尚未具备大世界二游最关键的体验链：世界遭遇 → 进战 → 权威结算 → 回落世界；"
        "也未实现无缝实时战斗与共享世界实体权威。",
    )
    add_body(
        doc,
        "产品定位建议：短期按「崩铁式闭环」把场景-战斗-回落打通；"
        "若要做无缝实时大世界，应作为明确产品分叉新建实时战斗子系统，"
        "而不是在现有 BattleContext 上渐进打补丁。",
    )

    # 二、对标
    add_heading(doc, "二、大世界二游设定对照", 1)
    add_body(doc, "「大世界二游」常见两条产品路线，对服务器模型要求不同：")
    add_table(
        doc,
        ["维度", "无缝实时路线（原神/鸣潮/绝区零）", "分区回合路线（崩铁）", "MyLunarCore 现状"],
        [
            [
                "探索空间",
                "流式/无缝大地图，世界怪共享权威",
                "位面/地图分区加载，遇敌切战斗",
                "plane+floor 分区；每人一份 SceneContext 实体快照",
            ],
            [
                "战斗形态",
                "同场景实时技能/闪避/韧性",
                "切入独立回合战局，结算后退场",
                "独立 BattleContext，回合+波次，请求-响应结算",
            ],
            [
                "遭遇方式",
                "接触/仇恨触发，进出战无缝",
                "接触或交互后切战斗场景",
                "客户端传 battle_stage_id 开战；忽略 scene_entity_uid",
            ],
            [
                "多人",
                "同世界互动/联机战斗",
                "部分玩法可多人，主线常单人",
                "AOI 见人有；战斗单人 owner；挑战仅匹配门禁",
            ],
            [
                "状态机",
                "探索与战斗可同场景共存",
                "SCENE ↔ BATTLE 明确切换",
                "状态机允许切换，但开战未真正切入 BATTLE",
            ],
        ],
    )
    add_body(
        doc,
        "符合度判定：若目标是「崩铁式大世界二游」，底座方向正确，缺闭环；"
        "若目标是「原神式无缝大世界」，当前战斗与场景模型均不符合，需另建实时战斗循环。",
    )

    # 三、现状架构
    add_heading(doc, "三、现状架构梳理", 1)

    add_heading(doc, "3.1 场景系统", 2)
    add_bullet(doc, "SceneManager：玩家 uid → SceneContext（一人一场景快照）。")
    add_bullet(doc, "SceneContext：plane/floor/坐标 + monsters/npcs/props；onTick 中 Buff/AI 为空占位。")
    add_bullet(doc, "SceneNettyService：进场、迁移、移动、NPC、拾取、事件、治疗泉；parseGroupsIntoScene 全量加载 groups。")
    add_bullet(doc, "ZoneManager / ZoneContext / AoiGrid：同 plane+floor 共享 Zone，XZ 格子 AOI（cellSize=20）。")
    add_bullet(doc, "SceneSyncBroadcaster：移动后推送 SCENE_ENTITY_SYNC 给自己与 AOI 邻居。")
    add_bullet(doc, "SceneConfigRepository：读 DB scene_config(plane_id, floor_id, groups)。")
    add_bullet(doc, "关键路径：scene/SceneManager.java、SceneNettyService.java、SceneContext.java、center/SceneRegistry.java。")

    add_heading(doc, "3.2 中心服与迁移", 2)
    add_bullet(doc, "SceneRegistry：zoneId = plane*10000+floor；默认 nodeId=local。")
    add_bullet(doc, "CenterServer / LocalCenterServer / RemoteCenterServer：迁移计划抽象。")
    add_bullet(doc, "PlayerMigrationService：leave 旧 Zone，不自动 join；真跨进程会话搬迁未完成。")
    add_bullet(doc, "跨节点 migrate 非本机直接 retcode=3。")

    add_heading(doc, "3.3 战斗系统", 2)
    add_bullet(doc, "BattleManager：battleId → BattleContext 内存索引。")
    add_bullet(doc, "BattleContext：回合、波次、HP/Buff、技能行为；synchronized 串行化。")
    add_bullet(doc, "BattleNettyService：FightStart/Action/Result/Quit/GetInfo/AskBattleHint。")
    add_bullet(doc, "BattleSceneFactory + battle / battle_monster_wave 表装配战局。")
    add_bullet(doc, "BattleAssistPolicy：PVE 启发式提示/托管（actionType=10）。")
    add_bullet(doc, "挑战 ChallengeNettyService：生命周期与匹配门禁，非共享多人战局。")

    add_heading(doc, "3.4 场景与战斗的连接现状", 2)
    add_table(
        doc,
        ["环节", "当前行为", "问题"],
        [
            ["进世界", "GameFlow → handleEnterScene 加载 groups", "可用"],
            [
                "遇敌开战",
                "FightStart 只校验 battleStageId + lineupId，忽略 scene_entity_uid",
                "与场景怪无绑定；未切 BATTLE 状态",
            ],
            ["战斗中场景", "SceneContext 仍保留，移动未冻结", "可能边走边打"],
            [
                "胜负/退出",
                "清 BattleManager；teleportSceneId=0；坐标占位 0",
                "不回世界、不删场景怪、奖励多为演示空值",
            ],
            ["回主界面", "leaveCurrentPlay 一并清场景+战斗", "粗暴，非战后回落点"],
        ],
    )
    add_body(
        doc,
        "协议侧已预留钩子：battle_system.proto 含 scene_entity_uid、FightQuitScRsp.teleport_scene_id；"
        "会话状态机允许 SCENE↔BATTLE。实现侧尚未打通。",
    )

    # 四、差距
    add_heading(doc, "四、与典型大世界二游的主要差距", 1)

    add_heading(doc, "4.1 开放世界探索", 2)
    add_bullet(doc, "整 plane+floor 一次加载，非流式子区域无缝切换。")
    add_bullet(doc, "怪/宝箱在每人私有 SceneContext，无法做共享刷新与联机互动。")
    add_bullet(doc, "探索辅助仅有内容包路线建议与 POI 旁白；拾取/治疗泉多为演示空效果。")

    add_heading(doc, "4.2 遭遇与战斗", 2)
    add_bullet(doc, "无服务端接触/仇恨遭遇判定；开战完全依赖客户端传 stageId。")
    add_bullet(doc, "无战后世界怪死亡/刷新/掉落闭环。")
    add_bullet(doc, "无实时技能/闪避/韧性；部分技能 action_type 未实现。")
    add_bullet(doc, "结算偏信任客户端 end_status；权威胜负判定不足。")

    add_heading(doc, "4.3 多人与多节点", 2)
    add_bullet(doc, "AOI 同屏见人已有；世界怪与战斗不同步给他人。")
    add_bullet(doc, "挑战匹配有房间门禁，无共享 BattleContext 结算。")
    add_bullet(doc, "跨节点迁移仅门禁，无会话票据与目标节点恢复。")

    add_heading(doc, "4.4 性能与同步", 2)
    add_bullet(doc, "每次 Move 广播；缺采样合并与跨格阈值。")
    add_bullet(doc, "entityId 用 playerUid 取模，存在碰撞风险。")
    add_bullet(doc, "场景 tick 中怪 AI 为空；扩展时需防全服 tick 热点。")
    add_bullet(doc, "战局无超时回收策略，依赖下线/回主界面 cleanup。")

    add_heading(doc, "4.5 内容管线", 2)
    add_bullet(doc, "场景 groups JSON + 战斗波次表分离，缺 encounter 映射表。")
    add_bullet(doc, "奖励/对话/事件大量空列表演示路径。")
    add_bullet(doc, "场景/波次热更与版本化弱于现有活动/Assist 热更体系。")

    # 五、方案
    add_heading(doc, "五、优化方案（含落地步骤）", 1)

    add_heading(doc, "5.1 P0：打通「探索 → 战斗 → 回落」闭环（优先）", 2)
    add_body(doc, "目标：用最低成本完成崩铁式产品骨架，协议与状态机已预留。")
    add_bullet(doc, "方案 A — FightStart 绑定场景实体")
    add_bullet(doc, "读取并校验 req.scene_entity_uid，从 SceneContext.getMonster 取怪。", 1)
    add_bullet(doc, "通过 encounter 表或怪模板映射 battle_stage_id（客户端 stageId 仅作校验/兜底）。", 1)
    add_bullet(doc, "PlayerSessionStateMachine.tryTransition(SCENE→BATTLE)；handleMove 在 BATTLE 时拒绝或冻结。", 1)
    add_bullet(doc, "方案 B — 战后回世界")
    add_bullet(doc, "FightResult/FightQuit：写回 plane/floor/pos；删除或标记场景怪；teleportSceneId 非 0。", 1)
    add_bullet(doc, "状态机 BATTLE→SCENE；发放奖励写真实背包/经验。", 1)
    add_bullet(doc, "方案 C — 权威结算")
    add_bullet(doc, "服务端根据波次清剿/玩家全灭判定胜负，弱化纯客户端 end_status。", 1)
    add_bullet(doc, "挑战结算同样改为服务端权威，匹配成功后校验再发奖。", 1)

    add_heading(doc, "5.2 P1：场景与同步向大世界靠拢", 2)
    add_bullet(doc, "方案 D — 世界实体权威下沉到 Zone")
    add_bullet(doc, "怪/宝箱放入 ZoneContext（或 Zone 级 EntityStore）；SceneContext 改为兴趣视图。", 1)
    add_bullet(doc, "刷新、拾取、击杀对同 Zone 玩家可见可交互。", 1)
    add_bullet(doc, "方案 E — 移动与带宽")
    add_bullet(doc, "移动采样/合并：仅跨 AOI 格或位移超阈值广播；delta 压缩。", 1)
    add_bullet(doc, "独立 entityId 分配器，替换 uid 取模。", 1)
    add_bullet(doc, "方案 F — 遭遇规则引擎")
    add_bullet(doc, "填满 SceneContext.onTick：距离/仇恨/主动怪；或交互型「开战」按钮。", 1)
    add_bullet(doc, "遭遇成功后走 P0 开战流程，保证权威在服务端。", 1)

    add_heading(doc, "5.3 P2：战斗深化（二选一，勿混做）", 2)
    add_body(doc, "路线 1（推荐，与现状一致）—— 崩铁式回合制深化：")
    add_bullet(doc, "补全技能 action_type、速度/行动条、多角色阵容实体、弱点击破。")
    add_bullet(doc, "Assist 从启发式接到真实 skill 表与战局特征。")
    add_bullet(doc, "波次切换、自动战斗、托管与战斗回放埋点完善。")
    add_body(doc, "路线 2 —— 原神/鸣潮式无缝实时：")
    add_bullet(doc, "新建实时战斗循环：tick、碰撞、技能 CD、打断、韧性条。")
    add_bullet(doc, "与当前 FightAction 请求模型不兼容，视为新子系统。")
    add_bullet(doc, "世界怪与玩家同场景权威同步，进出战无独立 BattleContext 或仅作子状态。")

    add_heading(doc, "5.4 P3：多人与多节点", 2)
    add_bullet(doc, "方案 G — Center 真迁移")
    add_bullet(doc, "目标非本机：返回 node 地址 + 会话票据；源 leave + 持久化；目标 restore + joinZone。", 1)
    add_bullet(doc, "方案 H — 联机战斗")
    add_bullet(doc, "挑战匹配成局后共享 BattleContext 或独立 coop runtime，而不仅是房间门禁。", 1)
    add_bullet(doc, "方案 I — 微服务边界")
    add_bullet(
        doc,
        "遵循 microservices/README：Center 路由压测验证前，不要拆 battle/scene 微服务；AI assist 可旁路。",
        1,
    )

    add_heading(doc, "5.5 内容管线与配置", 2)
    add_bullet(doc, "新增 encounter 配置：scene_monster_id → battle_stage_id / drop / refresh。")
    add_bullet(doc, "场景/波次配置版本化 + 热更，对齐现有 HotReloadCoordinator。")
    add_bullet(doc, "补齐奖励表、对话表、事件表，去掉空列表演示路径。")
    add_bullet(doc, "道具拾取与治疗泉改为真实数值效果，并写审计日志。")

    # 六、实施路线
    add_heading(doc, "六、建议实施路线图", 1)
    add_table(
        doc,
        ["阶段", "周期建议", "交付物", "验收标准"],
        [
            [
                "Phase 1",
                "1–2 周",
                "场景实体开战 + 状态机 + 战后回落 + 权威胜负",
                "从场景怪开战→战斗→回原坐标；怪消失/刷新；奖励到账",
            ],
            [
                "Phase 2",
                "2–3 周",
                "Zone 实体权威 + 移动节流 + encounter 表 + tick 遭遇",
                "同 Zone 可见怪状态变化；带宽下降；主动怪可触发开战",
            ],
            [
                "Phase 3",
                "3–4 周",
                "回合战斗深化（或启动实时战斗立项）",
                "技能链完整；弱点击破；Assist 可用；或实时战斗技术预研通过",
            ],
            [
                "Phase 4",
                "按需",
                "Center 真迁移 + 挑战联机战局",
                "跨节点进房可玩；匹配后共享结算",
            ],
        ],
    )

    # 七、关键代码索引
    add_heading(doc, "七、关键代码与配置索引", 1)
    add_table(
        doc,
        ["模块", "路径 / 资源"],
        [
            ["场景运行时", "src/main/java/.../scene/SceneManager.java、SceneContext.java、SceneNettyService.java"],
            ["AOI / Zone", "src/main/java/.../scene/ZoneManager.java、AoiGrid.java、SceneSyncBroadcaster.java"],
            ["中心路由", "src/main/java/.../center/SceneRegistry.java、PlayerMigrationService.java"],
            ["战斗运行时", "src/main/java/.../battle/BattleManager.java、BattleContext.java、BattleNettyService.java"],
            ["战斗辅助", "src/main/java/.../battle/assist/HeuristicBattleAssistPolicy.java"],
            ["挑战", "src/main/java/.../challenge/ChallengeNettyService.java"],
            ["场景协议", "src/main/proto/scene_system.proto（Cmd 约 300+）"],
            ["战斗协议", "src/main/proto/battle_system.proto（含 scene_entity_uid、teleport_scene_id）"],
            ["场景配置", "DB scene_config.groups JSON"],
            ["战斗配置", "DB battle + battle_monster_wave + maze_skill / maze_buff"],
        ],
    )

    # 八、总结
    add_heading(doc, "八、总结", 1)
    add_body(
        doc,
        "MyLunarCore 已具备分区场景 + AOI 见人 + 中心路由骨架 + 回合波次战斗的二游服务器底座，"
        "方向上更贴近崩铁式「探索切战斗」，尚不符合无缝实时大世界设定。",
    )
    add_body(
        doc,
        "短期最高价值投入是 P0：场景实体绑定开战、会话切入 BATTLE、权威结算、战后回落与奖励。"
        "中期做 Zone 权威实体与遭遇引擎；长期在「回合深化」与「实时无缝」之间做明确产品选择后再投入。"
        "在 Center 路由与状态迁移成熟前，不宜拆出 battle/scene 微服务。",
    )

    return doc


def main():
    DESKTOP.mkdir(parents=True, exist_ok=True)
    doc = build_document()
    doc.save(OUTPUT)
    print(f"Wrote: {OUTPUT}")


if __name__ == "__main__":
    main()
