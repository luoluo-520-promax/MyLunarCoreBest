# -*- coding: utf-8 -*-
"""将本轮「十大生产/体验优化」落地同步进桌面原报告（仅原地修订 .bak.docx）。

目标：Desktop/MyLunarCore项目各方面详细总结报告.bak.docx
不另存新文档。幂等标记：正文出现 HotWalletCacheService 或 TenGapsBusinessE2EFlowTest。
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

IDEMPOTENT = "HotWalletCacheService"


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


def find_para(doc, exact=None, startswith=None, contains=None, heading_only=False):
    for p in doc.paragraphs:
        t = p.text.strip()
        if heading_only and not (p.style and p.style.name.startswith("Heading")):
            continue
        if exact is not None and t == exact:
            return p
        if startswith is not None and t.startswith(startswith):
            return p
        if contains is not None and contains in t:
            return p
    return None


def replace_if(doc, *, exact=None, startswith=None, contains=None, new_text=None, heading_only=False):
    p = find_para(doc, exact=exact, startswith=startswith, contains=contains, heading_only=heading_only)
    if p is not None and new_text is not None:
        replace_paragraph_text(p, new_text)
        return True
    return False


def set_cell_text(cell, text):
    cell.text = text
    for para in cell.paragraphs:
        for r in para.runs:
            set_run_font(r, size=10)


def append_table_row(table, values):
    row = table.add_row()
    for i, val in enumerate(values):
        if i < len(row.cells):
            set_cell_text(row.cells[i], val)


def table_has(table, needle, col=0):
    for row in table.rows:
        if needle in row.cells[col].text:
            return True
    return False


def patch_table_cell(table, old_contains=None, col=0, new_other=None, new_text=None):
    for row in table.rows:
        cur = row.cells[col].text.strip()
        if old_contains is None or old_contains not in cur:
            continue
        if new_text is not None:
            set_cell_text(row.cells[col], new_text)
        if new_other is not None:
            for ci, val in new_other.items():
                set_cell_text(row.cells[ci], val)
        return True
    return False


def main():
    if not DOC_PATH.exists():
        raise SystemExit(f"文档不存在: {DOC_PATH}")

    doc = Document(str(DOC_PATH))
    if find_para(doc, contains=IDEMPOTENT) is not None:
        print(f"Already patched ({IDEMPOTENT} present): {DOC_PATH}")
        return

    today = f"{date.today().year}年{date.today().month:02d}月{date.today().day:02d}日"
    n = 0

    # ---------- 封面副标题 ----------
    p_cover = find_para(doc, startswith="（含技术架构、业务能力、安全运维")
    if p_cover is not None and "HotWallet" not in p_cover.text and "生产优化落地" not in p_cover.text:
        replace_paragraph_text(
            p_cover,
            p_cover.text.rstrip("）")
            + "；以及「十大生产/体验优化」落地："
            "热点钱包二级缓存与读写分离/分片规划、组队迁移超时熔断与进度 UI、"
            "资源增量补丁断点续传与强制升级窗口、轻量帧同步与触控/震动精细化、"
            "HPA/SLO 错误预算与自动降级、配置多人审批与 Git 回退、"
            "本地 RL 多模态战斗建议、设备 QualityPreset、"
            "OpenTelemetry/Tempo 与 RCA、混沌/E2E/k6 门禁）",
        )
        n += 1

    # ---------- 报告日期 ----------
    p_date = find_para(doc, startswith="报告生成日期：")
    if p_date is not None and IDEMPOTENT not in p_date.text:
        replace_paragraph_text(
            p_date,
            f"报告生成日期：{today}"
            "（在「十大体验/生产缺口」CmdId 1130–1142 基础上，已同步落地「十大生产/体验优化」："
            "①HotWalletCacheService 双写+延迟双删 + ReadOnlyRoutingAspect + TableShardRouter/"
            "sharding-sphere-player.yaml；"
            "②MigrateFallbackService 5s 超时/失败率>1% 熔断 + 1109 进度文案；"
            "③ResourcePatchManifestService resumeOffset/bsdiff + force_upgrade_deadline；"
            "④BattleLockstepService 帧号广播 + UiAdapt 摇杆死区/滑动取消 + 技能震动波形；"
            "⑤BattlePoolMetricsReporter / SloErrorBudgetService / OpsAutoMitigation AI fallback；"
            "⑥SensitiveOp 至少 2 人审批 + 抽卡概率 executive + ConfigGitVersionController；"
            "⑦LocalRlPolicyService + AssistBattleRlAdvisor + battle_context_json；"
            "⑧QualityPresetService + DevicePerfProbeService 下发 preset/阴影/粒子；"
            "⑨NetTraceContext 业务标签/traceparent + TraceRootCauseAnalyzer + deploy/otel；"
            "⑩TenGapsBusinessE2EFlowTest / 混沌 runbook / CI+k6 门禁。"
            "定向回归 TenGapsBusinessE2EFlowTest 等合计 216 通过 / 0 失败。）",
        )
        n += 1

    # ---------- 报告依据 ----------
    p_basis = find_para(doc, startswith="报告依据：当前仓库 README")
    if p_basis is not None and "HotWalletCacheService" not in p_basis.text:
        replace_paragraph_text(
            p_basis,
            p_basis.text.strip()
            + " 本轮再把「十大生产/体验优化」写入对应章节（热点缓存/读写分离/分片、迁移熔断、"
            "资源补丁与强制升级、Lockstep、HPA/SLO、配置 Git 审批、LocalRL、QualityPreset、"
            "OTel/RCA、TenGapsBusinessE2EFlowTest）。",
        )
        n += 1

    # ---------- 目录提示 ----------
    p_toc = find_para(doc, startswith="下面先给出章节速览")
    if p_toc is not None and "7.32" not in p_toc.text:
        replace_paragraph_text(
            p_toc,
            p_toc.text.strip()
            + " 第七章已新增 7.32「十大生产/体验优化落地（热点缓存·迁移熔断·Lockstep·SLO·LocalRL·OTel）」。",
        )
        n += 1

    # ---------- 7.4 战斗：帧同步 / 震动 ----------
    p_buf = find_para(doc, contains="PlayerInputBufferService")
    if p_buf is not None and "BattleLockstepService" not in p_buf.text:
        replace_paragraph_text(
            p_buf,
            p_buf.text.strip()
            + "；轻量帧同步 BattleLockstepService（关键技能/受击经 1113 广播 frameNo，移动仍状态同步）；"
            "DeviceHapticsConfigService.pickWaveformBySkill 区分战技/终结技/受击并按 iOS/Android 偏移。",
        )
        n += 1

    # ---------- 7.7 钱包热点缓存 ----------
    p_wallet = find_para(doc, startswith="商店购买会变更钱包并推送货币变化")
    if p_wallet is not None and "HotWalletCacheService" not in p_wallet.text:
        replace_paragraph_text(
            p_wallet,
            p_wallet.text.strip()
            + " 读路径可走 HotWalletCacheService（本地 Caffeine + Redis 二级缓存）；"
            "写成功后双写并延迟双删保证最终一致；"
            "@Transactional(readOnly=true) 经 ReadOnlyRoutingAspect 强制走从库；"
            "大表水平分片规划见 TableShardRouter / sharding-sphere-player.yaml（16 库×64 表）。",
        )
        n += 1

    p_wal_entry = find_para(doc, contains="WalletTccSagaService")
    if p_wal_entry is not None and "HotWalletCacheService" not in p_wal_entry.text and "入口：" in p_wal_entry.text:
        replace_paragraph_text(
            p_wal_entry,
            p_wal_entry.text.strip()
            + "、HotWalletCacheService；infra/db/ReadOnlyRoutingAspect、TableShardRouter",
        )
        n += 1

    # ---------- 7.11 组队迁移降级 ----------
    p_party = find_para(doc, startswith="组队：party.PartyService")
    if p_party is not None and "MigrateFallbackService" not in p_party.text:
        replace_paragraph_text(
            p_party,
            p_party.text.strip()
            + "；MigrateFallbackService：迁移最大超时 5s 自动回滚并提示客户端重试，"
            "失败率>1% 熔断拒绝新迁移；1109 推送 uiHint「正在迁移队伍数据 xx%」。",
        )
        n += 1

    # ---------- 7.13 / 设置触控 ----------
    p_set = find_para(doc, contains="UiAdaptConfigService")
    if p_set is not None and "joystickDeadzone" not in p_set.text:
        replace_paragraph_text(
            p_set,
            p_set.text.strip()
            + " 本轮细化：joystickDeadzone / skillPressThresholdMs / swipeCancelSkill；"
            "QualityPresetService 按 DeviceProfile 下发低/中/高/极高渲染档。",
        )
        n += 1

    # ---------- 8.4 协议：强制升级 / 补丁 ----------
    p_wire = find_para(doc, startswith="为避免新旧客户端")
    if p_wire is not None and "force_upgrade_deadline" not in p_wire.text:
        replace_paragraph_text(
            p_wire,
            p_wire.text.strip()
            + " LoginScRsp 增加 force_upgrade_deadline；逾期未升级返回 ERR_FORCE_UPGRADE=12 并跳转商店；"
            "ResourcePatchScNotify（1117）支持 resumeOffset + bsdiff/xdelta 差异块"
            "（ResourcePatchManifestService / data/resource_patch_manifest.json）。",
        )
        n += 1

    # ---------- 9.1 存储：读写分离 / 分片 ----------
    p_mysql = find_para(doc, startswith="账号、角色、背包、钱包等重要权威数据")
    if p_mysql is not None and "ReadOnlyRoutingAspect" not in p_mysql.text:
        replace_paragraph_text(
            p_mysql,
            p_mysql.text.strip()
            + " 读写分离：lunarcore.datasource.routing-enabled 开启后查询走 REPLICA、写走 PRIMARY"
            "（ReadWriteRouter / ReadOnlyRoutingAspect）；"
            "player_wallet / gacha_history 水平分片规划 16×64，见 sharding-sphere-player.yaml。",
        )
        n += 1

    # ---------- 10 AI：LocalRL ----------
    p_ai = find_para(doc, startswith="10.6") or find_para(doc, contains="AI 助手增强一期")
    # find a body near AI enhancement
    p_ai_body = find_para(doc, contains="AssistStrategyGrayService")
    if p_ai_body is not None and "LocalRlPolicyService" not in p_ai_body.text:
        replace_paragraph_text(
            p_ai_body,
            p_ai_body.text.strip()
            + "；本轮新增 LocalRlPolicyService（本地启发式/可替换 DQN，目标 <50ms）与"
            " AssistBattleRlAdvisor：AskAiAssist 携带 battle_context_json"
            "（队伍/敌方/回合/Buff）生成定制化建议，反馈可回写样本。",
        )
        n += 1
    else:
        p_ai6 = find_para(doc, contains="AskAiAssist")
        if p_ai6 is not None and "LocalRlPolicyService" not in p_ai6.text and "战斗场景" in p_ai6.text:
            replace_paragraph_text(
                p_ai6,
                p_ai6.text.strip()
                + " 战斗建议可走 LocalRlPolicyService + AssistBattleRlAdvisor（battle_context_json）。",
            )
            n += 1

    # ---------- 11 Admin：多人审批 / Git ----------
    p_admin = find_para(doc, startswith="管理后台走 HTTP")
    if p_admin is not None and "ConfigGitVersionController" not in p_admin.text:
        replace_paragraph_text(
            p_admin,
            p_admin.text.strip()
            + " 配置变更默认至少 2 人批准；抽卡概率等超敏操作需 role=executive；"
            "ConfigGitVersionController 支持 data/ Git 提交关联 config_release_id 与一键回退触发灰度评估。",
        )
        n += 1

    # ---------- 12 可观测性 ----------
    p_metrics = find_para(doc, startswith="主工程通过 BusinessMetrics")
    if p_metrics is not None and "SloErrorBudgetService" not in p_metrics.text:
        replace_paragraph_text(
            p_metrics,
            p_metrics.text.strip()
            + " BattlePoolMetricsReporter 每 2s 写入 battle_pool_usage；"
            "SloErrorBudgetService 按月 99.99% 错误预算，燃烧过快则关闭 AI 视觉/降日志；"
            "ai_fallback_rate>5% 触发 OpsAutoMitigation 自动降级；"
            "告警见 business-alerts.yml（BattlePoolUsage / SloErrorBudgetBurn）；"
            "HPA 清单 deploy/k8s/hpa-lunarcore-game.yaml。",
        )
        n += 1

    for p in doc.paragraphs:
        t = p.text.strip()
        if "LocalTxLogService" in t and "TraceRootCauseAnalyzer" not in t:
            replace_paragraph_text(
                p,
                t
                + " 排障：NetTraceContext 附加 cmdId/zoneId/battleId 与 W3C traceparent；"
                "TraceRootCauseAnalyzer 在告警时汇总慢 SQL/Redis/连接池信号；"
                "可选部署 deploy/otel（OTLP→Tempo）。",
            )
            n += 1
            break

    # ---------- 插入 7.32（锚在 7.31 末条客户端联调要点） ----------
    p_anchor = None
    for p in doc.paragraphs:
        t = p.text.strip()
        if t.startswith("客户端联调要点：优先消费 P0") and "CancelWindow" in t:
            p_anchor = p
            break
    if p_anchor is None:
        # fallback: last bullet under 7.31
        for p in doc.paragraphs:
            t = p.text.strip()
            if "docs/client-integration-priority.md" in t and "PresentationMockClient" in t:
                p_anchor = p
    if p_anchor is not None and find_para(doc, contains="7.32 十大生产", heading_only=True) is None:
        # 倒序插入：先末段再标题
        insert_paragraph_after(
            p_anchor,
            "测试入口：TenGapsOptimizationFlowTest、TenGapsBusinessE2EFlowTest（热点缓存/迁移熔断/"
            "补丁续传/Lockstep/SLO/审批/LocalRL/QualityPreset/RCA 全链路）；"
            "关联回归 ExperienceGapE2EFlowTest、WireV4EnhancementBusinessFlowsTest、"
            "WalletApplicationServiceTest、AssistEnhancementBusinessFlowTest 等；"
            "CI 已纳入 TenGaps*；混沌清单 deploy/chaos/party-migrate-chaos-runbook.md；"
            "定向套件合计 216 通过 / 0 失败。",
            style_name="List Bullet",
        )
        insert_paragraph_after(
            p_anchor,
            "实现入口：economy/HotWalletCacheService；infra/db/ReadOnlyRoutingAspect、"
            "TableShardRouter、sharding-sphere-player.yaml；"
            "party/MigrateFallbackService、PartyMigrateSagaService；"
            "common/ResourcePatchManifestService、ResourcePatchService；"
            "player/ClientVersionGateService（force_upgrade_deadline）；"
            "battle/BattleLockstepService、BattlePoolMetricsReporter；"
            "settings/UiAdaptConfigService、QualityPresetService、DeviceHapticsConfigService；"
            "ops/SloErrorBudgetService、OpsAutoMitigationService、TraceRootCauseAnalyzer；"
            "admin/SensitiveOpApprovalService、ConfigGitVersionController；"
            "assist/rl/LocalRlPolicyService、AssistBattleRlAdvisor；"
            "scene/DevicePerfProbeService；net/NetTraceContext；"
            "deploy/k8s/hpa-lunarcore-game.yaml、deploy/otel/、deploy/prometheus/rules/business-alerts.yml。",
            style_name="List Bullet",
        )
        body = (
            "在 7.31「十大体验/生产缺口」（CmdId 1130–1142）之上，本轮针对开服/活动高峰与跨节点容错再落地十项生产优化。"
            "（1）热点写路径：货币读走 HotWalletCache（Caffeine+Redis）写后双写+延迟双删；"
            "ReadOnlyRoutingAspect 强制 @Transactional(readOnly=true) 走从库；"
            "Uid/表分片助手 + ShardingSphere YAML 规划 16×64。"
            "（2）跨节点迁移：MigrateFallbackService 5s 超时回滚、失败率>1% 熔断；"
            "1109 百分比进度文案；DeadLetter+混沌可演练。"
            "（3）协议资源：resource_patch_manifest 差异块 + resumeOffset 断点续传；"
            "LoginScRsp.force_upgrade_deadline 逾期阻断登录。"
            "（4）战斗手感：Lockstep 仅同步技能/受击帧号；触控死区与滑动取消；按技能/设备族震动波形。"
            "（5）弹性运维：battle_pool_usage 接线 HPA；SLO 月错误预算自动降级非核心；"
            "AI fallback>5% 关视觉；钱包负余额等 P0 告警附 RCA 提示。"
            "（6）配置协作：至少 2 人审批，抽卡概率需总经理级；Git 回退 data/ 并触发灰度评估。"
            "（7）AI：本地 RL <50ms 覆盖「何时放大招」；多模态 battle_context_json 融合队伍/敌方/回合。"
            "（8）端侧性能：登录上报 DeviceProfile 匹配 QualityPreset，经 1082 下发阴影/粒子/视距。"
            "（9）分布式追踪：MDC 业务标签 + traceparent；Tempo 可选部署；告警自动根因骨架。"
            "（10）测试门禁：TenGapsBusinessE2EFlowTest + 混沌 runbook + CI/k6 p95 门禁骨架。"
        )
        insert_paragraph_after(p_anchor, body, style_name="Normal")
        insert_paragraph_after(
            p_anchor,
            "7.32 十大生产/体验优化落地（热点缓存·迁移熔断·Lockstep·SLO·LocalRL·OTel）",
            style_name="Heading 2",
        )
        n += 5
    elif find_para(doc, contains="7.32 十大生产") is None:
        raise SystemExit("未找到 7.31 锚点，无法插入 7.32")

    # ---------- 15.1 成熟度 ----------
    p_mature = find_para(doc, contains="十大缺口：WalletTcc")
    if p_mature is not None and "HotWalletCache" not in p_mature.text:
        replace_paragraph_text(
            p_mature,
            p_mature.text.strip()
            + "；十大生产优化：HotWalletCache/读写分离/分片规划、MigrateFallback、"
            "资源补丁续传与强制升级、BattleLockstep、HPA/SLO/自动降级、"
            "多人审批+Git 回退、LocalRL、QualityPreset、OTel/RCA、TenGaps E2E。",
        )
        n += 1

    p_wallet_lim = find_para(doc, startswith="钱包多节点依赖 Redis")
    if p_wallet_lim is not None and "HotWalletCache" not in p_wallet_lim.text:
        replace_paragraph_text(
            p_wallet_lim,
            p_wallet_lim.text.strip()
            + " 热点读已可用二级缓存降低主库压力；分片 YAML 已备生产启用开关。",
        )
        n += 1

    # ---------- 16 后续：标记已落地 ----------
    for p in doc.paragraphs:
        t = p.text.strip()
        if t.startswith("完善大厅社交与匹配体验") and "MigrateFallback" not in t:
            replace_paragraph_text(
                p,
                t
                + " MigrateFallback 超时熔断与进度 UI 已落地；继续压测跨节点失败率与 DeadLetter 持久化。",
            )
            n += 1
            break

    # ---------- 22.2 CI ----------
    p_ci = find_para(doc, startswith="定向单测：CmdId 唯一性")
    if p_ci is not None and "TenGapsBusinessE2EFlowTest" not in p_ci.text:
        replace_paragraph_text(
            p_ci,
            p_ci.text.strip()
            + "；生产优化：TenGapsOptimizationFlowTest、TenGapsBusinessE2EFlowTest。",
        )
        n += 1

    # ---------- 24 结语 ----------
    p_end = find_para(doc, startswith="本报告基于仓库当前代码与文档快照整理")
    if p_end is not None and "HotWalletCacheService" not in p_end.text:
        replace_paragraph_text(
            p_end,
            p_end.text.strip()
            + f" {today} 已在本文档内同步「十大生产/体验优化」落地"
            "（热点钱包缓存与读写分离/分片、迁移超时熔断与进度、资源补丁续传与强制升级、"
            "Lockstep 与触控震动、HPA/SLO 自动降级、配置多人审批与 Git 回退、"
            "LocalRL 多模态建议、QualityPreset、OTel/RCA、TenGaps E2E/混沌/k6；"
            "测试 TenGapsBusinessE2EFlowTest 等 216 通过）。",
        )
        n += 1

    p_conclude = find_para(doc, startswith="MyLunarCore 已经不是")
    if p_conclude is not None and "错误预算" not in p_conclude.text:
        replace_paragraph_text(
            p_conclude,
            p_conclude.text.strip()
            + " 开服洪峰侧再补：缓存与读写分离削热点、迁移可超时熔断、资源可增量续传、"
            "战斗帧对齐、指标驱动扩缩容与错误预算降级、配置可多人审批回退、"
            "AI 本地毫秒级建议、低端机画质档、链路可 RCA。",
        )
        n += 1

    # ---------- 表：Cmd / 包 / 术语 / FAQ ----------
    if len(doc.tables) > 5:
        t5 = doc.tables[5]
        if not table_has(t5, "1113 帧号"):
            append_table_row(
                t5,
                ["1113+", "BattleTick/弱网进度；Lockstep 关键技能广播 frameNo（BattleLockstepService）"],
            )
            n += 1
        if not table_has(t5, "force_upgrade"):
            append_table_row(
                t5,
                ["LoginScRsp", "force_upgrade_deadline；逾期未升级 ERR_FORCE_UPGRADE=12"],
            )
            n += 1
        if not table_has(t5, "1117 续传"):
            append_table_row(
                t5,
                ["1117", "ResourcePatchScNotify：resumeOffset + bsdiff/xdelta 差异块"],
            )
            n += 1

    if len(doc.tables) > 3:
        t3 = doc.tables[3]
        n += int(
            patch_table_cell(
                t3,
                old_contains="economy",
                col=2,
                new_text="商店/钱包/IAP；WAL；TCC；日对账；HotWallet 二级缓存双写+延迟双删",
            )
        )
        n += int(
            patch_table_cell(
                t3,
                old_contains="party",
                col=0,
                new_other={
                    2: "组队/跟随迁移 Saga；DeadLetter；MigrateFallback 超时熔断与进度 UI"
                },
            )
        )

    if len(doc.tables) > 17:
        t17 = doc.tables[17]
        for term, expl in [
            ["延迟双删", "写库后先删缓存再延迟再删，避免并发读回填脏数据"],
            ["迁移熔断", "MigrateFallback 失败率超 1% 拒绝新迁移，超时 5s 自动回滚"],
            ["轻量帧同步", "仅技能/受击按全局帧号对齐，移动仍状态同步"],
            ["错误预算", "月可用性目标 99.99%，燃烧过快自动降级非核心功能"],
            ["LocalRL", "本地强化学习/启发式战斗决策，目标响应 <50ms"],
            ["QualityPreset", "按 CPU/内存/GPU 匹配低中高极高渲染档并下发参数"],
        ]:
            if not table_has(t17, term[:4]):
                append_table_row(t17, [term, expl])
                n += 1

    if len(doc.tables) > 18:
        t18 = doc.tables[18]
        for q, a in [
            [
                "开服热点写入怎么扛？",
                "读走 HotWallet 二级缓存+从库；写走主库 WAL/TCC；大表按 uid 分片规划，见 sharding-sphere-player.yaml。",
            ],
            [
                "组队切服失败玩家会卡死吗？",
                "5s 超时自动回滚并推送重试；失败率过高熔断新迁移；DeadLetter 可重试补偿。",
            ],
            [
                "弱网更新还要下完整包吗？",
                "优先差量补丁+resumeOffset 断点续传；逾期未升级则强制商店更新。",
            ],
        ]:
            if not table_has(t18, q[:8]):
                append_table_row(t18, [q, a])
                n += 1

    # Fix accidental bad replace from earlier mistaken call
    for p in doc.paragraphs:
        t = p.text.strip()
        if t == "True" or t.startswith("True "):
            # remove junk if any
            replace_paragraph_text(p, "")
            n += 1

    doc.save(str(DOC_PATH))
    print(f"Patched {n} places -> {DOC_PATH}")


if __name__ == "__main__":
    main()
