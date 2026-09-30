# -*- coding: utf-8 -*-
"""原地更新桌面《MyLunarCore项目各方面详细总结报告.bak.docx》，写入 wire v4 新功能说明。"""

from __future__ import annotations

from copy import deepcopy
from datetime import date
from pathlib import Path

from docx import Document
from docx.oxml.ns import qn
from docx.text.paragraph import Paragraph

DOC_PATH = Path(r"c:\Users\ASUS\Desktop") / (
    "MyLunarCore"
    + "\u9879\u76ee\u5404\u65b9\u9762\u8be6\u7ec6\u603b\u7ed3\u62a5\u544a"
    + ".bak.docx"
)


def set_run_font(run, size=None, bold=None):
    run.font.name = "微软雅黑"
    run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    if size is not None:
        run.font.size = size
    if bold is not None:
        run.bold = bold


def clear_and_set(paragraph: Paragraph, text: str, *, keep_style: bool = True):
    """清空段落 runs 后写入新文本，尽量保留原段落样式。"""
    style = paragraph.style
    p = paragraph._p
    for child in list(p):
        if child.tag == qn("w:r"):
            p.remove(child)
    run = paragraph.add_run(text)
    set_run_font(run, size=None, bold=None)
    if keep_style and style is not None:
        paragraph.style = style


def insert_paragraph_after(paragraph: Paragraph, text: str, style_name: str | None = None) -> Paragraph:
    new_p = deepcopy(paragraph._p)
    # 清空副本内容
    for child in list(new_p):
        if child.tag in (qn("w:r"), qn("w:hyperlink")):
            new_p.remove(child)
    paragraph._p.addnext(new_p)
    new_para = Paragraph(new_p, paragraph._parent)
    if style_name:
        try:
            new_para.style = style_name
        except KeyError:
            pass
    run = new_para.add_run(text)
    set_run_font(run)
    return new_para


def find_para_index(doc: Document, predicate) -> int:
    for i, p in enumerate(doc.paragraphs):
        if predicate(p):
            return i
    raise RuntimeError("paragraph not found")


def main():
    if not DOC_PATH.is_file():
        raise SystemExit(f"missing: {DOC_PATH}")

    doc = Document(str(DOC_PATH))
    today = date.today().strftime("%Y年%m月%d日")

    # ----- 封面副标题 -----
    for p in doc.paragraphs:
        t = p.text.strip()
        if t.startswith("（含技术架构、业务能力"):
            clear_and_set(
                p,
                "（含技术架构、业务能力、安全运维、微服务迁移、体验缺口补齐、体验闭环、体验沉浸、"
                "体验优化二期/三期、AI 助手增强、战斗手感与拥堵防雪崩、性能深化、运营热更七项短板，"
                "以及 wire v4 稳定性与体验深化：跨节点迁移 Saga、Zone 分裂、战斗预测修正、"
                "活动流程 DSL、资源差量、业务止血与 AI 主动陪伴等）",
            )
            break

    for p in doc.paragraphs:
        if p.text.strip().startswith("报告生成日期："):
            clear_and_set(
                p,
                f"报告生成日期：{today}（在既有性能深化 CmdId 1107、手感/拥堵 1105–1106、"
                "运营热更七项短板基础上，已同步落地「稳定性与体验深化」wire v4："
                "①跨节点组队迁移 Saga（MigrateState + PartyMigrateProgressScNotify 1109）与私聊 ACK/序列号；"
                "②动态 Zone 分裂 + Shard 分配 + ScenePreloadPush 软过渡；"
                "③战斗客户端预测修正 BattleCorrectionScNotify（1110）、Auto 弱网补偿与预输入扩容；"
                "④活动流程 DSL（ActivityFlowScNotify 1114）与泛用 UI 容器；"
                "⑤好友亲密度 / 公会多人讨伐 / 语音信令完善；"
                "⑥协议能力握手 enabled_cmd_ids（1120–1121）与资源差量断点续传（1117）；"
                "⑦业务大盘指标 + 自动止血 + JSON 结构化日志；"
                "⑧AI 画像向量 / 情感气泡 / 客户端特征匹配减负 VLM。"
                "回归：WireV4EnhancementBusinessFlowsTest + StabilityEnhancementFlowTest，37 用例通过。）",
            )
            break

    # ----- 8.4 协议版本 -----
    for p in doc.paragraphs:
        if "PROTOCOL_WIRE_VERSION = 3" in p.text or (
            p.text.startswith("为避免新旧客户端") and "PROTOCOL_WIRE_VERSION" in p.text
        ):
            clear_and_set(
                p,
                "为避免新旧客户端「各说各话」，工程引入了协议线版本概念。"
                "当前 PROTOCOL_WIRE_VERSION = 4（迁移进度/战斗修正/活动流程/资源差量等 CmdId 1109–1125）；"
                "ProtocolCompatService.isCompatible 仍接受 wire ≥ 2，拒绝过旧的 v1 客户端"
                "（v1 角色号段 120–129 会撞上组队）。"
                "登录后可通过 PROTOCOL_CAPABILITY（1120/1121）上报 supported_cmd_versions，"
                "服务端返回 enabled_cmd_ids；wire < 4 时屏蔽 1109+ 新命令，"
                "不支持命令回 UNSUPPORTED_CMD_SC_NOTIFY（1125，retcode=120）并提示 min_required_wire_version。"
                "角色相关命令号已迁到 160–173。"
                "体验闭环占用 1020–1045，沉浸 1046–1069，二期 1070–1076，三期 1078–1097，"
                "AI 增强 1098–1104，手感/拥堵 1105–1106，性能深化 1107，维护推送 1108，"
                "稳定性深化 1109–1125。改号段必须与客户端同步，并由 CmdIdUniquenessTest / WireVersionGuardTest / CI buf breaking 守卫。",
            )
            break

    # ----- 12.1 监控告警补充 -----
    for p in doc.paragraphs:
        if p.text.startswith("主工程通过 BusinessMetrics 暴露业务指标"):
            clear_and_set(
                p,
                "主工程通过 BusinessMetrics 暴露业务指标到 /actuator/prometheus。"
                "可观察登录失败、抽卡异常、战斗超时、匹配成功率，以及 AI 助手请求终态、延迟与缓存命中等；"
                "wire v4 起补充 IAP 验单成功/失败（lunarcore.iap.verify）、钱包负余额（lunarcore.wallet.negative）、"
                "战斗审计不一致（lunarcore.battle.audit）。"
                "OpsAutoMitigationService 在 IAP 失败率或审计不一致率 >5%/窗口时自动关闭高价值购买或进入只读止血，"
                "StructuredJsonLogger 统一输出含 traceId/spanId/userId/cmdId/costMs 的 JSON 日志，便于 Loki 检索。"
                "配套部署目录 deploy/prometheus、deploy/grafana，Compose 可一键拉起 Prometheus(:9090)与 Grafana(:3000)。",
            )
            break

    for p in doc.paragraphs:
        if p.text.strip().startswith("AI 建议初值："):
            # insert extra bullets after AI alert bullet by appending to next sibling area
            pass

    # 在 AI 建议初值后追加业务告警 bullet（找该段落后插入）
    for i, p in enumerate(doc.paragraphs):
        if p.text.strip().startswith("AI 建议初值："):
            insert_paragraph_after(
                p,
                "业务大盘建议初值：IAP 验单失败率 >5%/5m critical；战斗审计不一致率 >5%/5m critical；"
                "钱包负余额计数 >0/2m critical；超阈值由 OpsAutoMitigationService 自动止血。",
                "List Bullet",
            )
            break

    # ----- 15.1 已具备能力：追加一条 -----
    for i, p in enumerate(doc.paragraphs):
        if p.text.strip().startswith("prod 弱密钥与 IAP Mock"):
            insert_paragraph_after(
                p,
                "稳定性与体验深化（wire v4）：PartyMigrateSagaService / DynamicZoneSplitService / "
                "ShardAllocationService；BattleCorrectionService + BattleAutoWeakNetService；"
                "ActivityFlowDslService；FriendIntimacyService / GuildRaidInstanceService / VoiceSignalingService；"
                "ProtocolCompatService.negotiate + ResourcePatchService；BusinessMetrics 业务大盘与自动止血；"
                "AssistPlayerProfileVectorService / AssistEmotionNotifyService / VisualFeatureMatchService；"
                "回归 WireV4EnhancementBusinessFlowsTest（全流程）与 StabilityEnhancementFlowTest。",
                "List Bullet",
            )
            break

    # ----- 15.2 局限：更新 wire 表述 -----
    for p in doc.paragraphs:
        if "协议已迁到 wire v3" in p.text:
            clear_and_set(
                p,
                "协议已迁到 wire v4（兼容守卫仍 ≥ 2）；v1 客户端必须升级；"
                "改 CmdId（含 1020–1045 闭环、1046–1069 沉浸、1070–1076 二期、1078–1097 三期、"
                "1098–1104 AI、1105–1108 手感/拥堵/性能/维护、1109–1125 稳定性深化）必须与客户端同步。"
                "旧客户端可通过能力握手拿 enabled_cmd_ids，避免强行踢出导致口碑问题。",
            )
            break

    # ----- 16.1 / 16.2 建议更新 -----
    for p in doc.paragraphs:
        if p.text.strip().startswith("补齐战斗/抽卡等专用压测场景与关键集成测试"):
            clear_and_set(
                p,
                "补齐战斗/抽卡等专用压测场景与关键集成测试：CI 已增加 RUN_TESTCONTAINERS 门控的 "
                "MysqlRedisSmokeIT/CoreLoopContainersIT，以及 PR 侧 k6 回归烟雾；"
                "正式阈值门禁需回填 docs/load-test-baseline.md 后收紧。",
            )
            break

    for p in doc.paragraphs:
        if p.text.strip().startswith("完善大厅社交与匹配体验，明确跨节点能力边界"):
            clear_and_set(
                p,
                "完善大厅社交与匹配体验：跨节点组队已具备 Saga 回滚与迁移进度推送，"
                "在线私聊已具备 ACK+重试与序列号；继续压测验证 Center remote + 共享 Redis 切服一致性，"
                "并完善公会多人讨伐与实时语音第三方 RTC 对接。",
            )
            break

    # ----- 22.2 CI -----
    for p in doc.paragraphs:
        if p.text.strip().startswith("定向单测：CmdId 唯一性"):
            clear_and_set(
                p,
                "定向单测：CmdId 唯一性/完整性、WireVersion 守卫、公会战、切服、抽卡、匹配、热更、PublishDrill，"
                "以及 StabilityEnhancementFlowTest / WireV4EnhancementBusinessFlowsTest 等稳定性深化回归。",
            )
            break

    for p in doc.paragraphs:
        if p.text.strip().startswith("夜间压测烟雾：k6"):
            clear_and_set(
                p,
                "夜间压测烟雾：k6（低并发 health 场景）；提交信息含 [loadtest] 也可触发；"
                "PR 另有 pr-perf-regression 烟雾任务，基线回填后可改为硬门禁。",
            )
            break

    # ----- 在 7.28 之后、第八章之前插入 7.30 -----
    idx_728 = find_para_index(
        doc, lambda p: p.style and p.style.name.startswith("Heading") and "7.28" in p.text
    )
    # 找到第八章标题
    idx_8 = find_para_index(
        doc, lambda p: p.style and p.style.name == "Heading 1" and p.text.strip().startswith("八、")
    )
    # 在第八章前一段落后插入：先定位 idx_8-1 的段落作为锚点，在其后再插会乱序；
    # 正确做法：在 idx_8 段落之前插入，即在 paragraphs[idx_8]._p 前插入。
    anchor = doc.paragraphs[idx_8]

    section_blocks = [
        (
            "Heading 2",
            "7.30 稳定性与体验深化（wire v4 / CmdId 1109–1125）",
        ),
        (
            "Normal",
            "在 7.27–7.29 手感、拥堵、性能与运营热更短板补齐之后，本轮针对「高并发多节点稳定性、战斗高延迟手感、"
            "活动生命周期、社交深度、协议/资源发布风险、生产可观测止血、AI 主动陪伴、CI 质量」八个方向落地服务端能力。"
            "协议线升至 PROTOCOL_WIRE_VERSION = 4；新增号段 1109–1125（推送与确认为主，成对规则仍遵循 CS_REQ=N、SC_RSP=N+1）。"
            "客户端需在登录后走 PROTOCOL_CAPABILITY 协商 enabled_cmd_ids，并对新 Notify 做泛用容器渲染。",
        ),
        (
            "Normal",
            "（1）跨节点组队与私聊：MigrateState 状态机 INIT→SERIALIZING→TRANSFERRING→CONFIRMED/ROLLBACK；"
            "PartyMigrateSagaService 任一节点失败补偿回滚；PartyFollowMigrationService 票据可 Redis 共享；"
            "PartyMigrateProgressScNotify（1109）下发进度。ReliablePrivateChatDelivery 为在线跨服私聊提供序列号、ACK（1118–1119）与超时重试。"
            "（2）场景弹性：DynamicZoneSplitService 在活跃人数超阈值（默认 250）时创建 Zone-{id}-split-{ts} 并按 UID 哈希迁移；"
            "ShardAllocationService 登录按负载与地域选最优分线；软过渡复用 ScenePreloadPushScNotify（1028）。"
            "（3）战斗手感：FightActionCsReq.client_estimated_time_ms 参与确定性对齐，偏差超阈推 BattleCorrectionScNotify（1110）；"
            "预输入缓冲扩至 5 并带 TTL；Auto 弱网下等待窗可拉长至 1800ms，支持 BattleActionConfirm（1111–1112）与 Tick 进度（1113）。"
            "（4）活动与社交：ActivityFlowDslService 支持 STEP_START→PLAY→SETTLE→REWARD 并推 ActivityFlowScNotify（1114）；"
            "FriendIntimacyService / GuildRaidInstanceService（10–20 人同屏）/ VoiceSignalingService 完善信令与过期清理。"
            "（5）发布一致性：资源差量 ResourcePatchScNotify（1117）含 MD5/URL/resumeOffset，允许更新期保持登录；"
            "Manifest 支持 softPatchAllowed 软锁。"
            "（6）运维：IAP/钱包/战斗审计指标 + Prometheus 告警规则；OpsAutoMitigationService 自动止血；StructuredJsonLogger。"
            "（7）AI：画像向量检索个性化建议；AssistEmotionScNotify（1116）调气泡色；VisualFeatureMatchService 优先本地特征匹配，远程 VLM 日限额。"
            "（8）工程：@ProtoCommand 注解驱动文档；CI Testcontainers 门控与 PR k6 烟雾。",
        ),
        (
            "List Bullet",
            "实现入口：party/MigrateState、PartyMigrateSagaService、PartyFollowMigrationService；"
            "hall/ReliablePrivateChatDelivery；scene/DynamicZoneSplitService、ShardAllocationService；"
            "battle/BattleCorrectionService、BattleAutoWeakNetService、PlayerInputBufferService；"
            "activity/ActivityFlowDslService；social/FriendIntimacyService、VoiceSignalingService；"
            "guild/GuildRaidInstanceService；net/ProtocolCompatService、ProtoCommand；"
            "common/ResourcePatchService、BusinessMetrics、StructuredJsonLogger；"
            "ops/OpsAutoMitigationService；assist/memory/AssistPlayerProfileVectorService、"
            "AssistEmotionNotifyService、assist/visual/VisualFeatureMatchService。",
        ),
        (
            "List Bullet",
            "测试入口：WireV4EnhancementBusinessFlowsTest（10 组业务流程全覆盖）、"
            "StabilityEnhancementFlowTest；回归 ExperienceOptV2FlowsTest、CmdIdUniquenessTest、"
            "WireVersionGuardTest、VoiceSignalingServiceTest。近期全量定向回归 37 通过 / 0 失败。",
        ),
        (
            "List Bullet",
            "客户端联调要点：上报 wire_version≥4 与 PROTOCOL_CAPABILITY；处理 1109/1110/1113/1114/1115/1116/1117/1123/1124 等 Notify；"
            "战斗技能包带 client_estimated_time_ms；资源落后走差量清单而非强制踢线；Auto 弱网显示「网络同步中」。",
        ),
    ]

    # 从后往前插到第八章标题之前，保持正序
    first_inserted = None
    for style_name, text in reversed(section_blocks):
        # 在 anchor 前插入：用 anchor 的前一个兄弟……python-docx 无直接 insert_before，
        # 采用：在 anchor 前一个段落 after 插入；若无则 deepcopy 到 anchor 前。
        prev = anchor._p.getprevious()
        if prev is None:
            # 极少情况：直接插在 body 开头附近
            new_p = deepcopy(anchor._p)
            for child in list(new_p):
                if child.tag in (qn("w:r"), qn("w:hyperlink")):
                    new_p.remove(child)
            anchor._p.addprevious(new_p)
            para = Paragraph(new_p, anchor._parent)
        else:
            # 构造空段落后 addnext 到 prev
            # 找 Paragraph 包装：遍历找 _p is prev
            prev_para = None
            for pp in doc.paragraphs:
                if pp._p is prev:
                    prev_para = pp
                    break
            if prev_para is None:
                new_p = deepcopy(anchor._p)
                for child in list(new_p):
                    if child.tag in (qn("w:r"), qn("w:hyperlink")):
                        new_p.remove(child)
                anchor._p.addprevious(new_p)
                para = Paragraph(new_p, anchor._parent)
            else:
                para = insert_paragraph_after(prev_para, "", style_name)
                # insert_paragraph_after 已写入空 run，清掉再写
                for child in list(para._p):
                    if child.tag == qn("w:r"):
                        para._p.remove(child)
        try:
            para.style = style_name
        except KeyError:
            pass
        run = para.add_run(text)
        set_run_font(run, bold=(style_name == "Heading 2"))
        first_inserted = para

    # ----- 结语附近若有日期/版本句，可略 -----
    doc.save(str(DOC_PATH))
    print(f"updated: {DOC_PATH}")


if __name__ == "__main__":
    main()
