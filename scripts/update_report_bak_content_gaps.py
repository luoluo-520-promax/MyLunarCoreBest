# -*- coding: utf-8 -*-
"""原地更新桌面报告.bak.docx：写入「五大内容缺口」落地说明，不另存新文件。"""

from __future__ import annotations

from copy import deepcopy
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.shared import Pt, Cm, RGBColor

DOC_PATH = Path(r"c:\Users\ASUS\Desktop\MyLunarCore项目各方面详细总结报告.bak.docx")


def set_run_font(run, size=11, bold=None, color=None):
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    run.font.size = Pt(size)
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = color


def replace_paragraph_text(paragraph, text: str, *, bold=False, size=11, color=None):
    """清空段落 runs 后写入新文本，保留段落样式。"""
    for r in paragraph.runs:
        r.text = ""
    if paragraph.runs:
        paragraph.runs[0].text = text
        set_run_font(paragraph.runs[0], size=size, bold=bold, color=color)
    else:
        run = paragraph.add_run(text)
        set_run_font(run, size=size, bold=bold, color=color)


def insert_after(paragraph, text: str, style: str | None = None):
    """在指定段落后插入新段落，返回新段落。"""
    new_p = deepcopy(paragraph._element)
    # 清空克隆内容
    for child in list(new_p):
        if child.tag.endswith("}r") or child.tag.endswith("}hyperlink"):
            new_p.remove(child)
    paragraph._element.addnext(new_p)
    # 重新包装为 paragraph 对象
    from docx.text.paragraph import Paragraph

    p = Paragraph(new_p, paragraph._parent)
    if style:
        p.style = style
    replace_paragraph_text(
        p,
        text,
        bold=(style or "").startswith("Heading"),
        size=13 if style == "Heading 2" else (16 if style == "Heading 1" else 11),
        color=RGBColor(25, 75, 140) if (style or "").startswith("Heading") else None,
    )
    if style is None or style == "Normal":
        p.paragraph_format.first_line_indent = Cm(0.74)
    return p


def find_para(doc: Document, predicate):
    for p in doc.paragraphs:
        if predicate(p.text.strip()):
            return p
    return None


def append_bullet_after_section_end(doc: Document, heading_startswith: str, bullet_text: str):
    """在某 Heading 2 小节正文末尾（下一 Heading 之前）追加一条项目符号。"""
    paras = list(doc.paragraphs)
    start = None
    for i, p in enumerate(paras):
        if p.style and p.style.name.startswith("Heading") and p.text.strip().startswith(heading_startswith):
            start = i
            break
    if start is None:
        return
    end = start + 1
    while end < len(paras):
        st = paras[end].style.name if paras[end].style else ""
        if st.startswith("Heading"):
            break
        end += 1
    # 插入到 end-1 段落后
    anchor = paras[end - 1]
    insert_after(anchor, bullet_text, style="List Bullet")


def main():
    if not DOC_PATH.is_file():
        raise SystemExit(f"找不到文档: {DOC_PATH}")

    doc = Document(str(DOC_PATH))

    # ---------- 封面与依据 ----------
    p = find_para(doc, lambda t: t.startswith("（含技术架构、业务能力"))
    if p:
        replace_paragraph_text(
            p,
            "（含技术架构、业务能力、安全运维、微服务迁移、体验缺口补齐、体验闭环、体验沉浸、体验优化二期/三期、"
            "AI 助手增强、战斗手感与拥堵防雪崩、性能深化、运营热更七项短板、wire v4 稳定性与体验深化、"
            "「十大体验/生产缺口」与 P11 体验/社交缺口，以及「五大内容深度缺口」落地：剧情表演指令集、命座/星魂、"
            "机关谜题与隐藏收集、Raid 职责连携、非战斗 MiniGame 框架）",
        )

    p = find_para(doc, lambda t: t.startswith("报告生成日期："))
    if p:
        replace_paragraph_text(
            p,
            "报告生成日期：2026年09月04日（在 P11 体验/社交缺口 CmdId 1200–1247 基础上，已同步落地「五大内容深度缺口」："
            "DialoguePerformanceService 剧情 Timeline（1248）、命座/星魂 ConstellationSystem 与抽卡结果推送（1249）、"
            "PuzzleStateService/CollectibleService/ExplorationEventService（1250–1251）、"
            "Raid 职责 READY_ROOM + TeamCombo + Ping 升级（1252–1253）、"
            "MiniGameFramework 跑酷/塔防/钓鱼与 NewMiniGame/HighScore 推送（1254–1255）；"
            "配置见 DialoguePerformanceScripts.json / CharacterConstellationConfig.json / PuzzleConfigs.json 等）",
        )

    p = find_para(doc, lambda t: t.startswith("下面先给出章节速览"))
    if p:
        replace_paragraph_text(
            p,
            "下面先给出章节速览（方便不打开自动目录时也能快速跳读）。其后是 Word 自动目录域：请用 Microsoft Word 或 WPS "
            "打开本文件后，右键点击自动目录区域，选择「更新域 / 更新整个目录」，即可显示带页码的完整目录。"
            "第七章已新增 7.31「十大体验/生产缺口」、7.34「P11 体验/社交缺口」、7.35「五大内容深度缺口（CmdId 1248–1255）」："
            "剧情沉浸表演、命座养成、探索谜题、Raid 团队协作、非战斗活动玩法。",
        )

    p = find_para(doc, lambda t: t.startswith("报告依据："))
    if p:
        old = p.text.strip()
        if "五大内容深度缺口" not in old:
            replace_paragraph_text(
                p,
                old.rstrip("。")
                + "；本轮补充「五大内容深度缺口」落地（CmdId 1248–1255）与 ContentGapComprehensiveFlowsTest / "
                "ContentGapFillFlowsTest 回归。",
            )

    # ---------- 7.2 角色养成：命座 ----------
    p = find_para(doc, lambda t: t.startswith("覆盖创角、成长晋阶、天赋、属性计算"))
    if p and "命座" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。命座/星魂：AvatarEntity.rank 现由 ConstellationService 在重复抽取时自动 +1（上限 6）；"
            "配置 data/CharacterConstellationConfig.json（数值增强 / 机制改变 / 天赋升级 / 开局状态）；"
            "ConstellationEffectApplier 在战斗侧写入 CombatAttributeSheet / BuffModifier；"
            "AvatarRepository.updateRank 持久化层数。",
        )

    # ---------- 7.6 抽卡 ----------
    p = find_para(doc, lambda t: t.startswith("卡池信息可热更；一次抽卡尽量"))
    if p and "is_new_constellation" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。内容深度：角色池发奖后调用 ConstellationService.onAvatarObtained，"
            "GachaItem 增加 is_new_constellation / current_layer；并推送 GachaResultScNotify（CmdId 1249）。"
            "GetGachaGuaranteeInfo（1244–1245）扩展 up_constellation_layers，展示各 UP 角色当前命座层，"
            "便于「再几金到关键命」付费透明。",
        )
    p = find_para(doc, lambda t: t.startswith("入口：GachaNettyService"))
    if p and "ConstellationService" not in p.text:
        replace_paragraph_text(
            p,
            "入口：GachaNettyService、GachaApplicationService、GachaConfigService、"
            "ConstellationService、GachaGuaranteeQueryService",
        )
    p = find_para(doc, lambda t: t.startswith("配置：data/Banners.json"))
    if p and "CharacterConstellation" not in p.text:
        replace_paragraph_text(
            p,
            "配置：data/Banners.json、data/CharacterConstellationConfig.json；"
            "流水表相关迁移含 gacha_draw_history",
        )

    # ---------- 7.9 活动 ----------
    p = find_para(doc, lambda t: t.startswith("活动侧有排期与配置查询"))
    if p and "MiniGameFramework" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。非战斗玩法：MiniGameFramework 抽象 onStart/onTick/onEnd/getReward，"
            "内置 RacingActivity（跑酷）、TowerDefenseActivity（塔防）、FishingActivity（钓鱼）；"
            "配置 data/MiniGamePoolConfigs.json；Groovy 示例 data/scripts/activity/racing_festival.groovy。"
            "VersionThemeConfigs.miniGamePool 控制版本开放池；登录推送 NewMiniGameScNotify（1254）；"
            "个人最高/服内 Top10 推送 ActivityHighScoreScNotify（1255，可复用战报分享）。",
        )
    p = find_para(doc, lambda t: t.startswith("活动：ActivityNettyService"))
    if p and "MiniGameFramework" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；MiniGameFramework / RacingActivity / TowerDefenseActivity / FishingActivity；"
            "ActivityReminderService.pushNewMiniGames；VersionThemeService.miniGamePool",
        )

    # ---------- 7.16 对话 ----------
    p = find_para(doc, lambda t: t.startswith("除了战斗与经济循环，项目还补齐了「讲故事」"))
    if p and "DialoguePerformanceService" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。剧情沉浸：节点可配置 performanceId / voiceLineKey；"
            "DialoguePerformanceService 进入节点时下发 DialoguePerformanceScNotify（CmdId 1248，timeline_json / voice_id / lip_sync）；"
            "配置 data/DialoguePerformanceScripts.json（PLAY_EMOTION/GESTURE/VOICE/CAMERA/SCREEN_EFFECT/WAIT）与 "
            "data/DialogueVoiceConfig.json（audio_key + lip_sync_timestamps）；无关键语音时可回退 AssistTtsService。"
            "SceneEnvironmentModifyScNotify（1070）扩展 target_entity_id / emotion_id；"
            "选项 impact_tags 含 Affinity 时插入 perf_affinity_ack 收尾动作。",
        )
    p = find_para(doc, lambda t: t.startswith("对话树：DialogueTriggerEngine"))
    if p and "DialoguePerformanceService" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；DialoguePerformanceService / DialoguePerformanceScriptRepository / DialogueVoiceConfigRepository（CmdId 1248）",
        )

    # ---------- 7.21 探索相关一句 ----------
    p = find_para(doc, lambda t: t.startswith("在日常循环之外，仓库还按 docs/feature-gap-fill.md"))
    if p and "PuzzleStateService" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。探索深度再加深：PuzzleStateService 维护压力板/元素点亮/顺序踩踏/限时跑酷状态机，"
            "解谜广播 ScenePuzzleCompleteScNotify（1250）；CollectibleService（忆泡/档案，表 player_collectibles）"
            "与 ExplorationEventService 限时裂隙（1251）补齐跑图惊喜。详见 7.35。",
        )

    # ---------- 插入 7.35（在 7.34 客户端联调要点之后、第八章之前） ----------
    anchor = find_para(doc, lambda t: t.startswith("客户端联调要点：对接 1200–1247"))
    if anchor is None:
        anchor = find_para(doc, lambda t: t.startswith("7.34 P11"))
    # 避免重复插入
    already = find_para(doc, lambda t: t.startswith("7.35 五大内容深度缺口"))
    if already is None and anchor is not None:
        # 从后往前插，使最终顺序正确：先插最后一段，再插倒数第二…
        blocks = [
            ("Heading 2", "7.35 五大内容深度缺口（剧情表演 / 命座 / 探索谜题 / Raid 协作 / MiniGame，CmdId 1248–1255）"),
            (
                "Normal",
                "在 7.34 P11（CmdId 1200–1247）之后，本轮针对产品侧五条「内容深度」短板一次性落地服务端能力："
                "（1）剧情演出缺动态表演；（2）抽卡重复缺命座深度；（3）世界探索缺机关谜题与隐藏收集；"
                "（4）社交协作缺职责分工与技能连携；（5）活动同质化缺非战斗玩法。"
                "新增号段 1248–1255（推送为主）；客户端按 timeline / 命座层 / 谜题完成 / 连携特效 / 小玩法教学 URL 对接。",
            ),
            (
                "Normal",
                "（1）剧情表演指令集：DialogueNode 增加 performanceId / voiceLineKey；"
                "DialoguePerformanceService 在进入节点时推送 DialoguePerformanceScNotify（1248），"
                "timeline_json 对齐 PLAY_EMOTION / PLAY_GESTURE / PLAY_VOICE / CAMERA_SHOT / SCREEN_EFFECT / WAIT；"
                "DialogueVoiceConfig 提供 audio_key 与口型时间戳；非关键台词可 AssistTtsService 补位。"
                "Affinity 选项触发 SceneEnvironmentModify（1070，emotion_id）与 1–2 秒收尾动作 perf_affinity_ack。",
            ),
            (
                "Normal",
                "（2）命座/星魂：ConstellationService 重复获得自动升层（0–6）；"
                "CharacterConstellationConfig 支持 STAT_BOOST / MECHANIC / TALENT_UPGRADE / START_STATE；"
                "ConstellationEffectApplier 写入战斗属性；抽卡 GachaItem 带 is_new_constellation / current_layer，"
                "并推送 GachaResultScNotify（1249）；保底查询附带 UP 命座层。",
            ),
            (
                "Normal",
                "（3）探索谜题与收集：PuzzleStateService（INACTIVE→ACTIVE→SOLVED）覆盖压力板、元素点亮、顺序踩踏、限时跑酷；"
                "解谜推送 ScenePuzzleCompleteScNotify（1250，奖励与隐藏门坐标）；"
                "CollectibleService 分 NORMAL/HIDDEN/EASTER_EGG，达阈值解锁 lore_text_key；"
                "ExplorationEventService 结合 WorldTime 刷新限时裂隙 MiniChallenge（1251）。"
                "PlayerMoveService 接受移动后探测压力板。",
            ),
            (
                "Normal",
                "（4）Raid 团队协作：RaidRoleAssignmentService 推荐/指定 TANK/HEALER/DPS；"
                "GuildRaidInstanceService.enterStrategyPhase 进入 30 秒 READY_ROOM（1253）；"
                "HateTable 嘲讽优先、BattleHealService 范围溅射、RoleBuffProvider 职责加成；"
                "TeamComboTracker + ElementReactionConfig 触发 BattleComboScNotify（1252）；"
                "ScenePing 增加 FOCUS_FIRE/INTERRUPT/RETREAT 与 countdown_ms / 未就绪名单。",
            ),
            (
                "Normal",
                "（5）非战斗 MiniGame：MiniGameInstance 四钩子；跑酷/塔防/钓鱼模板 + racing_festival.groovy 热更；"
                "VersionTheme.miniGamePool 轮转；登录 NewMiniGameScNotify（1254）；"
                "Redis ZSET 排行与 ActivityHighScoreScNotify（1255）建议分享战报。",
            ),
            (
                "List Bullet",
                "实现入口：dialogue/DialoguePerformanceService；character/ConstellationService、ConstellationEffectApplier；"
                "exploration/PuzzleStateService、CollectibleService、ExplorationEventService；"
                "guild/RaidRoleAssignmentService、GuildRaidInstanceService；"
                "battle/HateTable、BattleHealService、TeamComboTracker、RoleBuffProvider；"
                "minigame/MiniGameFramework；scene/ScenePingService；CmdIds 1248–1255。",
            ),
            (
                "List Bullet",
                "配置：data/DialoguePerformanceScripts.json、DialogueVoiceConfig.json、CharacterConstellationConfig.json、"
                "PuzzleConfigs.json、CollectibleConfigs.json、ExplorationEvents.json、ElementReactionConfig.json、"
                "MiniGamePoolConfigs.json；VersionThemeConfigs.miniGamePool；scripts/activity/racing_festival.groovy。",
            ),
            (
                "List Bullet",
                "测试入口：ContentGapComprehensiveFlowsTest（19 条分缺口全流程）、ContentGapFillFlowsTest（5 条冒烟）；"
                "回归 DialogueCutsceneFlowTest、GachaNettyServiceTest、ExperienceImmersionV3Test 等已通过。",
            ),
            (
                "List Bullet",
                "客户端联调要点：对接 1248–1255；对话节点按 start_time 混播表情/口型/镜头；抽卡结果点亮命座星；"
                "谜题完成播机关特效并刷新路径；Raid 准备期语音沟通 + 连携/Ping 纪律 UI；"
                "登录引导新小玩法教学动画，高分一键分享。",
            ),
        ]
        # 反向插入以保持顺序
        cursor = anchor
        for style, text in blocks:
            cursor = insert_after(cursor, text, style=style)

    # ---------- 9.2 data 目录 ----------
    p = find_para(doc, lambda t: t.startswith("很多玩法数值不以硬编码写死"))
    if p and "DialoguePerformanceScripts" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。内容深度配置同目录新增：DialoguePerformanceScripts / DialogueVoiceConfig、"
            "CharacterConstellationConfig、PuzzleConfigs / CollectibleConfigs / ExplorationEvents、"
            "ElementReactionConfig、MiniGamePoolConfigs。",
        )

    # ---------- 十五 成熟度 ----------
    p = find_para(doc, lambda t: t.startswith("缺口补齐玩法：世界 BOSS"))
    if p and "命座" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；命座/星魂、剧情 Timeline 表演、探索谜题与隐藏收集、Raid 职责连携、MiniGame 跑酷/塔防/钓鱼（CmdId 1248–1255）。",
        )

    p = find_para(doc, lambda t: t.startswith("对话/过场/家园：服务端已加深"))
    if p and "1248" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；剧情表演 1248、命座点亮、谜题/裂隙、Raid READY_ROOM 与连携、MiniGame 教学推送需客户端继续对齐。",
        )

    p = find_para(doc, lambda t: "改 CmdId（含 1020–1045 闭环" in t)
    if p and "1248" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；内容深度缺口 1248–1255 必须与客户端同步。",
        )

    # ---------- 十六 短期建议 ----------
    p = find_para(doc, lambda t: t.startswith("继续以模块化单体打磨崩铁式体验"))
    if p and "命座" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；客户端优先对接剧情 Timeline/口型、命座星星、谜题机关特效、Raid 职责与连携、MiniGame 教学与高分分享。",
        )

    # ---------- 二十二 CI ----------
    p = find_para(doc, lambda t: t.startswith("定向单测：CmdId 唯一性"))
    if p and "ContentGap" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；内容深度：ContentGapComprehensiveFlowsTest / ContentGapFillFlowsTest。",
        )

    # ---------- 结语 ----------
    p = find_para(doc, lambda t: t.startswith("MyLunarCore 已经不是「只有空壳的演示仓库」"))
    if p and "五大内容深度缺口" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "。2026-09-04 起，服务端已补齐五大内容深度缺口：沉浸式剧情表演、命座养成、探索谜题与隐藏收集、"
            "Raid 团队职责与元素连携、非战斗 MiniGame 框架（CmdId 1248–1255），并形成可回归测试闭环。",
        )

    p = find_para(doc, lambda t: t.startswith("本报告基于仓库当前代码与文档快照整理"))
    if p and "1248–1255" not in p.text:
        replace_paragraph_text(
            p,
            p.text.strip().rstrip("。")
            + "；同日（2026年09月04日）已在本文件内同步「五大内容深度缺口」落地说明（见第七章 7.35）。"
            "请打开 Word 后右键目录「更新整个目录」以刷新页码。",
        )

    doc.save(str(DOC_PATH))
    print(f"updated: {DOC_PATH}")


if __name__ == "__main__":
    main()
