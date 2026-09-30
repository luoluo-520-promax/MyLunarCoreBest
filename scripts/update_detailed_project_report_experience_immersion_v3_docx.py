# -*- coding: utf-8 -*-
"""将本轮「体验优化三期」7 项服务端功能同步进桌面《MyLunarCore项目各方面详细总结报告.bak.docx》。

只原地修订该文件，不另存新文档、不生成第二份 .docx。
幂等标记：正文出现 PlayerMoveService 或 CmdId 1080 或 7.25 体验优化三期 即视为已写入本轮。
"""

from datetime import date
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.shared import Pt, RGBColor

DOC_PATH = Path.home() / "Desktop" / (
    "MyLunarCore"
    + "\u9879\u76ee\u5404\u65b9\u9762\u8be6\u7ec6\u603b\u7ed3\u62a5\u544a"
    + ".bak.docx"
)


def set_run_font(run, size=11, bold=False, color=None):
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    run.font.size = Pt(size)
    run.bold = bold
    if color is not None:
        run.font.color.rgb = color


def replace_paragraph_text(paragraph, new_text):
    style = paragraph.style
    for r in list(paragraph.runs):
        r.text = ""
    if paragraph.runs:
        paragraph.runs[0].text = new_text
        set_run_font(paragraph.runs[0])
    else:
        run = paragraph.add_run(new_text)
        set_run_font(run)
    paragraph.style = style


def insert_paragraph_after(paragraph, text, style_name="List Bullet"):
    new_para = paragraph._parent.add_paragraph(text, style=style_name)
    paragraph._p.addnext(new_para._p)
    for r in new_para.runs:
        set_run_font(
            r,
            size=13 if style_name.startswith("Heading") else 11,
            bold=style_name.startswith("Heading"),
        )
        if style_name.startswith("Heading"):
            r.font.color.rgb = RGBColor(25, 75, 140)
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


def replace_if(doc, *, exact=None, startswith=None, contains=None, new_text=None):
    p = find_para(doc, exact=exact, startswith=startswith, contains=contains)
    if p is not None and new_text is not None:
        replace_paragraph_text(p, new_text)
        return True
    return False


def set_cell_text(cell, text):
    cell.text = text
    for para in cell.paragraphs:
        for r in para.runs:
            set_run_font(r, size=10)


def patch_table_cell(table, old_exact=None, old_contains=None, col=0, new_text=None, new_other=None):
    for row in table.rows:
        cur = row.cells[col].text.strip()
        hit = False
        if old_exact is not None and cur == old_exact:
            hit = True
        if old_contains is not None and old_contains in cur:
            hit = True
        if not hit:
            continue
        if new_text is not None:
            set_cell_text(row.cells[col], new_text)
        if new_other is not None:
            for ci, val in new_other.items():
                set_cell_text(row.cells[ci], val)
        return True
    return False


def append_table_row(table, values):
    row = table.add_row()
    for i, val in enumerate(values):
        if i < len(row.cells):
            set_cell_text(row.cells[i], val)
    return row


def table_has(table, needle, col=0):
    return any(needle in row.cells[col].text for row in table.rows)


def main():
    if not DOC_PATH.exists():
        raise SystemExit(f"文档不存在: {DOC_PATH}")

    doc = Document(str(DOC_PATH))
    if find_para(doc, contains="7.25 体验优化三期") is not None:
        print(f"Already patched (7.25 present): {DOC_PATH}")
        return
    if find_para(doc, contains="PlayerMoveService") is not None and find_para(
        doc, contains="CmdId 1080"
    ) is not None:
        print(f"Already patched (PlayerMoveService + 1080 present): {DOC_PATH}")
        return

    today = f"{date.today().year}年{date.today().month:02d}月{date.today().day:02d}日"
    n = 0

    # ---------- 封面 ----------
    if replace_if(
        doc,
        exact=(
            "（含技术架构、业务能力、安全运维、微服务迁移、体验缺口补齐、体验闭环、"
            "体验沉浸、体验优化二期与后续建议）"
        ),
        new_text=(
            "（含技术架构、业务能力、安全运维、微服务迁移、体验缺口补齐、体验闭环、"
            "体验沉浸、体验优化二期、体验优化三期与后续建议）"
        ),
    ):
        n += 1
    elif replace_if(
        doc,
        contains="体验优化二期与后续建议",
        new_text=(
            "（含技术架构、业务能力、安全运维、微服务迁移、体验缺口补齐、体验闭环、"
            "体验沉浸、体验优化二期、体验优化三期与后续建议）"
        ),
    ):
        n += 1

    if replace_if(
        doc,
        startswith="报告生成日期：",
        new_text=(
            f"报告生成日期：{today}"
            "（已同步体验优化三期 7 项：环境微交互池 EnvInteractDetector + 客户端预测移动 PlayerMoveService、"
            "设备热管理/帧率探针 ReportDevicePerf 与 ScenePerformanceAdjust、"
            "WorldTimeService 箱庭时段与 DailyMission timePeriod 挂钩、"
            "Auto target_focus 集火/破盾 + BatchSweepRewardSummary 结算快进、"
            "家园虚影问候 HomeShadowGreeting 与死亡荧光残影 DeathEcho、"
            "AI IntentExecuteHandler 指令化导航 SceneNavigate/AutoPathFind、"
            "DeviceHapticsConfig 触觉热更与 BattleFx haptic 字段；协议号段 CmdId 1078–1097）"
        ),
    ):
        n += 1

    if replace_if(
        doc,
        startswith="报告依据：当前仓库 README",
        new_text=(
            "报告依据：当前仓库 README、源码包结构、配置文件与已有评估文档快照；"
            "此前已写入体验闭环（CmdId 1020–1045）、体验沉浸（1046–1069）、体验优化二期（1070–1076）；"
            "本轮再把体验优化三期 7 项（CmdId 1078–1097）写入对应章节，"
            "回归测试 ExperienceImmersionV3FlowsTest / ExperienceImmersionV3Test / "
            "ExperienceImmersionProtocolFlowsTest / ExperienceOptV2FlowsTest。"
        ),
    ):
        n += 1

    # ---------- 7.3 场景：微交互 + 预测 + 热管理 + 世界时间 ----------
    p_scene = find_para(doc, startswith="玩家进入某个 Plane/Floor 对应的 Zone 实例后")
    if p_scene is not None and "EnvInteractDetector" not in p_scene.text:
        replace_paragraph_text(
            p_scene,
            p_scene.text.strip().rstrip("。")
            + "。"
            "进一步引入环境微交互池 EnvInteractDetector（踢石子/草丛晃动/水面涟漪，仅下发坐标与类型，"
            "推送 SceneEnvMicroInteractScNotify CmdId 1078）与 PlayerMoveService："
            "校验轨迹向量而非逐帧精确坐标，允许约 100ms 客户端先行以消除黏滞感。"
            "客户端每 5s 上报 SoC 温度/FPS/电量（ReportDevicePerf 1080–1081）；"
            "温度 >45℃ 或严重掉帧时服务端主动下发 ScenePerformanceAdjustScNotify（1082）强制渲染降级，"
            "与仅按 RTT 降特效的 FxQualityAdvisor 解耦。"
            "WorldTimeService 以 Tick 维护黎明/正午/黄昏/深夜全局时段（GetWorldTime/WorldTimeScNotify 1083–1085），"
            "客户端据时间戳切光照；战败可在落点留下 30 分钟荧光残影（DeathEcho，1090–1092）供路人抚慰。",
        )
        n += 1

    p_move = find_para(doc, startswith="移动状态机与落地：MovementPhysics")
    if p_move is not None and "PlayerMoveService" not in p_move.text:
        replace_paragraph_text(
            p_move,
            "移动状态机与落地：MovementPhysics + SceneInteractPhysicsScNotify（CmdId 1053，"
            "落地材质/触觉/脚步声效，landing 标记）；"
            "环境微交互 EnvInteractDetector → SceneEnvMicroInteractScNotify（1078）；"
            "客户端预测 PlayerMoveService（轨迹向量 + 100ms 先行窗口）。",
        )
        n += 1

    # ---------- 7.4 战斗：target_focus + haptic ----------
    p_battle = find_para(doc, startswith="场景中触发遭遇后进入战斗实例")
    if p_battle is not None and "target_focus" not in p_battle.text:
        replace_paragraph_text(
            p_battle,
            p_battle.text.strip().rstrip("。")
            + "。"
            "SetBattleAuto 扩展 target_focus（0 默认 / 1 集火精英 / 2 优先破盾），"
            "Auto 选目标时按 focus 重排，避免扫荡清小怪翻车。"
            "BattleFxScNotify（213）嵌入 haptic_intensity 与 waveform_id，"
            "配合 DeviceHapticsConfig 热更表（PS5/Xbox/手机震动波形，CmdId 1095–1097）做差异化力反馈。",
        )
        n += 1

    p_auto = find_para(doc, startswith="Auto/倍速/策略：BattleAutoService")
    if p_auto is not None and "target_focus" not in p_auto.text:
        replace_paragraph_text(
            p_auto,
            p_auto.text.strip().rstrip("。")
            + "；target_focus 集火精英/优先破盾；"
            "BattleFxScNotify 带 haptic_intensity / waveform_id（DeviceHapticsConfig）。",
        )
        n += 1

    # ---------- 7.5 扫荡快进 ----------
    p_sweep = find_para(doc, startswith="扫荡：SweepService / SweepNettyService")
    if p_sweep is not None and "BatchSweepRewardSummary" not in p_sweep.text:
        replace_paragraph_text(
            p_sweep,
            p_sweep.text.strip().rstrip("。")
            + "；连战结算另推 BatchSweepRewardSummaryScNotify（1086），"
            "只展示增量摘要并 skip_loot_box_anim，压缩日常耗时。",
        )
        n += 1

    # ---------- 7.10 / 家园虚影问候 ----------
    p_home_entry = find_para(doc, startswith="入口：HomeNettyService")
    if p_home_entry is None:
        p_home_entry = find_para(doc, contains="HomeAssistEffectScNotify")
    if p_home_entry is not None and "HomeShadowGreeting" not in p_home_entry.text:
        replace_paragraph_text(
            p_home_entry,
            p_home_entry.text.strip().rstrip("。")
            + "；虚影点赞/赠礼 HomeShadowGreeting（1087–1089）回馈微量储备体力，"
            "对方上线收 FriendShadowGreetingNotify。",
        )
        n += 1

    # ---------- 7.19 每日任务时段 ----------
    p_daily_mission = find_para(doc, startswith="日常循环：体力、每日任务、战令")
    if p_daily_mission is not None and "timePeriod" not in p_daily_mission.text:
        replace_paragraph_text(
            p_daily_mission,
            p_daily_mission.text.strip().rstrip("。")
            + "；DailyMissionConfigs.timePeriod 与 WorldTimeService 时段挂钩"
            "（如夜间任务仅 night 出现）。",
        )
        n += 1

    # ---------- 10 AI IntentExecute ----------
    p_ai = find_para(doc, startswith="AI 助手")
    if p_ai is None:
        p_ai = find_para(doc, contains="站内 WebView")
    # 找 10.1 附近更稳妥的段落
    p_ai_cap = find_para(doc, contains="主流平台攻略默认在站内 WebView")
    if p_ai_cap is not None and "IntentExecuteHandler" not in p_ai_cap.text:
        # 在 AI 章节找能力描述段
        pass

    p_ai_what = find_para(doc, startswith="它能做的事")
    if p_ai_what is None:
        p_ai_what = find_para(doc, contains="问任务/养成/活动")
    if p_ai_what is not None and "IntentExecuteHandler" not in p_ai_what.text:
        replace_paragraph_text(
            p_ai_what,
            p_ai_what.text.strip().rstrip("。")
            + "。"
            "本轮增加 IntentExecuteHandler：自然语言转游戏内指令——"
            "「当前任务目标在哪」直接推 SceneNavigateScNotify（1093）画荧光引路线；"
            "「缺的材料在哪」推 AutoPathFindPushScNotify（1094）自动寻路至掉落入口，"
            "结果必须变成地图红点/路径而非纯文本外链。",
        )
        n += 1
    else:
        # 兜底：在结语/助手相关列表追加一句
        p_assist_entry = find_para(doc, contains="AskAiAssist")
        if p_assist_entry is not None and "IntentExecuteHandler" not in p_assist_entry.text:
            replace_paragraph_text(
                p_assist_entry,
                p_assist_entry.text.strip().rstrip("。")
                + "；IntentExecuteHandler 可将问路/材料意图转为 SceneNavigate（1093）/"
                "AutoPathFindPush（1094）。",
            )
            n += 1

    # ---------- 设置/触觉 ----------
    p_settings = find_para(doc, startswith="版本/热更查询协议")
    if p_settings is None:
        p_settings = find_para(doc, contains="SyncKeyBind")
    if p_settings is not None and "DeviceHapticsConfig" not in p_settings.text:
        replace_paragraph_text(
            p_settings,
            p_settings.text.strip().rstrip("。")
            + "；DeviceHapticsConfig.json 热更触觉波形，"
            "GetDeviceHaptics / PushDeviceHaptics（1095–1097）按 device_family 下发 PS5/Xbox/手机元数据。",
        )
        n += 1

    # ---------- 玩家一天流程 ----------
    p_day = find_para(doc, startswith="可看剧情树快照与 SAVEPOINT")
    if p_day is None:
        p_day = find_para(doc, contains="遇敌打回合战（可开 Auto")
    if p_day is not None and "target_focus" not in p_day.text and "荧光引路线" not in p_day.text:
        replace_paragraph_text(
            p_day,
            p_day.text.strip()
            .replace(
                "可开 Auto 并切换优先战技/普攻/攒能量",
                "可开 Auto 并切换优先战技/普攻/攒能量与 target_focus 集火/破盾",
            )
            .replace(
                "已通关材料关可一键连续扫荡",
                "已通关材料关可一键连续扫荡（结算只看增量摘要、跳过抽奖盒动画）",
            )
            .replace(
                "必要时问 AI 助手（站内 WebView 打开 B站/抖音/官网攻略）",
                "迷路时问 AI 助手可直接在地图画荧光引路线或自动寻路到材料门前"
                "（也可站内 WebView 打开攻略）",
            )
            .replace(
                "回家园点点基建、摆家具、好友助产时设施金光一闪与飘字、非好友虚影串门、看拜访日志",
                "回家园点点基建、摆家具、好友助产金光飘字、点虚影问候回馈储备体力、"
                "非好友虚影串门、看拜访日志；路上可见他人战败荧光残影并可抚慰",
            ),
        )
        n += 1

    # ---------- 7.25 专节 ----------
    if find_para(doc, contains="7.25 体验优化三期") is None:
        insert_at = find_para(doc, startswith="测试入口：ExperienceOptV2FlowsTest")
        if insert_at is None:
            insert_at = find_para(doc, contains="7.24 体验优化二期")
        if insert_at is None:
            raise SystemExit("未找到 7.25 插入锚点")
        h = insert_paragraph_after(
            insert_at, "7.25 体验优化三期（CmdId 1078–1097）", "Heading 2"
        )
        p1 = insert_paragraph_after(
            h,
            "在体验优化二期（1070–1076）之后，本轮针对箱庭微交互手感、设备烫手掉帧、世界时段呼吸感、"
            "Auto 扫荡目标锁定、连战结算耗时、虚影社交深度、AI 站内指令化与多端触觉反馈做了 7 项补齐。"
            "协议仍遵循 CS_REQ = N、SC_RSP = N+1；新增号段占用 1078、1080–1097。",
            "Normal",
        )
        p2 = insert_paragraph_after(
            p1,
            "移动：EnvInteractDetector 微交互池推送 1078；PlayerMoveService 轨迹向量校验 + 100ms 预测先行。"
            "性能：ReportDevicePerf（1080–1081）上报温度/FPS/电量；>45℃ 下发 ScenePerformanceAdjust（1082）。"
            "时空：WorldTimeService 黎明/正午/黄昏/深夜（1083–1085）；DailyMission timePeriod 挂钩夜间任务。"
            "战斗：SetBattleAuto.target_focus 集火精英/优先破盾；BatchSweepRewardSummary（1086）跳过抽奖盒。"
            "社交：HomeShadowGreeting（1087–1089）虚影赠礼回馈储备体力；DeathEcho（1090–1092）战败荧光残影可抚慰。"
            "AI：IntentExecuteHandler → SceneNavigate（1093）/ AutoPathFindPush（1094）。"
            "触觉：DeviceHapticsConfig + Get/PushDeviceHaptics（1095–1097）；BattleFxScNotify 带 waveform_id。",
            "Normal",
        )
        insert_paragraph_after(
            p2,
            "测试入口：ExperienceImmersionV3FlowsTest（三期 7 项业务流程全覆盖）、"
            "ExperienceImmersionV3Test（微交互/预测/热管理单元）、"
            "ExperienceImmersionProtocolFlowsTest（BatchSweep 双 Notify 回归）、"
            "ExperienceOptV2FlowsTest / ExperienceImmersionFlowsTest（既有回归）。",
            "List Bullet",
        )
        n += 1

    # ---------- 8.4 协议版本 ----------
    p_wire = find_para(doc, startswith="为避免新旧客户端「各说各话」")
    if p_wire is not None and "1078" not in p_wire.text and "1097" not in p_wire.text:
        replace_paragraph_text(
            p_wire,
            "为避免新旧客户端「各说各话」，工程引入了协议线版本概念。"
            "当前 PROTOCOL_WIRE_VERSION = 3（登录握手增加 input_methods / recommended_layout_id）；"
            "ProtocolCompatService.isCompatible 仍接受 wire ≥ 2，拒绝过旧的 v1 客户端"
            "（v1 角色号段 120–129 会撞上组队）。"
            "角色相关命令号已迁到 160–173。体验闭环占用 CmdId 1020–1045，"
            "体验沉浸占用 1046–1069，体验优化二期占用 1070–1076，体验优化三期占用 1078–1097"
            "（docs/meta/cmdid-config-meta.yaml 已登记 experience_immersion_v3）。"
            "因此：改协议必须同步客户端，并尽量用自动化测试（如 CmdIdUniquenessTest）防止号段再重叠。"
            "仓库还提供 buf.yaml，作为 Protobuf lint / breaking 检查基线。",
        )
        n += 1

    # ---------- 15.x ----------
    p_confirm = find_para(doc, startswith="确认客户端协议线版本与 CmdId 已对齐 PROTOCOL_WIRE_VERSION=3")
    if p_confirm is not None and "1097" not in p_confirm.text:
        replace_paragraph_text(
            p_confirm,
            "确认客户端协议线版本与 CmdId 已对齐 PROTOCOL_WIRE_VERSION=3"
            "（兼容守卫仍为 wire ≥ 2；v3 字段为 input_methods / recommended_layout_id；"
            "沉浸 1046–1069、二期 1070–1076 与三期 1078–1097 需与客户端同步）。",
        )
        n += 1

    p_v2_reg = find_para(doc, startswith="体验优化二期回归：ExperienceOptV2FlowsTest")
    if p_v2_reg is not None and find_para(doc, contains="ExperienceImmersionV3FlowsTest") is None:
        insert_paragraph_after(
            p_v2_reg,
            "体验优化三期回归：ExperienceImmersionV3FlowsTest 覆盖微交互与预测移动、热管理降级、"
            "世界时间与夜间任务、Auto target_focus、BatchSweepRewardSummary、虚影问候与死亡残影、"
            "AI 指令导航、DeviceHaptics 与 BattleFx 触觉（CmdId 1078–1097）。",
        )
        n += 1

    p_guide = find_para(doc, contains="体验优化二期：场景环境联动（1070）")
    if p_guide is not None and "1097" not in p_guide.text:
        replace_paragraph_text(
            p_guide,
            p_guide.text.strip().rstrip("。")
            + "；体验优化三期：微交互/预测移动（1078）、热管理（1080–1082）、世界时间（1083–1085）、"
            "扫荡摘要（1086）、虚影问候（1087–1089）、死亡残影（1090–1092）、"
            "AI 导航（1093–1094）、设备触觉（1095–1097）。",
        )
        n += 1

    if replace_if(
        doc,
        startswith="协议已迁到 wire v3（兼容守卫仍 ≥ 2）",
        new_text=(
            "协议已迁到 wire v3（兼容守卫仍 ≥ 2）；v1 客户端必须升级；"
            "改 CmdId（含 1020–1045 闭环、1046–1069 沉浸、1070–1076 二期与 1078–1097 三期）"
            "必须与客户端同步。"
        ),
    ):
        n += 1

    # ---------- 16.1 ----------
    p_short = find_para(doc, startswith="继续以模块化单体打磨崩铁式体验与数值内容")
    if p_short is not None and "EnvInteractDetector" not in p_short.text:
        replace_paragraph_text(
            p_short,
            "继续以模块化单体打磨崩铁式体验与数值内容；"
            "客户端对齐环境微交互粒子、100ms 预测移动、热管理降级指令、昼夜光照、"
            "Auto target_focus UI、连战增量结算、虚影问候与死亡残影抚慰、"
            "AI 荧光引路线/自动寻路、以及 DeviceHaptics 震动波形；"
            "并延续 DISSOLVE/RIFT、大招预表现、养成日程、云端键位与 recommended_layout_id。",
        )
        n += 1

    # ---------- 结语 ----------
    p_end = find_para(doc, startswith="MyLunarCore 已经不是「只有空壳的演示仓库」")
    if p_end is not None and "PlayerMoveService" not in p_end.text:
        replace_paragraph_text(
            p_end,
            "MyLunarCore 已经不是「只有空壳的演示仓库」：它在单体形态下具备较完整的崩铁式 RPG 服务端能力——"
            "联网、场景（含强制/方向预判预加载、DISSOLVE/RIFT/BLACK 切图、跳跃落地物理反馈、"
            "环境微交互池、100ms 客户端预测移动、世界时段、死亡荧光残影与热管理渲染降级）、"
            "回合战（含 Auto 策略权重与 target_focus、抢占式手动大招、倍速出手、打击感 FX/"
            "弱网降档与设备触觉波形）、"
            "养成（含材料计划、日程表、一键连续扫荡与增量结算摘要）、经济抽卡、"
            "体力与每日循环（含扫荡、满体储备溢出、时段挂钩每日任务、过期道具转化）、"
            "战令与主线、活动、对话 SAVEPOINT/环境联动、"
            "家园（家具/虚影在场/助产视觉/虚影问候赠礼）、公会与竞技、"
            "世界 BOSS/深渊等缺口玩法、成就与材料反查、新手引导、匹配 Ready Check、"
            "跨节点跟随迁移、表情与键位云同步、多端输入协商（wire v3）、运维热更与业务监控，"
            "以及能力增强后的 AI 助手旁路（站内 WebView 攻略摘要 + IntentExecute 地图引路线/自动寻路）。"
            "与此同时，它也很诚实地把微服务目录标成脚手架，把无缝大世界标成实验，把默认密钥标成仅限本地。",
        )
        n += 1

    if replace_if(
        doc,
        startswith="本报告基于仓库当前代码与文档快照整理",
        new_text=(
            f"本报告基于仓库当前代码与文档快照整理，{today} 已在本文档内同步体验优化三期 7 项"
            "（CmdId 1078–1097；此前二期为 1070–1076、沉浸为 1046–1069、闭环为 1020–1045、"
            "PROTOCOL_WIRE_VERSION=3），"
            "可用于汇报、培训与决策讨论。若后续架构或配置有重大变更，建议修订对应章节后刷新目录域，"
            "以便目录页码与正文一致。"
        ),
    ):
        n += 1

    # ---------- 表 3 包一览 ----------
    t3 = doc.tables[3]
    n += int(
        patch_table_cell(
            t3,
            old_contains="强制/方向预判 Preload、DISSOLVE/RIFT/BLACK",
            col=2,
            new_text=(
                "进图、移动状态机/预测先行、落地物理与环境微交互、AOI、Zone、"
                "强制/方向预判 Preload、DISSOLVE/RIFT/BLACK、世界时段、死亡残影、热管理降级"
            ),
        )
    )
    n += int(
        patch_table_cell(
            t3,
            old_contains="抢占式手动大招、打击感 FX 与弱网降档",
            col=2,
            new_text=(
                "回合战、遭遇、波次、BattleAuto 策略/target_focus/倍速/抢占式手动大招、"
                "打击感 FX 与弱网降档、触觉 waveform"
            ),
        )
    )
    n += int(
        patch_table_cell(
            t3,
            old_contains="助产视觉、虚影在场、拜访日志",
            col=2,
            new_text=(
                "基建、产出、家具摆放/交互、好友互访/助产视觉、虚影在场与虚影问候赠礼、"
                "拜访日志/留言、溢出推送"
            ),
        )
    )

    # ---------- 表 5 CmdId ----------
    t5 = doc.tables[5]
    n += int(
        patch_table_cell(
            t5,
            old_exact="1053",
            new_other={1: "场景物理落地反馈 SceneInteractPhysics（微交互见 1078）"},
        )
    )
    n += int(
        patch_table_cell(
            t5,
            old_contains="战斗打击感元数据 FX",
            col=0,
            new_other={1: "战斗打击感 FX + 触觉 intensity/waveform（热见表 1095–1097）"},
        )
    )
    # 200–213 行可能是合并描述
    for row in t5.rows:
        if "200" in row.cells[0].text and "213" in row.cells[0].text and "触觉" not in row.cells[1].text:
            set_cell_text(
                row.cells[1],
                row.cells[1].text.strip().rstrip("。")
                + "；BattleFx 含 haptic_intensity / waveform_id",
            )
            n += 1
            break

    extra_cmd_rows = [
        ["1078", "环境微交互 SceneEnvMicroInteractScNotify"],
        ["1080–1081", "设备性能探针 ReportDevicePerf"],
        ["1082", "渲染降级 ScenePerformanceAdjustScNotify"],
        ["1083–1084", "拉取世界时间 GetWorldTime"],
        ["1085", "世界时段推送 WorldTimeScNotify"],
        ["1086", "连战增量结算 BatchSweepRewardSummaryScNotify"],
        ["1087–1088", "家园虚影问候 HomeShadowGreeting"],
        ["1089", "虚影问候回馈 FriendShadowGreetingNotify"],
        ["1090", "死亡荧光残影同步 SceneDeathEchoSyncScNotify"],
        ["1091–1092", "抚慰残影 ComfortDeathEcho"],
        ["1093", "AI 荧光引路线 SceneNavigateScNotify"],
        ["1094", "AI 自动寻路 AutoPathFindPushScNotify"],
        ["1095–1096", "设备触觉配置 GetDeviceHaptics"],
        ["1097", "触觉波形推送 PushDeviceHapticsScNotify"],
    ]
    if not table_has(t5, "1078"):
        for values in extra_cmd_rows:
            append_table_row(t5, values)
            n += 1

    # ---------- 表 17 术语 ----------
    t17 = doc.tables[17]
    extra_terms = [
        ["环境微交互池", "跑图时踢石子/草丛/水面涟漪等表现触发；服务端只下发坐标与类型，客户端播粒子"],
        ["客户端预测移动", "校验轨迹向量并允许约 100ms 本地先行，减轻移动黏滞感，仍保留反作弊速度守卫"],
        ["热管理降级", "按 SoC 温度/FPS/电量由服务端下发渲染降级，与按 RTT 降特效解耦"],
        ["WorldTime 时段", "黎明/正午/黄昏/深夜预设切换；可与每日任务 timePeriod 挂钩"],
        ["target_focus", "Auto 目标锁定：默认 / 集火精英 / 优先破盾"],
        ["BatchSweepRewardSummary", "连战结算只展示增量摘要并跳过 3D 抽奖盒动画"],
        ["死亡荧光残影", "战败落点存坐标 30 分钟，路过玩家可抚慰并发鼓励邮件"],
        ["IntentExecuteHandler", "把自然语言意图转成地图引路线/自动寻路等游戏内指令"],
        ["DeviceHapticsConfig", "按设备族下发震动波形元数据，BattleFx 携带 waveform_id"],
    ]
    for term, expl in extra_terms:
        if not table_has(t17, term[:6]):
            append_table_row(t17, [term, expl])
            n += 1

    # ---------- 表 18 FAQ ----------
    t18 = doc.tables[18]
    extra_faq = [
        [
            "为什么手机还是烫？",
            "不要只看网速。客户端应 5s 上报温度/FPS；服务端在 >45℃ 时主动下发 1082 强制降 LOD/关虚影。",
        ],
        [
            "Auto 为什么总先清小怪？",
            "开启 SetBattleAuto 时设置 target_focus=1（集火精英）或 2（优先破盾）。",
        ],
        [
            "连续扫荡为什么还在播抽奖盒？",
            "客户端应消费 BatchSweepRewardSummaryScNotify（1086），skip_loot_box_anim=true 时只播增量摘要。",
        ],
        [
            "问 AI「任务在哪」为什么还是外链？",
            "应走 IntentExecuteHandler：服务端直接推 SceneNavigateScNotify（1093）画荧光引路线。",
        ],
        [
            "手柄反击没震动？",
            "先 GetDeviceHaptics 拉波形表，再按 BattleFxScNotify.waveform_id 播对应 haptic。",
        ],
    ]
    for q, a in extra_faq:
        if not table_has(t18, q[:6]):
            append_table_row(t18, [q, a])
            n += 1

    doc.save(str(DOC_PATH))
    print(f"Updated in place: {DOC_PATH}")
    print(f"Patches applied (approx): {n}")


if __name__ == "__main__":
    main()
