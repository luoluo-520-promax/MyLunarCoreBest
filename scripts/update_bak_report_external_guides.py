# -*- coding: utf-8 -*-
"""将外链攻略（B站/抖音/官网）同步进桌面原 bak 总结报告，覆盖保存原文件。"""

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


def patch_table_cell_contains(table, contains, new_text=None, col=0, append=None):
    for row in table.rows:
        if contains in row.cells[col].text:
            if new_text is not None:
                set_cell_text(row.cells[col], new_text)
            if append:
                target = row.cells[-1] if append[0] is None else row.cells[append[0]]
                cur = target.text.strip()
                extra = append[1]
                if extra not in cur:
                    set_cell_text(target, cur.rstrip("。") + extra)
            return True
    return False


def main():
    if not DOC_PATH.exists():
        raise SystemExit(f"文档不存在: {DOC_PATH}")

    doc = Document(str(DOC_PATH))
    n = 0

    # ---------- 十、引言：处理链路纳入外链 ----------
    p = find_para(doc, startswith="AI 模块的定位是「游戏内向导」")
    if p:
        replace_paragraph_text(
            p,
            "AI 模块的定位是「游戏内向导」，不是替代游戏逻辑的裁判。默认启用规则教练（按配置提示），"
            "本地大模型与远程 sidecar 默认关闭。处理链路大致是：安全分类 → 本地规则/FAQ/缓存快路径 → "
            "拼装玩家上下文与多轮历史 → 可选远程/本地 LLM → 出站清洗、合规声明、缓存、审计、配额与埋点；"
            "当玩家问攻略/打法/教程/视频时，再附加主流平台外链（B站、抖音、官方网站）的视频或搜索链接。"
            "近期已按「对话体验 / 业务联动 / 架构性能 / 多语言运维 / 合规」五条线补强，下列小节说明当前能力边界与实现入口。",
        )
        n += 1

    # ---------- 10.1 外链两条：写清三平台与业务流程 ----------
    p = find_para(doc, startswith="主流平台外链/视频攻略")
    if p:
        replace_paragraph_text(
            p,
            "主流平台外链/视频攻略（B站、抖音、官方网站）：玩家问「攻略/打法/教程/视频/官网」时，"
            "服务端从 data/ExternalGuideCatalog.json 先匹配运营精选条目（curated），不足则按三平台搜索模板"
            "生成可打开的深链；回答正文附链接摘要，协议 AskAiAssistScRsp / AiHintNotify 下发 media_links"
            "（OPEN_VIDEO / OPEN_EXTERNAL_LINK）。仅允许 https 白名单域名（bilibili / douyin / mihoyo 等），"
            "不做第三方页面抓取，避免把不可控内容直接灌进游戏内。客户端用卡片或浏览器/WebView 打开链接。",
        )
        n += 1

    p = find_para(doc, startswith="配置与热更：lunarcore.ai-assist.external-guide-enabled")
    if p:
        replace_paragraph_text(
            p,
            "配置与热更：开关 lunarcore.ai-assist.external-guide-enabled（默认开）；"
            "目录文件 lunarcore.ai-assist.external-guide-catalog-resource（默认 ExternalGuideCatalog.json）。"
            "HotReloadCoordinator 与 GuidePack 一并加载/失败回滚。运营改 platforms（B站/抖音/官网检索模板）"
            "或 guides（真实合作视频、官网公告 URL）后热更即可，无需发版。",
        )
        n += 1

    # ---------- 10.2 / 10.3 / 10.4 类名、协议、配置 ----------
    p = find_para(doc, exact="主工程：AiAssistApplicationService、CoachRuleEngine、AssistSafetyFilter、AiAssistClient（含熔断）")
    if p:
        replace_paragraph_text(
            p,
            "主工程：AiAssistApplicationService、CoachRuleEngine、AssistSafetyFilter、AiAssistClient（含熔断）；"
            "外链攻略：ExternalGuideSearchService、ExternalGuideCatalogRepository、AssistMediaLink",
        )
        n += 1

    p = find_para(doc, startswith="协议扩展：AskAiAssist 支持 session_id/locale")
    if p:
        replace_paragraph_text(
            p,
            "协议扩展：AskAiAssist 支持 session_id/locale；响应与推送带 disclaimer/strategy_version；"
            "AskAiAssistScRsp.media_links / AiHintNotify.media_links 下发外链（id/title/platform/"
            "media_type/url/action/curated），platform 取 bilibili、douyin、official。",
        )
        n += 1

    p = find_para(doc, startswith="关键配置见 lunarcore.ai-assist.*")
    if p:
        replace_paragraph_text(
            p,
            "关键配置见 lunarcore.ai-assist.*（如 conversation-history-*、free/vip-daily-llm-budget、"
            "remote-circuit-*、strategy-version / gray-strategy-*、env-key-prefix、compliance-disclaimer、"
            "external-guide-enabled、external-guide-catalog-resource 等）。",
        )
        n += 1

    p = find_para(doc, contains="内容素材来自 GuidePack")
    if p and "B站" not in p.text:
        replace_paragraph_text(
            p,
            "入站安全在关键词/正则（AssistSafetyRules）之外增加轻量语义意图分类，降低绕过词表变体的风险；"
            "出站仍做敏感句清洗。送入 LLM 前对 UID/手机号/邮箱等做 PII 脱敏（AssistPiiRedactor）；"
            "审计日志使用 uidHash，默认不落完整隐私原文。配额按教练/LLM 分桶，并支持等级/VIP 差异化日预算与"
            "「同一问题」短时限流。内容素材来自 GuidePack、CoachTips、AssistFeatureContent 与"
            "ExternalGuideCatalog.json（B站/抖音/官方网站白名单外链）；热更时可增量刷新 RAG。"
            "记住：AI 给出的是建议，真正扣费、开战、发奖仍由游戏权威逻辑决定；外链来自第三方平台，须提醒玩家甄别。",
        )
        n += 1

    # ---------- 10.5 业务流程专节（幂等：已有标题则跳过插入） ----------
    if find_para(doc, contains="10.5 主流平台外链") is None:
        anchor = find_para(doc, startswith="关键配置见 lunarcore.ai-assist.*")
        if anchor is None:
            anchor = find_para(doc, startswith="BusinessMetrics 增加 AI 专项指标")
        if anchor is None:
            raise SystemExit("未找到 10.5 插入锚点")
        h = insert_paragraph_after(anchor, "10.5 主流平台外链/视频攻略业务流程", "Heading 2")
        p1 = insert_paragraph_after(
            h,
            "这是本轮新增的「建议面」能力：助手不仅给文字攻略，还能把 B站、抖音、官方网站上的视频或页面链接交给玩家。"
            "设计原则是「白名单深链，不抓取页面」——服务端只返回可打开的 URL，内容仍在原平台播放/阅读。",
            "Normal",
        )
        p2 = insert_paragraph_after(
            p1,
            "玩家侧入口与现有助手相同：聊天 @助手、AskAiAssist（Cmd 902）、异步 AiHintNotify。"
            "问句含「攻略/打法/通关/教程/视频/官网/B站/抖音」等意图词时触发。流程为："
            "① 安全分类与本地 FAQ/规则快路径照常执行；② ExternalGuideSearchService 读取热更目录；"
            "③ 按关键词/场景给精选条目打分，命中则返回 curated=true 的视频或文章链接；"
            "④ 精选不足时按 platforms 模板补齐 B站搜索、抖音搜索、官网首页；"
            "⑤ 域名必须在 allowedHosts 内且为 http(s)，否则丢弃；⑥ 正文追加「相关攻略」摘要，"
            "cited_config_ids 增加 ext-guide:{id}；⑦ 客户端按 action 打开外链。",
            "Normal",
        )
        p3 = insert_paragraph_after(
            p2,
            "三平台约定：bilibili → search.bilibili.com 检索；douyin → www.douyin.com/search/{query}；"
            "official → 默认 https://sr.mihoyo.com/（运营可改为真实官网/公告页）。"
            "开关关闭（external-guide-enabled=false）或目录 enabled=false 时不附带外链。"
            "非攻略问句（如「今天做什么日常」）不会硬塞链接。热更失败时仓库 restore 旧快照，与 GuidePack 同一套协调器。",
            "Normal",
        )
        insert_paragraph_after(
            p3,
            "测试入口：ExternalGuideBusinessFlowTest（目录检索 → ask 主链路 → 协议 media_links → 热更回滚）、"
            "ExternalGuideSearchServiceTest。策划手册见 docs/planner-config-guide.md 中 ExternalGuideCatalog.json 一行。",
            "List Bullet",
        )
        n += 1

    # ---------- 热更说明 ----------
    p = find_para(doc, startswith="可以把热更想成")
    if p and "ExternalGuideCatalog" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "。助手外链目录 ExternalGuideCatalog.json 已纳入同一协调器，与 GuidePack/CoachTips 一起加载或回滚。",
        )
        n += 1

    p = find_para(doc, exact="核心类：HotReloadCoordinator、HotfixDataService、ConfigImportService、ConfigPublishAuditService、ConfigReleaseService")
    if p and "ExternalGuideCatalogRepository" not in p.text:
        replace_paragraph_text(
            p,
            p.text + "；AI 外链：ExternalGuideCatalogRepository",
        )
        n += 1

    # ---------- 玩家一天 / 成熟度 / 结语 ----------
    p = find_para(doc, startswith="为了让非技术同学建立整体画面")
    if p and "B站" not in p.text:
        replace_paragraph_text(
            p,
            p.text.replace(
                "必要时问 AI 助手",
                "必要时问 AI 助手（可拿到 B站/抖音/官网攻略链接）",
            ),
        )
        n += 1

    p = find_para(doc, startswith="Admin 热更/导入/审计")
    if p and "外链攻略" not in p.text:
        replace_paragraph_text(
            p,
            p.text.rstrip("。")
            + "；AI 外链攻略（B站/抖音/官网 media_links）。",
        )
        n += 1

    p = find_para(doc, startswith="MyLunarCore 已经不是「只有空壳的演示仓库」")
    if p and "B站" not in p.text:
        replace_paragraph_text(
            p,
            p.text.replace(
                "熔断灰度、合规声明与专项指标）。",
                "熔断灰度、合规声明、专项指标，以及主流平台外链/视频攻略（B站、抖音、官方网站）。",
            ),
        )
        n += 1

    # ---------- 表格 ----------
    t6 = find_table_by_header(doc, "文件/目录", "用途")
    if t6:
        if not patch_table_cell_contains(
            t6,
            "GuidePack",
            new_text="GuidePack / CoachTips / AssistSafetyRules / AssistFeatureContent / ExternalGuideCatalog.json",
        ):
            add_table_row(t6, [
                "ExternalGuideCatalog.json",
                "AI 外链攻略目录：B站/抖音/官方网站白名单与精选 URL",
            ])
        n += 1

    t3 = find_table_by_header(doc, "包名", "普通人理解")
    if t3:
        patch_table_cell_contains(
            t3,
            "assist",
            append=(2, "；外链攻略（B站/抖音/官网 media_links）"),
        )
        n += 1

    t7 = find_table_by_header(doc, "能力面", "代表类")
    if t7 and not table_has(t7, "ExternalGuide"):
        add_table_row(t7, [
            "外链/视频攻略",
            "ExternalGuideSearchService / ExternalGuideCatalog.json",
            "B站、抖音、官网白名单链接；AskAiAssist 下发 media_links",
        ])
        n += 1

    t15 = find_table_by_header(doc, "配置", "含义")
    if t15:
        if not table_has(t15, "external-guide-enabled"):
            add_table_row(t15, [
                "lunarcore.ai-assist.external-guide-enabled",
                "是否在攻略意图问答中附带 B站/抖音/官网外链（默认 true）",
            ])
        if not table_has(t15, "external-guide-catalog-resource"):
            add_table_row(t15, [
                "lunarcore.ai-assist.external-guide-catalog-resource",
                "外链目录文件，默认 ExternalGuideCatalog.json",
            ])
        n += 1

    t17 = find_table_by_header(doc, "术语", "人话解释")
    if t17:
        if not table_has(t17, "media_links"):
            add_table_row(t17, [
                "media_links",
                "助手回答里附带的外链列表：标题、平台（B站/抖音/官网）、视频或网页 URL，客户端点开即可",
            ])
        if not table_has(t17, "ExternalGuideCatalog"):
            add_table_row(t17, [
                "ExternalGuideCatalog",
                "运营维护的外链攻略说明书：白名单域名、三平台搜索模板、精选视频/官网条目，可热更",
            ])
        n += 1

    t18 = find_table_by_header(doc, "别人常问", "你可以这样答")
    if t18 and not table_has(t18, "攻略视频"):
        add_table_row(t18, [
            "AI 能给攻略视频吗？",
            "能。问「攻略/打法/视频」时会返回 B站、抖音或官方网站链接（白名单），不抓取第三方页面；内容以游戏内官方说明为准。",
        ])
        n += 1

    t5 = find_table_by_header(doc, "号段", "系统")
    if t5:
        patch_table_cell_contains(
            t5,
            "900",
            append=(1, "（含 media_links 外链攻略）"),
        )
        n += 1

    doc.save(str(DOC_PATH))
    print(f"已原地更新原文档: {DOC_PATH}  触及块数≈{n}")


if __name__ == "__main__":
    main()
