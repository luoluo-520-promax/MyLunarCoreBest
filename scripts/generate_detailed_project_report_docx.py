# -*- coding: utf-8 -*-
"""生成 MyLunarCore 项目各方面详细总结 Word 报告（含目录与页码），输出到桌面。"""

from datetime import date
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Pt, Cm, RGBColor

DESKTOP = Path.home() / "Desktop"
OUTPUT_ASCII = DESKTOP / "MyLunarCore_Detailed_Project_Report.docx"
# 使用 unicode 转义，避免脚本文件在不同编码下把中文文件名写坏
OUTPUT_CN = DESKTOP / (
    "MyLunarCore"
    + "\u9879\u76ee\u5404\u65b9\u9762\u8be6\u7ec6\u603b\u7ed3\u62a5\u544a"
    + ".docx"
)


def set_run_font(run, size=None, bold=None, color=None, name="微软雅黑", east_asia="微软雅黑"):
    run.font.name = name
    run._element.rPr.rFonts.set(qn("w:eastAsia"), east_asia)
    if size is not None:
        run.font.size = size
    if bold is not None:
        run.bold = bold
    if color is not None:
        run.font.color.rgb = color


def set_doc_fonts(doc: Document):
    style = doc.styles["Normal"]
    style.font.name = "微软雅黑"
    style.font.size = Pt(11)
    style._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    pf = style.paragraph_format
    pf.line_spacing_rule = WD_LINE_SPACING.ONE_POINT_FIVE
    pf.space_after = Pt(6)

    for i in range(1, 4):
        hs = doc.styles[f"Heading {i}"]
        hs.font.name = "微软雅黑"
        hs._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
        hs.font.color.rgb = RGBColor(25, 75, 140)
        if i == 1:
            hs.font.size = Pt(16)
            hs.font.bold = True
        elif i == 2:
            hs.font.size = Pt(13)
            hs.font.bold = True
        else:
            hs.font.size = Pt(12)


def set_page_layout(doc: Document):
    section = doc.sections[0]
    section.top_margin = Cm(2.54)
    section.bottom_margin = Cm(2.54)
    section.left_margin = Cm(2.8)
    section.right_margin = Cm(2.8)
    section.page_width = Cm(21.0)
    section.page_height = Cm(29.7)


def _add_page_number_field(paragraph):
    """在段落中插入 PAGE 域。"""
    run = paragraph.add_run()
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")

    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = " PAGE "

    fld_sep = OxmlElement("w:fldChar")
    fld_sep.set(qn("w:fldCharType"), "separate")

    # 占位显示，打开 Word 后会刷新为真实页码
    text = OxmlElement("w:t")
    text.text = "1"

    fld_end = OxmlElement("w:fldChar")
    fld_end.set(qn("w:fldCharType"), "end")

    r = run._r
    r.append(fld_begin)
    r.append(instr)
    r.append(fld_sep)
    r.append(text)
    r.append(fld_end)
    set_run_font(run, size=Pt(9), color=RGBColor(90, 90, 90))


def add_footer_with_page_numbers(doc: Document):
    section = doc.sections[0]
    footer = section.footer
    footer.is_linked_to_previous = False
    p = footer.paragraphs[0] if footer.paragraphs else footer.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.clear()
    run1 = p.add_run("MyLunarCore 项目详细总结报告  ·  第 ")
    set_run_font(run1, size=Pt(9), color=RGBColor(90, 90, 90))
    _add_page_number_field(p)
    run2 = p.add_run(" 页")
    set_run_font(run2, size=Pt(9), color=RGBColor(90, 90, 90))


def enable_update_fields_on_open(doc: Document):
    """打开文档时提示/自动更新域（目录、页码）。"""
    settings = doc.settings.element
    update = OxmlElement("w:updateFields")
    update.set(qn("w:val"), "true")
    settings.append(update)


def add_toc_field(doc: Document):
    """插入 Word 自动目录域（打开后可刷新页码）。"""
    p = doc.add_paragraph()
    run = p.add_run()
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")

    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = ' TOC \\o "1-2" \\h \\z \\u '

    fld_sep = OxmlElement("w:fldChar")
    fld_sep.set(qn("w:fldCharType"), "separate")

    placeholder = OxmlElement("w:t")
    placeholder.text = "（打开 Word 后右键目录 →「更新域」即可显示带页码的完整目录）"

    fld_end = OxmlElement("w:fldChar")
    fld_end.set(qn("w:fldCharType"), "end")

    r = run._r
    r.append(fld_begin)
    r.append(instr)
    r.append(fld_sep)
    r.append(placeholder)
    r.append(fld_end)
    set_run_font(run, size=Pt(10), color=RGBColor(80, 80, 80))


def add_title(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_run_font(run, size=Pt(24), bold=True, color=RGBColor(25, 75, 140))


def add_subtitle(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_run_font(run, size=Pt(11), color=RGBColor(100, 100, 100))


def add_heading(doc: Document, text: str, level: int = 1):
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        set_run_font(run, color=RGBColor(25, 75, 140) if level <= 2 else None)
    return h


def add_body(doc: Document, text: str):
    p = doc.add_paragraph(text)
    for run in p.runs:
        set_run_font(run, size=Pt(11))
    p.paragraph_format.first_line_indent = Cm(0.74)
    return p


def add_note(doc: Document, text: str):
    p = doc.add_paragraph()
    run = p.add_run(text)
    set_run_font(run, size=Pt(10), color=RGBColor(90, 90, 90))
    return p


def add_bullet(doc: Document, text: str, level: int = 0):
    p = doc.add_paragraph(text, style="List Bullet")
    p.paragraph_format.left_indent = Cm(0.5 * (level + 1))
    for run in p.runs:
        set_run_font(run, size=Pt(11))
    return p


def add_code(doc: Document, text: str):
    p = doc.add_paragraph()
    p.paragraph_format.left_indent = Cm(0.4)
    p.paragraph_format.space_before = Pt(4)
    p.paragraph_format.space_after = Pt(8)
    run = p.add_run(text)
    set_run_font(run, size=Pt(9), name="Consolas", east_asia="微软雅黑")
    run.font.color.rgb = RGBColor(30, 30, 30)
    return p


def add_table(doc: Document, headers, rows):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = h
        for p in cell.paragraphs:
            for run in p.runs:
                set_run_font(run, size=Pt(10), bold=True)
    for r_idx, row in enumerate(rows):
        for c_idx, val in enumerate(row):
            cell = table.rows[r_idx + 1].cells[c_idx]
            cell.text = str(val)
            for p in cell.paragraphs:
                for run in p.runs:
                    set_run_font(run, size=Pt(9.5))
    doc.add_paragraph()
    return table


def add_page_break(doc: Document):
    doc.add_page_break()


def build_document() -> Document:
    doc = Document()
    set_doc_fonts(doc)
    set_page_layout(doc)
    add_footer_with_page_numbers(doc)
    enable_update_fields_on_open(doc)

    today = date.today().strftime("%Y年%m月%d日")

    # ========== 封面 ==========
    for _ in range(3):
        doc.add_paragraph()
    add_title(doc, "MyLunarCore")
    add_title(doc, "项目各方面详细总结报告")
    doc.add_paragraph()
    add_subtitle(doc, "面向非专业读者的通俗版全景说明")
    add_subtitle(doc, "（含技术架构、业务能力、安全运维、微服务迁移与后续建议）")
    doc.add_paragraph()
    add_subtitle(doc, "项目路径：c:\\Users\\ASUS\\IdeaProjects\\test\\MyLunarCore")
    add_subtitle(doc, f"报告生成日期：{today}")
    add_subtitle(doc, "文档性质：内部汇报 / 新人上手 / 架构决策参考")
    add_page_break(doc)

    # ========== 目录 ==========
    add_heading(doc, "目录", 1)
    add_body(
        doc,
        "下面先给出章节速览（方便不打开自动目录时也能快速跳读）。"
        "其后是 Word 自动目录域：请用 Microsoft Word 或 WPS 打开本文件后，"
        "右键点击自动目录区域，选择「更新域 / 更新整个目录」，即可显示带页码的完整目录。",
    )
    add_note(doc, "页脚已设置「第 N 页」页码，全文均可对照页码阅读。")
    doc.add_paragraph()

    toc_items = [
        "一、写在前面：这份报告在讲什么",
        "二、一句话读懂本项目",
        "三、产品定位与玩法形态",
        "四、技术栈一览（用生活化比喻解释）",
        "五、总体架构：谁和谁怎么配合",
        "六、仓库目录与代码包结构",
        "七、核心业务能力详解",
        "八、网络与通信协议",
        "九、数据存储与配置热更",
        "十、AI 助手与教练系统",
        "十一、管理后台与运维能力",
        "十二、可观测性、埋点与生产加固",
        "十三、安全机制与上线注意事项",
        "十四、微服务脚手架现状",
        "十五、成熟度评估与已知局限",
        "十六、后续发展建议",
        "十七、给不同角色读者的阅读指引",
        "十八、附录：常用命令与配置速查",
        "十九、术语小词典（给非技术同学）",
        "二十、如何向他人讲解本项目（电梯演讲稿）",
        "二十一、发布、备份与日常运维手册摘要",
        "二十二、架构决策记录（ADR）与持续集成",
        "二十三、端口与组件速查总表",
        "二十四、结语",
    ]
    for i, item in enumerate(toc_items, 1):
        p = doc.add_paragraph()
        run = p.add_run(f"{item}")
        set_run_font(run, size=Pt(11))
        p.paragraph_format.space_after = Pt(2)

    doc.add_paragraph()
    add_heading(doc, "自动目录（打开后请更新域）", 2)
    add_toc_field(doc)
    add_page_break(doc)

    # ========== 一 ==========
    add_heading(doc, "一、写在前面：这份报告在讲什么", 1)
    add_body(
        doc,
        "如果你第一次接触这个仓库，可能会觉得文件很多、名词很技术。"
        "本报告的目标，是用尽量白话的方式，把 MyLunarCore「是什么、能干什么、怎么跑起来、"
        "哪里成熟、哪里还只是脚手架、上线要注意什么」一次讲清楚。",
    )
    add_body(
        doc,
        "阅读时不必先懂 Java 或游戏服务器。可以把整套系统想象成："
        "一台「游戏主机」（主工程）负责真正开房间、打怪、抽卡、存档；"
        "旁边有一个「实验工作台」（microservices 目录）在试着把部分能力拆成独立小服务；"
        "还有一个「办公室小窗口」（管理后台 HTTP）给运营和运维改配置、热更新、看工单。",
    )
    add_bullet(doc, "报告依据：当前仓库 README、源码包结构、配置文件与已有评估文档快照。")
    add_bullet(doc, "报告语言：优先通俗中文，必要时才给出类名/配置项，方便技术人员核对。")
    add_bullet(doc, "报告用途：汇报演示、新人入职、产品与技术对齐、上线前核对清单。")

    # ========== 二 ==========
    add_heading(doc, "二、一句话读懂本项目", 1)
    add_body(
        doc,
        "MyLunarCore 是一套基于 Spring Boot 4 + Netty/KCP + Protobuf + MySQL 的"
        "「崩坏：星穹铁道式」游戏私服 / 自研服务端：玩家在分区场景里探索，遇敌后进入回合制战斗；"
        "同时提供抽卡、商店支付、活动任务、组队匹配、排行榜聊天，以及管理后台热更与可选多节点切服。"
        "真正可运行的主业务在根目录单体工程；microservices/ 只是迁移脚手架，不能单独当作生产账号体系。",
    )

    add_heading(doc, "2.1 三个最容易误解的点", 2)
    add_table(
        doc,
        ["误解", "正确理解"],
        [
            [
                "仓库里有 microservices，所以已经是完整微服务游戏服",
                "主业务仍在单体；脚手架是迁移试验田，auth/player 多为演示桩",
            ],
            [
                "这是原神那种无缝大世界服务器",
                "短期定位是崩铁式 Plane/Floor 分区实例 + 回合战，不是无缝大世界",
            ],
            [
                "开发默认配置可以直接上线",
                "默认密钥、演示密码、IAP Mock 仅限本地；生产必须用 prod 并轮换密钥",
            ],
        ],
    )

    # ========== 三 ==========
    add_heading(doc, "三、产品定位与玩法形态", 1)
    add_body(
        doc,
        "从玩家感受来说，本项目更接近「星穹铁道」：地图按区域（Plane/Floor）开实例，"
        "在场景里移动、交互、触发遭遇，再进入独立的回合制战斗；同时有模拟宇宙（Rogue）、"
        "挑战关卡、养成、抽卡和经济循环。它不是「原神 / 鸣潮」那种在同一张大地图上"
        "高频实时动作战斗的无缝大世界。",
    )

    add_heading(doc, "3.1 与两类主流玩法对照", 2)
    add_table(
        doc,
        ["对照维度", "原神/鸣潮式", "崩铁式", "本项目现状"],
        [
            ["世界模型", "无缝大地图 + 分片加载", "分区实例（Plane/Floor）", "Zone 分区，非无缝"],
            ["战斗", "世界内实时动作", "遇敌进入回合/波次", "遇敌进回合战，偏崩铁"],
            ["服务器节奏", "移动/战斗高频模拟", "场景低频 + 战斗独立", "全局/场景/战斗分时钟"],
            ["多人", "同区域可见 + 场景舰队", "实例内协作", "组队与多人共战雏形"],
        ],
    )

    add_heading(doc, "3.2 单机与多节点", 2)
    add_body(
        doc,
        "默认是一台机器、一个进程就能跑通开发联调（center.mode=local）。"
        "若要做双节点切服、共享在线表、世界聊天跨进程，需要打开 Redis，"
        "并把中心路由切到 remote 模式。无缝 Cell 切换与实时战斗权威目前是实验开关，默认关闭。",
    )
    add_bullet(doc, "单机开发：最常见，改配置后 mvn spring-boot:run 即可。")
    add_bullet(doc, "多节点：需共享 Redis + 远程 Center，先验证路由与迁移，再谈更大扩容。")
    add_bullet(doc, "实验能力：cell-handoff / realtime-combat 默认关，避免未成熟能力误开。")

    # ========== 四 ==========
    add_heading(doc, "四、技术栈一览（用生活化比喻解释）", 1)
    add_body(
        doc,
        "下面这张表尽量用「生活比喻」帮助非技术同学理解每一层在干什么。"
        "技术人员可直接看「技术选型」列。",
    )
    add_table(
        doc,
        ["层次", "技术选型", "生活化理解"],
        [
            ["编程语言", "Java 17", "写服务器逻辑的「母语」"],
            ["应用框架", "Spring Boot 4.0.4", "搭好架子、自动接线的「装配车间」"],
            ["游戏联网", "Netty 4.2.9 + KCP", "玩家客户端连进来的「高速通道」"],
            ["消息格式", "Protobuf 4.34", "双方约定好的「快递装箱规格」"],
            ["玩家存档", "MySQL 8 + JDBC", "账号、背包、钱包等的「保险柜」"],
            ["可选共享状态", "Redis（可关）", "多台机器共用的「白板/黑板」"],
            ["本地加速", "Caffeine 缓存", "常用数据放桌上，少跑保险柜"],
            ["接口限流", "Bucket4j", "门口保安，防止刷登录/刷包"],
            ["运维网页", "Spring MVC + Security", "办公室窗口，要钥匙才能进"],
            ["监控指标", "Actuator + Prometheus", "仪表盘：健康、流量、是否卡顿"],
            ["微服务试验", "Spring Cloud Alibaba", "旁边工作台，尚未替代主机"],
            ["链路追踪（可选）", "Micrometer Tracing / Zipkin", "一次请求串起多段日志的「快递单号」"],
            ["分布式锁（可选）", "Redisson", "多机抢同一把锁，保护钱包等写路径"],
        ],
    )
    add_body(
        doc,
        "构建工具是 Maven（含 mvnw 包装脚本）。主工程与 microservices 脚手架均对齐"
        "Spring Boot 4.0.4、Spring Cloud 2025.1.0、Spring Cloud Alibaba 2025.1.0.0 一线版本；"
        "另含 kcp-netty、Redisson 等配套依赖。",
    )

    # ========== 五 ==========
    add_heading(doc, "五、总体架构：谁和谁怎么配合", 1)
    add_heading(doc, "5.1 架构鸟瞰", 2)
    add_code(
        doc,
        "玩家客户端\n"
        "  │\n"
        "  ├─ TCP / KCP 端口 9000  ──►  游戏网络层（编解码、限流、分发）\n"
        "  │                              └─ 各业务服务（战斗/场景/抽卡/…）\n"
        "  │                              └─ 游戏主循环时钟（Global / Zone / Battle）\n"
        "  │\n"
        "运营 / 运维浏览器\n"
        "  └─ HTTP 端口 8080      ──►  Admin 后台（登录、热更、配置导入、工单）\n"
        "\n"
        "数据层\n"
        "  ├─ MySQL：玩家与账号等持久数据\n"
        "  ├─ data/*.json：活动、商店、任务、卡池等可热更配置\n"
        "  └─ Redis（可选）：切服票据、在线表、排行榜、世界聊天\n"
        "\n"
        "可选旁路\n"
        "  └─ ai-assist-service :18083  （AI 问答 sidecar，失败回退本机规则教练）\n"
        "\n"
        "可观测性（可选 Compose）\n"
        "  ├─ Prometheus :9090\n"
        "  ├─ Grafana :3000（看板 MyLunarCore Business）\n"
        "  └─ ClickHouse（profile analytics）\n"
        "\n"
        "脚手架（未替代主工程）\n"
        "  ├─ api-gateway :18443 → auth / player / ai-assist（网关已挂）\n"
        "  ├─ chat-service :18084、match-service :18085（独立 HTTP，未进网关）\n"
        "  └─ auth :18081 / player :18082（演示桩）",
    )

    add_heading(doc, "5.2 架构原则（为什么这样分）", 2)
    add_bullet(doc, "实时游戏面（战斗、场景、钱包扣费写路径）留在单体 Netty 核心，保证一致性和延迟。")
    add_bullet(doc, "运维面（Admin、配置导入、热更）与游戏 Tick 解耦，可优先外拆。")
    add_bullet(doc, "AI 助手是「建议面」：只读上下文、异步回答，适合 sidecar，挂了也不应拖垮游戏服。")
    add_bullet(doc, "Center 路由支持 local / remote，为多节点场景迁移铺路，而不是一上来拆战斗服务。")

    add_heading(doc, "5.3 启动入口", 2)
    add_body(
        doc,
        "主工程启动类是 MyLunarCoreApplication。"
        "游戏侧由 GameServer 驱动全局/场景/战斗时钟；"
        "网络侧由 GameNettyServer（TCP）与 GameKcpServer（KCP）监听同一业务端口（默认 9000）；"
        "管理侧由嵌入式 Web 容器提供 8080 端口的后台接口。",
    )

    # ========== 六 ==========
    add_heading(doc, "六、关键目录与代码包结构", 1)
    add_heading(doc, "6.1 仓库顶层目录", 2)
    add_code(
        doc,
        "MyLunarCore/\n"
        "├── README.md / QUICKSTART.md # 定位、快速启动与 FAQ\n"
        "├── pom.xml                   # 主工程 Maven 依赖\n"
        "├── docker-compose.dev.yml    # 本地 MySQL + Redis\n"
        "├── data/                     # 运行时 JSON/CSV 配置（热更友好）\n"
        "├── docs/                     # 生产加固、协议、ADR 等\n"
        "├── deploy/                   # Prometheus / Grafana / ClickHouse 部署样例\n"
        "├── scripts/                  # 发布演练、导入校验、报告生成\n"
        "├── tools/                    # 开发辅助脚本\n"
        "├── microservices/            # Cloud Alibaba 迁移脚手架\n"
        "├── .github/workflows/        # CI（buf lint + CmdId 测试）\n"
        "├── src/main/java/...         # 单体游戏核心业务代码\n"
        "├── src/main/proto/           # Protobuf 协议定义\n"
        "└── src/main/resources/       # 配置、SQL、classpath 样例数据",
    )

    add_heading(doc, "6.2 主工程业务包一览", 2)
    add_body(
        doc,
        "Java 包根路径为 cn.itcast.demo.mylunarcore。"
        "下面用「普通人能懂的一句话」说明每个包在干什么。",
    )
    add_table(
        doc,
        ["包名", "普通人理解", "典型职责"],
        [
            ["net", "邮局与分拣中心", "帧编解码、包分发、限流背压、各 PacketHandlers"],
            ["player", "玩家前台接待", "登录会话、在线状态、玩家数据加载与落库"],
            ["battle", "战斗裁判室", "回合战、遭遇、波次、战斗辅助策略"],
            ["scene", "地图场景管理员", "进图、移动、AOI 同步、Zone 管理"],
            ["center", "总调度台", "多节点场景归属、切服票据、在线表"],
            ["character", "角色成长手册", "创角、晋阶、天赋、属性计算"],
            ["item", "背包仓库", "道具使用、装备、强化等"],
            ["economy", "收银台与钱包", "商店、货币、IAP 验签"],
            ["gacha", "抽卡机", "卡池、扣费、发奖、pity、流水"],
            ["challenge", "挑战副本台", "挑战开局、结算、奖励"],
            ["rogue", "模拟宇宙", "Roguelike 地图与祝福奇物流程"],
            ["quest", "任务板", "接取提交、进度与触发"],
            ["hall", "大厅社交区", "好友、邮件、聊天、排行榜入口"],
            ["party", "临时小队", "大世界组队雏形（同进程）"],
            ["matchmaking", "匹配大厅", "队列、房间、挑战匹配"],
            ["guild", "公会会所", "创建/加入/贡献/商店 + 公会战闭环"],
            ["skin", "衣柜", "皮肤拥有与装备"],
            ["assist", "智能向导", "规则教练、多轮历史、画像、RAG、语义安全、远程 AI、反馈闭环"],
            ["admin", "运维办公室", "后台登录、热更、配置导入、工单、回滚、存档导出"],
            ["anticheat", "巡场保安", "移动超速、战斗审计、确定性伤害校验"],
            ["achievement", "成就墙", "成就进度与领取"],
            ["arena", "竞技场裁判", "ELO 评分、赛季与匹配 mode=10"],
            ["tutorial", "新手引导员", "引导步骤、NewbieGuide 配置"],
            ["activity", "活动运营台", "活动排期、类型目录、版本活动容器、热更推送"],
            ["battlepass", "战令柜台", "战令 XP/等级、免费与付费奖励轨"],
            ["story", "主线章节室", "章节进度与 Plane/Floor 解锁门闸"],
            ["dialogue", "对话编剧室", "NPC 对话树触发、分支进度"],
            ["cutscene", "过场导演", "剧情过场触发、跳过/完成联动"],
            ["home", "家园管理员", "基建、产出、家具、好友互访"],
            ["analytics", "数据埋点员", "结构化运营事件日志"],
            ["archive", "档案归档员", "历史数据清理与归档任务"],
            ["tx", "交易记账本", "本地事务日志、防重放护栏"],
            ["security", "安全组件", "敏感信息脱敏等横切能力"],
            ["common", "公共工具箱", "时钟、热更协调、活动、指标、优雅停机、日周刷新、体力"],
            ["world", "实验大世界", "Cell 切换、实时战斗骨架（默认关）"],
            ["worldboss", "世界 BOSS", "全服血量、阶段、日限与排名奖励"],
            ["abyss", "深渊赛季", "周期重置、多队轮战、星级奖励"],
            ["equipment", "光锥/遗器工坊", "随机词条、强化、分解、套装加成"],
            ["social", "社交扩展", "赠礼、公会红包、语音信令"],
            ["handbook", "收集图鉴", "图鉴进度与 handbook_* 成就"],
            ["affinity", "好感度", "NPC 好感与对话选项联动"],
            ["profile", "名片外观", "名片、头像框等展示"],
            ["settings", "玩家设置与客服", "设置同步、工单提交"],
            ["tools", "开发工具", "如公会战 Mock 客户端等辅助"],
            ["repo / model", "档案柜与表格", "数据访问与实体模型"],
            ["config", "配置读取", "各类配置装配"],
        ],
    )

    # ========== 七 ==========
    add_heading(doc, "七、核心业务能力详解", 1)
    add_body(
        doc,
        "本章按「玩家会感知到的功能」展开。每个小节说明：能做什么、系统里谁在管、"
        "配置大概在哪里。类名留给需要对照代码的同事。",
    )

    add_heading(doc, "7.1 登录与账号会话", 2)
    add_body(
        doc,
        "玩家用账号密码登录后，服务器建立会话、校验心跳、处理登出，并维护在线人数与超时。"
        "密码支持哈希校验；明文兼容开关默认关闭，避免不安全的比对方式在不知情时开启。",
    )
    add_bullet(doc, "关键类：PlayerSessionService、PlayerLoginApplicationService、AccountPasswordService")
    add_bullet(doc, "协议：player_session.proto；命令号大致在会话段 68–83")

    add_heading(doc, "7.2 角色养成与皮肤", 2)
    add_body(
        doc,
        "覆盖创角、成长晋阶、天赋、属性计算，以及皮肤衣柜的拥有与装备。"
        "属性计算由 AttributeCalculator 等组件完成，皮肤配置来自 data/SkinConfigs.json。",
    )
    add_bullet(doc, "入口：CharacterNettyService、CharacterCreationApplicationService、TalentApplicationService")
    add_bullet(doc, "皮肤：SkinNettyService、SkinEquipApplicationService")

    add_heading(doc, "7.3 场景与世界", 2)
    add_body(
        doc,
        "玩家进入某个 Plane/Floor 对应的 Zone 实例后，可移动、与 NPC/道具交互，"
        "服务器按兴趣范围（AOI）同步周围实体。Zone 可配置人数上限与租约心跳。"
        "跨图或跨节点时，由 Center 规划归属，必要时发放迁移票据。",
    )
    add_bullet(doc, "入口：SceneNettyService、ZoneManager、ZoneTickService、PlayerMigrationService")
    add_bullet(doc, "实验：CellBoundaryHandoffService、RealtimeCombatAuthority（默认关闭）")

    add_heading(doc, "7.4 回合制战斗", 2)
    add_body(
        doc,
        "场景中触发遭遇后进入战斗实例：开战、行动、结算、退出。"
        "支持遇敌配置与波次；多人共战通过 BattleContext.participantPlayerIds 等字段表达。"
        "另有启发式战斗辅助策略（偏提示，不是外挂代打）。",
    )
    add_bullet(doc, "入口：BattleNettyService、BattleManager、EncounterConfigRepository")
    add_bullet(doc, "配置：data/EncounterConfigs.json；辅助策略 HeuristicBattleAssistPolicy")

    add_heading(doc, "7.5 挑战关卡与模拟宇宙", 2)
    add_body(
        doc,
        "挑战玩法负责开局、结果上报、组奖励与历史；模拟宇宙（Rogue）则包含地图生成、移动、"
        "祝福/奇物、战斗上报与天赋等完整 Roguelike 流程。",
    )
    add_bullet(doc, "挑战：ChallengeNettyService、ChallengeRuntime（命令号约 600 段）")
    add_bullet(doc, "Rogue：RogueNettyService、RogueRuntime；配置 data/RogueMapGen.json（约 550 段）")

    add_heading(doc, "7.6 抽卡", 2)
    add_body(
        doc,
        "卡池信息可热更；一次抽卡尽量把「扣费 → 发奖 → pity 更新 → 流水记录」放在同一事务里，"
        "避免出现扣了钱没卡、或重复发奖。另有天井兑换与抽卡历史查询。",
    )
    add_bullet(doc, "入口：GachaNettyService、GachaApplicationService、GachaConfigService")
    add_bullet(doc, "配置：data/Banners.json；流水表相关迁移含 gacha_draw_history")

    add_heading(doc, "7.7 商店、钱包与内购（IAP）", 2)
    add_body(
        doc,
        "商店购买会变更钱包并推送货币变化。内购流程包含下单、确认、日领等；"
        "验签可走 Mock（仅开发）、Apple、Google 渠道。"
        "生产环境必须关闭 Mock，并配置真实渠道密钥，否则启动会 fail-fast。",
    )
    add_bullet(doc, "入口：EconomyNettyService、ShopApplicationService、WalletApplicationService")
    add_bullet(doc, "IAP：IapVerifyGateway、各渠道 Verifier、IapProductionGuard")
    add_bullet(doc, "配置：data/ShopConfigs.json；mylunarcore.iap.*")

    add_heading(doc, "7.8 背包与道具", 2)
    add_body(
        doc,
        "提供背包快照以及使用、装备、强化、升阶、锁定、丢弃等操作。"
        "道具表可由 CSV 等素材导入维护，便于策划与程序协作。",
    )
    add_bullet(doc, "入口：ItemNettyService、ItemApplicationService")
    add_bullet(doc, "素材参考：data/items_config.csv")

    add_heading(doc, "7.9 活动与任务", 2)
    add_body(
        doc,
        "活动侧有排期与配置查询，支持热更推送，也支持 Excel/JSON 导入。"
        "任务侧支持接取、提交、放弃，以及进度与触发引擎，方便做引导与运营目标。",
    )
    add_bullet(doc, "活动：ActivityNettyService、ActivityScheduleService；data/Activity*.json、data/activities/")
    add_bullet(doc, "任务：QuestNettyService、QuestProgressApplicationService、QuestTriggerEngine")
    add_bullet(doc, "任务配置：data/QuestConfigs.json")

    add_heading(doc, "7.10 大厅社交：好友、邮件、聊天、排行榜", 2)
    add_body(
        doc,
        "大厅模块把社交能力收在一起：好友、邮件、世界/私聊，以及历史最高分类排行榜。"
        "排行榜与世界聊天在开启 Redis 后可跨进程共享；聊天里 @助手 可转给 AI 模块。",
    )
    add_bullet(doc, "入口：HallNettyService、ChatService、FriendApplicationService、MailApplicationService")
    add_bullet(doc, "排行榜：LeaderboardService（Redis ZSET 或进程内存）")

    add_heading(doc, "7.11 组队与匹配", 2)
    add_body(
        doc,
        "Party 是大世界组队雏形（同进程权威，人数上限约 4 人）。"
        "Match/Room 面向副本匹配：排队、开房、兼容性打分，并与挑战匹配协调器联动。"
        "跨节点组队与完整私聊跨服仍有局限，报告后文会再次提醒。",
    )
    add_bullet(doc, "组队：party.PartyService")
    add_bullet(doc, "匹配：MatchNettyService、RoomService、MatchQueue、ChallengeMatchCoordinator")

    add_heading(doc, "7.12 反作弊", 2)
    add_body(
        doc,
        "移动侧检查超速与过频上报；战斗侧有审计服务，便于事后排查异常。"
        "这属于基础防护，不是完整的反外挂产品，但能挡住一类明显作弊。",
    )
    add_bullet(doc, "MoveSpeedGuard、BattleAuditService；配置 lunarcore.anti-cheat.*")

    add_heading(doc, "7.13 版本推送与玩家设置", 2)
    add_body(
        doc,
        "版本/热更相关协议可向客户端推送更新信息；设置与客服工单允许玩家改偏好并提交问题单，"
        "后台可继续处理。",
    )
    add_bullet(doc, "VersionNettyService；SettingsNettyService、SupportTicketApplicationService")

    add_heading(doc, "7.14 成就与新手引导", 2)
    add_body(
        doc,
        "成就系统记录玩家达成条件与领取状态，适合做长期留存目标。"
        "新手引导则按配置步骤引导新玩家完成关键操作，降低上手门槛。"
        "两者都属于「内容运营型」能力：配置改了，行为就能跟着变。",
    )
    add_bullet(doc, "成就：AchievementService；表 player_achievement；协议号段约 950–954")
    add_bullet(doc, "新手引导：NewbieGuideService；配置 data/NewbieGuide.json；协议号段约 960–964")

    add_heading(doc, "7.15 公会与公会战", 2)
    add_body(
        doc,
        "公会已落地基础能力：创建、加入、退出、贡献，以及公会商店兑换。"
        "在此之上，公会战已形成可运行闭环：匹配 → 报分 → 积分榜 → 周期结算"
        "（GuildWarService；命令号约 984–989；表 guild_war_*）。"
        "另有公会 Raid 调度与贡献榜（GuildRaidScheduler、GuildContributionRank）。"
        "配置可参考 data/GuildShopConfigs.json；基础协议约在 970–983。"
        "注意：部分 Protobuf 消息体与客户端 Handler 仍可能需联调补全，CmdId 已预留。",
    )
    add_bullet(doc, "入口：GuildService、GuildWarService、GuildNettyService、GuildPacketHandlers")
    add_bullet(doc, "已有：创建/加入/退出/贡献/商店 + 公会战匹配报分结算；Raid/贡献榜可用")

    add_heading(doc, "7.16 对话树、过场与家园", 2)
    add_body(
        doc,
        "除了战斗与经济循环，项目还补齐了「讲故事」和「家园养成」能力，"
        "方便策划做剧情与轻养成。剧情侧会记录分支与已播放状态；"
        "过场支持跳过/完成并与玩家设置联动；家园支持产出、家具与好友互访。",
    )
    add_bullet(doc, "对话树：DialogueTriggerEngine、DialogueProgressService；配置 data/DialogueTrees.json。")
    add_bullet(doc, "过场：CutsceneTriggerService；配置 data/CutsceneConfigs.json。")
    add_bullet(doc, "家园：HomeBaseService（产出/家具/互访，DB home_state）；配置 data/HomeFacilityConfigs.json。")
    add_bullet(doc, "成熟度提示：服务端逻辑已加深；部分协议消息体仍可按客户端联调继续补全。")

    add_heading(doc, "7.17 竞技场、日周刷新与存档运维", 2)
    add_body(
        doc,
        "竞技场侧用 ArenaRatingService 维护 ELO 与赛季，匹配模式 mode=10。"
        "日/周刷新由 PeriodicResetService + ClusterJobLock 保证多节点下「只跑一次」"
        "（例如周贡献清零）。运维侧可对玩家数据做导入导出，并支持带确认码的一键回滚。",
    )
    add_bullet(doc, "竞技场：ArenaRatingService；匹配 mode=10")
    add_bullet(doc, "周期刷新：PeriodicResetService、ClusterJobLock")
    add_bullet(doc, "存档：GET/POST /api/admin/ops/player-data/{uid}/export|import")
    add_bullet(doc, "回滚：POST /api/admin/ops/rollback?confirm=ROLLBACK&reason=")

    add_heading(doc, "7.18 战斗安全与快照（玩家不容易看见、但很重要）", 2)
    add_body(
        doc,
        "回合战不能「客户端报多少伤害服务器就信多少」。"
        "BattleDeterministicValidator 与 BattleDamageFormula 用服务端公式做确定性校验；"
        "BattleAuditService 留下审计线索；BattleSnapshotService 支持断线重连/热恢复时的战斗快照。"
        "对玩家而言，这意味着：更少「莫名其妙打赢/打输」，以及掉线后更可能回到合理状态。",
    )
    add_bullet(doc, "类：BattleDeterministicValidator、BattleSnapshotService、BattleAuditService")
    add_bullet(doc, "配置提示：lunarcore.battle-snapshot.enabled 等（见生产加固文档）")

    add_heading(doc, "7.19 体力、每日任务、战令与主线章节", 2)
    add_body(
        doc,
        "这是近期补齐的「日常运营循环」能力，让玩家每天都有明确目标，也让数值消耗可控。"
        "体力（Stamina）会自然恢复，打本消耗，也可日购补充；"
        "每日任务按目标累计进度并领奖，与日切刷新耦合；"
        "战令提供 XP/等级以及免费轨与付费轨奖励；"
        "主线章节记录进度，并作为进入某些 Plane/Floor 的门闸。"
        "版本活动则用树形节点 + 共享 ActivityToken，把一版活动内容组织起来。",
    )
    add_bullet(doc, "体力：StaminaService；配置 data/StaminaConfigs.json；登录会话与 Tick 驱动恢复。")
    add_bullet(doc, "每日任务：DailyMissionService；配置 data/DailyMissionConfigs.json；与 PeriodicReset 日切联动。")
    add_bullet(doc, "战令：BattlePassService / BattlePassPacketHandlers；配置 data/BattlePassConfigs.json；CmdId 约 990–995、1009。")
    add_bullet(doc, "主线章节：StoryChapterService；配置 data/ChapterConfigs.json；进图前可做解锁校验；CmdId 约 1006–1008。")
    add_bullet(doc, "版本活动：VersionActivityService；配置 data/VersionActivityConfigs.json。")
    add_bullet(doc, "协议：DailyLoopPacketHandlers + daily_loop_system.proto 已接线（体力 996–1000、日常 1001–1005）；客户端联调可继续加深表现层。")

    add_heading(doc, "7.20 好友助战、公会科技与切图加载态", 2)
    add_body(
        doc,
        "好友助战允许借用好友角色快照加入战斗（写入 BattleContext.participantPlayerIds）；"
        "公会科技按公会等级提供全局 Buff（攻/防/血/体力恢复等）；"
        "切图时服务器会发放 LoadingTicket，加载完成前拒绝移动与开战，减少「半截进图」导致的状态错乱。"
        "登录阶段还会交换 SupportedFeatures 能力位掩码，让客户端按服务端能力隐藏未开放入口"
        "（例如公会战入口在能力未开时返回 retcode=20）。",
    )
    add_bullet(doc, "助战：SupportService（外借快照 → 开战加入参战列表）。")
    add_bullet(doc, "公会科技：GuildTechService；配置 data/GuildTechConfigs.json。")
    add_bullet(doc, "切图加载：PlayerLoadingStateService；CmdId 352–353（加载完成握手）。")
    add_bullet(doc, "能力握手：登录响应含 supported_features / enabled_features、session_crypto_key。")

    add_heading(doc, "7.21 缺口补齐玩法（世界 BOSS / 深渊 / 养成 / 社交）", 2)
    add_body(
        doc,
        "在日常循环之外，仓库还按 docs/feature-gap-fill.md 落地了一批「崩铁式」内容深度能力，"
        "方便策划做长线目标与社交互动。服务端逻辑与配置已具备；部分客户端表现层仍可继续联调。",
    )
    add_bullet(doc, "世界 BOSS：WorldBossService；全服血量/阶段/日限/排名；data/WorldBossConfigs.json。")
    add_bullet(doc, "深渊赛季：AbyssSeasonService；周期重置、多队轮战、星级；data/AbyssSeasonConfigs.json。")
    add_bullet(doc, "光锥/遗器：EquipmentAffixService；随机词条、强化、分解、套装；EquipmentAffixPool.json / RelicSetConfigs.json。")
    add_bullet(doc, "社交扩展：GiftService、GuildRedPacketService、VoiceSignalingService（赠礼/红包/语音信令）。")
    add_bullet(doc, "图鉴 / 好感 / 名片：HandbookService、AffinityService、PlayerCardService。")
    add_bullet(doc, "活动模板：ActivityTemplateService + ActivityTypeCatalog（转盘/拼图/积分兑换等）。")
    add_bullet(doc, "其他：RogueMapGenerator、CombatBalanceAnalyticsService、StoryAssetVersionService。")

    add_heading(doc, "7.22 玩家一天可能经历的主路径（故事化）", 2)
    add_body(
        doc,
        "为了让非技术同学建立整体画面，下面用一条「普通玩家的一天」串起系统："
        "登录进服（校验协议与能力位）→ 看邮件/好友 → 消耗体力进场景走动、触发 NPC 对话或过场 →"
        "遇敌打回合战（可带助战）→ 开挑战、Rogue、世界 BOSS 或深渊 → 回家园点点基建 → 抽卡与商店消费 →"
        "做每日任务/战令/版本活动拿奖 → 推主线章节解锁新地图 →"
        "进公会贡献/兑换/科技/公会战 → 看排行榜聊天 → 必要时问 AI 助手 → 改设置或提工单。"
        "运营同学则在后台改活动排期、导入配置、触发热更；运维同学盯监控与发布演练。",
    )
    add_bullet(doc, "玩家面：Netty/KCP 实时协议为主，强调手感与一致性。")
    add_bullet(doc, "运营面：HTTP Admin + data 配置热更，强调可改、可审计、可回滚。")
    add_bullet(doc, "建议面：AI/教练异步回答，失败可降级，不应阻断主玩法。")

    # ========== 八 ==========
    add_heading(doc, "八、网络与通信协议", 1)
    add_heading(doc, "8.1 端口与传输方式", 2)
    add_table(
        doc,
        ["通道", "默认端口", "说明"],
        [
            ["管理后台 HTTP", "8080", "浏览器/运维工具访问 Admin API"],
            ["游戏 TCP", "9000", "默认开启，可选手动开 TLS"],
            ["游戏 KCP（UDP）", "9000", "无 TLS；prod 用 lunarcore.kcp-crypto AES-GCM 会话加密"],
            ["AI sidecar（可选）", "18083", "内部问答接口，需 Internal Token"],
            ["chat / match（脚手架）", "18084 / 18085", "独立 HTTP 骨架，未挂入 api-gateway"],
            ["网关 HTTPS（脚手架）", "18443", "仅微服务联调，非主游戏口"],
        ],
    )
    add_body(
        doc,
        "生产环境若担心传输被窃听，应优先评估 TCP+TLS、专线或 VPN；"
        "UDP/KCP 本身没有 TLS，但生产强制开启 KCP AES-GCM 会话加密（见 docs/kcp-crypto.md）。",
    )

    add_heading(doc, "8.2 报文长什么样（通俗版）", 2)
    add_body(
        doc,
        "客户端与服务器约定了一种叫「Lunar 帧」的包装方式：前后有魔术数字防错乱，"
        "中间写明命令号、头长度、数据长度，真正的业务内容用 Protobuf 编码。"
        "可以把它想成：信封（帧头尾）+ 收件类型（命令号）+ 信纸内容（Protobuf）。",
    )
    add_code(
        doc,
        "magic | opcode | headerLen | dataLen | 可选扩展头 | Protobuf 载荷 | magic\n"
        "头魔数 0x9d74c714，尾魔数 0xd7a152c8；单包载荷上限约 4MB",
    )

    add_heading(doc, "8.3 协议如何分系统", 2)
    add_body(
        doc,
        "src/main/proto 下按系统拆分多个 .proto 文件（会话、大厅、战斗、场景、道具、抽卡、"
        "Rogue、挑战、活动、任务、匹配、AI、经济、角色、皮肤、设置、版本等）。"
        "命令号集中在 CmdIds，修改必须与客户端同步。包到达后由分发器按注解注册到对应 Handler，"
        "避免巨大的 switch-case。",
    )
    add_table(
        doc,
        ["号段（约）", "系统"],
        [
            ["68–83", "登录会话"],
            ["100–118", "大厅社交"],
            ["120–129", "组队 Party"],
            ["140–150", "经济"],
            ["160–173", "角色养成（已从旧 120–129 迁出，避免与组队冲突）"],
            ["200–212", "战斗"],
            ["300–351", "场景"],
            ["352–353", "切图加载完成握手"],
            ["400–450", "背包"],
            ["500–508", "抽卡"],
            ["550–599", "Rogue"],
            ["600–613", "挑战"],
            ["650–652", "活动"],
            ["700–708", "任务"],
            ["800–806", "匹配"],
            ["850–857", "家园"],
            ["860–868", "对话 / 过场"],
            ["900–921", "AI 助手"],
            ["930+", "设置 / 工单"],
            ["950–954", "成就"],
            ["960–964", "新手引导"],
            ["970–983", "公会基础"],
            ["984–989", "公会战"],
            ["990–995 / 1009", "战令"],
            ["996–1000", "体力"],
            ["1001–1005", "每日任务"],
            ["1006–1008", "主线章节"],
        ],
    )

    add_heading(doc, "8.4 协议版本兼容（非常重要）", 2)
    add_body(
        doc,
        "为避免新旧客户端「各说各话」，工程引入了协议线版本概念。"
        "当前 PROTOCOL_WIRE_VERSION = 2；ProtocolCompatService 会拒绝过旧的 v1 客户端。"
        "角色相关命令号已迁到 160–173，旧客户端若仍走 120–129 角色语义会撞上组队号段。"
        "因此：改协议必须同步客户端，并尽量用自动化测试（如 CmdIdUniquenessTest）防止号段再重叠。"
        "仓库还提供 buf.yaml，作为 Protobuf lint / breaking 检查基线。",
    )

    add_heading(doc, "8.5 连接保护", 2)
    add_bullet(doc, "连接包限流：防止恶意刷包拖垮进程。")
    add_bullet(doc, "背压处理：下游忙不过来时控制写入节奏。")
    add_bullet(doc, "幂等处理：降低重复请求导致的重复发奖等风险。")
    add_bullet(doc, "KCP 会话加密：KcpSessionCryptoCodec（lunarcore.kcp-crypto.enabled；prod 强制开启）。")
    add_bullet(doc, "KCP 拥塞与重传：KcpRetransmitAlgo（fixed/adaptive）+ KcpRttMonitor，弱网更稳。")
    add_bullet(doc, "协议 HMAC（可选）：ProtocolHmacSigner / ProtocolHmacCodec，命令号动态盐，防篡改。")
    add_bullet(doc, "TraceId 透传：NetTraceContext → 日志 MDC / X-Trace-Id，方便跨服务排查一次请求。")

    add_heading(doc, "8.6 给非程序员的一句话总结", 2)
    add_body(
        doc,
        "玩家手机/电脑通过「游戏专用通道」和服务器说话；运营人员通过「网页后台」管配置；"
        "两边都用编号好的「快递单」传消息。改协议就像改快递单格式——买卖双方必须一起换版本。",
    )

    # ========== 九 ==========
    add_heading(doc, "九、数据存储与配置热更", 1)
    add_heading(doc, "9.1 MySQL：玩家世界的「保险柜」", 2)
    add_body(
        doc,
        "账号、角色、背包、钱包等需要长期保存的数据放在 MySQL。"
        "开发环境常见库名 lunarcore，并可自动执行初始化 SQL；"
        "生产环境通过环境变量注入连接信息，且不再自动乱执行 init SQL。"
        "仓库内提供合并结构参考 lunarcore_merged.sql，以及 db/migration 增量脚本"
        "（如钱包流水、抽卡历史、皮肤字段、玩家数据版本等）。",
    )

    add_heading(doc, "9.2 data/ 目录：策划与运营的「可热更说明书」", 2)
    add_body(
        doc,
        "很多玩法数值不以硬编码写死在 Java 里，而是放在 data/ 下的 JSON、CSV、Excel。"
        "这样改活动、改卡池、改商店，往往不必重新发整包程序，只要走热更/导入流程。",
    )
    add_table(
        doc,
        ["文件/目录", "用途"],
        [
            ["ActivityScheduling.json / ActivityConfigs.json / activities/", "活动排期与详情"],
            ["Banners.json", "抽卡卡池"],
            ["ShopConfigs.json", "商店"],
            ["SkinConfigs.json", "皮肤"],
            ["QuestConfigs.json", "任务"],
            ["EncounterConfigs.json", "遇敌"],
            ["RogueMapGen.json", "模拟宇宙地图生成"],
            ["hotfix.json", "热更清单"],
            ["GuidePack / CoachTips / AssistSafetyRules / AssistFeatureContent", "AI/教练内容与安全词"],
            ["NewbieGuide.json", "新手引导步骤"],
            ["GuildShopConfigs.json", "公会商店"],
            ["GuildTechConfigs.json", "公会科技/全局 Buff"],
            ["DialogueTrees.json", "NPC 对话树"],
            ["CutsceneConfigs.json", "剧情过场"],
            ["HomeFacilityConfigs.json", "家园设施"],
            ["StaminaConfigs.json", "体力恢复与消耗"],
            ["DailyMissionConfigs.json", "每日任务"],
            ["BattlePassConfigs.json", "战令等级与奖励轨"],
            ["ChapterConfigs.json", "主线章节与解锁门闸"],
            ["VersionActivityConfigs.json", "版本活动树形节点"],
            ["ActivityTypeCatalog.json", "活动类型目录（签到/限时挑战/爬塔等）"],
            ["WorldBossConfigs.json", "世界 BOSS"],
            ["AbyssSeasonConfigs.json", "深渊/忘却之庭赛季"],
            ["EquipmentAffixPool.json / RelicSetConfigs.json", "光锥遗器词条与套装"],
            ["ChatSensitiveWords.json", "聊天敏感词（Admin 可热更）"],
            ["items_config.csv / activity_template.xlsx", "导入素材"],
            ["avatar_usage_stats.json", "角色使用统计"],
        ],
    )

    add_heading(doc, "9.3 Redis：多机共用的「白板」（可选）", 2)
    add_body(
        doc,
        "默认可以不开 Redis。一旦要做双节点切服、共享在线 UID、跨进程排行榜或世界聊天广播，"
        "就应打开 lunarcore.redis.enabled，并配置主机端口与键前缀。"
        "关闭时，相关能力退回进程内存实现，适合单机开发。"
        "生产加固后还支持：跨节点组队（RedisPartyStore）、跨节点私聊订阅（lunar:chat:private）等。"
        "匹配侧则有分段桶、超时踢出、可选机器人补位，并上报成功率指标。",
    )

    add_heading(doc, "9.4 热更新怎么工作（通俗）", 2)
    add_body(
        doc,
        "可以把热更想成「换一本新说明书，但不要把正在营业的店关掉」。"
        "协调器分阶段加载新配置；若中途失败，回滚到旧的内存指针；成功则广播通知并留下审计。"
        "控制台 stdin 输入 /reload 之类的能力在生产默认关闭，避免误操作。"
        "更进一步，配置发布可写入 config_release 指纹表（ConfigReleaseService），"
        "支持版本追溯与回滚思路，详见 docs/config-versioning.md；"
        "策划同学可优先阅读 docs/planner-config-guide.md。",
    )
    add_bullet(doc, "核心类：HotReloadCoordinator、HotfixDataService、ConfigImportService、ConfigPublishAuditService、ConfigReleaseService")
    add_bullet(doc, "后台接口示例：POST /api/admin/ops/reload")
    add_bullet(doc, "相关文档：docs/config-versioning.md、docs/planner-config-guide.md")

    # ========== 十 ==========
    add_heading(doc, "十、AI 助手与教练系统", 1)
    add_body(
        doc,
        "AI 模块的定位是「游戏内向导」，不是替代游戏逻辑的裁判。"
        "默认启用规则教练（按配置提示），本地大模型与远程 sidecar 默认关闭。"
        "处理链路大致是：安全分类 → 本地规则/FAQ/缓存快路径 → 拼装玩家上下文与多轮历史 →"
        "可选远程/本地 LLM → 出站清洗、合规声明、缓存、审计、配额与埋点。"
        "近期已按「对话体验 / 业务联动 / 架构性能 / 多语言运维 / 合规」五条线补强，"
        "下列小节说明当前能力边界与实现入口。",
    )
    add_heading(doc, "10.1 能帮玩家做什么", 2)
    add_bullet(doc, "任务与探索引导、环境旁白类提示")
    add_bullet(doc, "已有角色阵容推荐（可附 OPEN_LINEUP 等结构化快捷指令，供客户端一键高亮/应用）")
    add_bullet(doc, "知识检索（RAG）回答设定/玩法问题；配置热更后优先增量刷新语料")
    add_bullet(doc, "多轮对话：会话级最近 N 轮历史 + 超长摘要压缩（Redis/内存，带 TTL）")
    add_bullet(doc, "个性化建议：常用角色、未完成任务、体力与等级画像拼入上下文")
    add_bullet(doc, "主动教练推送：连败、主线卡关、体力满溢等场景触发系统提示")
    add_bullet(doc, "聊天中 @助手 自动转问；有用/无用反馈回传埋点与指标")
    add_bullet(doc, "战斗特征采集后的辅助建议（可开关）；多语言 locale / Accept-Language")
    add_bullet(doc, "回答附带合规免责声明（助手建议仅供参考，以游戏内结果与官方说明为准）")

    add_heading(doc, "10.2 故障隔离与高可用", 2)
    add_body(
        doc,
        "远程 ai-assist-service 挂了、超时（建议约 3s）或触发本地熔断时，"
        "游戏服回退到进程内规则教练/本地逻辑，保证「游戏还能玩」。"
        "同问相似缓存（Jaccard≥约 0.9）可进一步减少远程调用。"
        "内部调用需要 X-Internal-Token，避免外网随便打问答接口。",
    )
    add_bullet(doc, "主工程：AiAssistApplicationService、CoachRuleEngine、AssistSafetyFilter、AiAssistClient（含熔断）")
    add_bullet(doc, "多轮/画像/主动推送：AssistConversationHistoryService、AssistPlayerProfileService、AssistProactiveCoachService")
    add_bullet(doc, "策略灰度：AssistStrategyGrayService（UID 尾号切换 prompt/策略；fallback 率过高自动回滚）")
    add_bullet(doc, "sidecar：POST /internal/ai/ask；健康检查 /ai/health")
    add_bullet(doc, "网关路径：/v1/ai/**（含熔断）与旧路径 /ai/** 兼容；失败走 AiAssistFallbackController")

    add_heading(doc, "10.3 内容、安全与合规边界", 2)
    add_body(
        doc,
        "入站安全在关键词/正则（AssistSafetyRules）之外增加轻量语义意图分类，"
        "降低绕过词表变体的风险；出站仍做敏感句清洗。"
        "送入 LLM 前对 UID/手机号/邮箱等做 PII 脱敏（AssistPiiRedactor）；"
        "审计日志使用 uidHash，默认不落完整隐私原文。"
        "配额按教练/LLM 分桶，并支持等级/VIP 差异化日预算与「同一问题」短时限流。"
        "内容素材来自 GuidePack、CoachTips、AssistFeatureContent 等配置，"
        "热更时可增量刷新 RAG。记住：AI 给出的是建议，真正扣费、开战、发奖仍由游戏权威逻辑决定。",
    )
    add_bullet(doc, "语义安全：AssistSemanticSafetyClassifier（隐私探测/外挂/代充/买卖号等意图）")
    add_bullet(doc, "反馈闭环：AssistFeedbackCsReq → AssistFeedbackService → AnalyticsEventPublisher.ai_feedback")
    add_bullet(doc, "协议扩展：AskAiAssist 支持 session_id/locale；响应与推送带 disclaimer/strategy_version")

    add_heading(doc, "10.4 可观测性与环境隔离", 2)
    add_body(
        doc,
        "BusinessMetrics 增加 AI 专项指标：请求计数（success/fallback/blocked/error）、"
        "延迟直方图、缓存命中比、反馈有用/无用。"
        "Prometheus 告警示例：fallback 率 >10%、P95 延迟 >2s。"
        "对话 Redis key 使用 lunarcore.ai-assist.env-key-prefix（dev/prod）隔离，"
        "避免开发知识库/会话污染生产。",
    )
    add_table(
        doc,
        ["能力面", "代表类 / 配置", "说明"],
        [
            ["多轮历史", "AssistConversationHistoryService", "最近 N 轮 + 摘要；Redis TTL"],
            ["个性化", "AssistPlayerProfileService / AssistContextBuilder", "常用角色、未完成任务"],
            ["主动推送", "AssistProactiveCoachService", "连败/卡关/体力满 → AiHintNotify"],
            ["熔断降级", "AssistRemoteCircuitBreaker / AiAssistClient", "失败快速回退规则教练"],
            ["策略灰度", "AssistStrategyGrayService", "UID 尾号 + 自动回滚"],
            ["多语言", "AssistLocaleService", "zh-CN / en-US prompt 与文案"],
            ["配额", "AssistQuotaLimiter", "免费/VIP 日预算 + 同问限流"],
            ["RAG", "RagKnowledgeService.reloadPreferIncremental", "热更增量优先，失败全量"],
            ["指标告警", "BusinessMetrics + business-alerts.yml", "ai_requests / latency / cache"],
        ],
    )
    add_note(
        doc,
        "关键配置见 lunarcore.ai-assist.*（如 conversation-history-*、free/vip-daily-llm-budget、"
        "remote-circuit-*、strategy-version / gray-strategy-*、env-key-prefix、compliance-disclaimer 等）。",
    )

    # ========== 十一 ==========
    add_heading(doc, "十一、管理后台与运维能力", 1)
    add_body(
        doc,
        "管理后台走 HTTP，与玩家游戏口隔离。登录使用 Session，并有 CSRF 与 RBAC 权限控制。"
        "常见能力包括：热更触发、配置导入、中心路由计划查询、客服工单处理、登录失败锁定等。",
    )
    add_table(
        doc,
        ["能力", "代表入口", "说明"],
        [
            ["后台登录与权限", "AdminLoginController、AdminSecurityConfig", "保护 /api/admin/**"],
            ["热更操作", "HotReloadController", "需相应写权限"],
            ["配置导入", "ConfigImportController", "活动等素材入库/落盘"],
            ["配置版本", "ConfigVersionController", "列表/对比/回滚影响；/admin/config-versions.html"],
            ["错误码目录", "ErrorCodeCatalogController", "GET /api/admin/error-codes"],
            ["聊天审核", "ChatModerationAdminController", "禁言/踢人/重载敏感词"],
            ["敏感操作审批", "SensitiveOpApprovalController", "存档等高危操作二次审批"],
            ["客服工单", "SupportTicketAdminController", "处理玩家反馈"],
            ["中心计划", "CenterPlanController", "内部查询 Plane/Floor 规划"],
            ["玩家存档导出导入", "PlayerDataExportController", "客服/运维处理异常存档"],
            ["一键回滚", "OpsRollbackController", "需 confirm=ROLLBACK 防误触"],
            ["内部鉴权", "InternalApiAuthFilter", "校验 X-Internal-Token"],
        ],
    )
    add_body(
        doc,
        "发布前建议跑通发布演练脚本 scripts/publish_drill.ps1，"
        "或运行 PublishDrillTest 等关键测试，确认编译、热更、Center、匹配等核心路径仍健康。"
        "管理操作（热更、导入、工单、回滚等）会写入 admin_audit_log，便于事后追责与回放；"
        "归档任务可按策略清理过期审计日志。",
    )

    # ========== 十二 可观测性 ==========
    add_heading(doc, "十二、可观测性、埋点与生产加固", 1)
    add_body(
        doc,
        "「能跑」和「能稳定运营」中间还隔着一层：看得见问题、扛得住峰值、停机不丢关键状态、"
        "钱和奖发得可追溯。本章对应 docs/production-hardening.md 的落地基线，"
        "用白话说明运维与研发共同关心的那部分。",
    )

    add_heading(doc, "12.1 监控与告警：给系统装仪表盘", 2)
    add_body(
        doc,
        "主工程通过 BusinessMetrics 暴露业务指标到 /actuator/prometheus。"
        "可观察登录失败、抽卡异常、战斗超时、匹配成功率，以及 AI 助手请求终态、延迟与缓存命中等。"
        "配套部署目录 deploy/prometheus、deploy/grafana，Compose 可一键拉起 Prometheus(:9090)"
        "与 Grafana(:3000，默认 admin/admin，看板 MyLunarCore Business)。"
        "告警规则示例见 deploy/prometheus/rules/business-alerts.yml"
        "（含 AI fallback 率、AI P95 延迟等）。"
        "压测脚本 scripts/load/k6_baseline.js 用于回填真实阈值，文档里的百分比只是初值。",
    )
    add_table(
        doc,
        ["项", "普通人理解", "落地位置"],
        [
            ["业务指标", "看今天有没有人刷登录、抽卡是否异常", "BusinessMetrics → Prometheus"],
            ["AI 助手指标", "问答成功/降级/拦截、延迟、缓存命中、反馈有用率", "lunarcore.ai.* + ai_ask/ai_feedback 埋点"],
            ["Grafana 看板", "可视化大屏", "deploy/grafana"],
            ["告警规则", "超阈值自动喊人", "business-alerts.yml（含 AI 规则）"],
            ["优雅停机", "关门前先清客人，不要硬拔电源", "GracefulShutdownCoordinator"],
            ["日志脱敏", "日志里别把密码原样打印出来；AI 入参另有 PII 脱敏", "SensitiveDataMasker + AssistPiiRedactor"],
            ["压测基线", "先量一下能扛多少人", "scripts/load/k6_baseline.js"],
        ],
    )
    add_bullet(doc, "建议初值（需压测校准）：登录失败 warning >1%/5m、critical >5%/5m；抽卡异常 >2%/5m。")
    add_bullet(doc, "AI 建议初值：fallback 率 >10%/10m；P95 延迟 >2s/10m；error 速率突增 critical。")
    add_bullet(doc, "Zone 默认人数上限约 300（可按 Tick 耗时自适应下调）；匹配队列超时默认约 60 秒。")

    add_heading(doc, "12.2 数据一致性与防重放：让「钱和奖」可追溯", 2)
    add_body(
        doc,
        "抽卡等关键写路径会写入本地事务日志（表 local_tx_log + LocalTxLogService），"
        "方便排查「扣了没有发」类问题。另有防重放表 biz_replay_guard，"
        "用于 IAP 渠道交易号、抽卡 nonce 等，降低重复提交刷奖风险。"
        "数据归档任务 DataArchiveJob 可按 lunarcore.data-retention.* 配置清理/归档历史（默认关闭）。"
        "相关 SQL：src/main/resources/db/migration_p2_production_hardening.sql。"
        "若将来拆成多服务，本地事务日志可升级为 Outbox → 消息队列 → 对端消费；"
        "当前单体仍主要靠数据库事务（@Transactional）。",
    )

    add_heading(doc, "12.3 运营埋点与分析仓", 2)
    add_body(
        doc,
        "AnalyticsEventPublisher 输出结构化 analytics {...} 日志，便于采集。"
        "除登录/抽卡/战斗/内购外，已补充 ai_ask（问答来源、场景、缓存命中、策略版本）"
        "与 ai_feedback（有用/无用、原因码）事件，供 AI 质量看板与策略灰度评估。"
        "可选启用 ClickHouse（docker compose --profile analytics），"
        "建表脚本在 deploy/clickhouse/init.sql。",
    )

    add_heading(doc, "12.4 优雅停机在做什么（故事版）", 2)
    add_body(
        doc,
        "想象商场要打烊：先停止新客人进匹配队列 → 中止尚未结算完的战斗并妥善收尾 →"
        "停止网络监听 → 把该落盘的数据落盘。这样比重启时直接杀进程更安全，"
        "也减少玩家「打到一半掉线且状态诡异」的投诉。",
    )

    # ========== 十三 ==========
    add_heading(doc, "十三、安全机制与上线注意事项", 1)
    add_body(
        doc,
        "安全不是「加一个密码」这么简单。本项目在开发便利与生产安全之间做了分层："
        "开发可以用宽松配置快速联调；一旦启用 prod，会主动拒绝一批明显危险的默认值。",
    )
    add_table(
        doc,
        ["机制", "普通人理解", "要点"],
        [
            ["Admin Session + CSRF + RBAC", "后台要登录，且不能越权乱点", "保护运维接口"],
            ["Internal Token", "服务之间的「对暗号」", "/internal/** 与 AI 内部问答"],
            ["ProductionSecretsValidator", "上线安检门", "拒弱数据库密码、dev-internal-token 等"],
            ["IAP Mock 开关", "假支付只能练手", "prod 必须关闭 Mock"],
            ["微服务 JWT 密钥", "网关通行证签名钥匙", "默认 change-me 仅本地"],
            ["Cookie 策略（prod）", "浏览器饼干更严", "HttpOnly / Secure / SameSite"],
            ["管理端口分离（可选）", "监控口与业务口分开", "减小暴露面"],
            ["KCP 会话加密", "游戏 UDP 通道加一层暗号", "prod 强制 lunarcore.kcp-crypto.enabled=true"],
            ["管理审计日志", "谁在后台点了什么都留痕", "admin_audit_log"],
            ["环境变量注入密钥", "生产密钥不写死在配置文件", "INTERNAL_API_TOKEN / DB_PASSWORD 等"],
        ],
    )
    add_heading(doc, "13.1 上线前必做清单（建议打印核对）", 2)
    add_bullet(doc, "使用 spring.profiles.active=prod（或等价方式）。")
    add_bullet(doc, "用环境变量注入并轮换：DB_PASSWORD、INTERNAL_API_TOKEN；脚手架侧还要轮换 AUTH_JWT_SECRET、GATEWAY_JWT_SECRET、AI_ASSIST_INTERNAL_TOKEN。")
    add_bullet(doc, "关闭 mylunarcore.iap.mock-verify，按 docs/iap-sandbox.md 配置 Apple/Google 真渠道。")
    add_bullet(doc, "确认 prod 下 KCP 加密已开启；评估 TCP TLS 与管理端口隔离。")
    add_bullet(doc, "多节点场景：Redis 必开，center.mode=remote，广告地址配置正确；对照 docs/multi-node-failover.md。")
    add_bullet(doc, "跑通 publish_drill，检查热更审计、配置版本与关键回归测试。")
    add_bullet(doc, "确认演示账号密码（如 123456/admin123）不可出现在生产。")
    add_bullet(doc, "确认客户端协议线版本与 CmdId 已对齐 PROTOCOL_WIRE_VERSION=2。")
    add_bullet(doc, "对照 docs/production-hardening.md 核对监控、归档、脱敏与告警阈值。")

    # ========== 十四 ==========
    add_heading(doc, "十四、微服务脚手架现状", 1)
    add_body(
        doc,
        "microservices/ 目录的官方定位非常明确：迁移脚手架，不是生产身份证中心（IdP），"
        "也不是完整玩家域。它适合做拆分试验、网关联调、AI sidecar 旁路，"
        "不适合「关掉单体、只启动脚手架就开服」。",
    )
    add_table(
        doc,
        ["模块", "默认端口", "职责", "生产可用？"],
        [
            ["api-gateway", "18443 HTTPS", "路由鉴权、限流、AI 降级（挂 auth/player/ai）", "仅联调，需换密钥证书"],
            ["auth-service", "18081", "演示登录发 JWT", "否（脚手架）"],
            ["player-service", "18082", "玩家查询占位（scaffoldOnly）", "否"],
            ["ai-assist-service", "18083", "内部 AI 问答 + health", "可旁路联调，需强 Token"],
            ["chat-service", "18084", "ChatApi 世界/私聊内存队列骨架", "否；未挂入网关"],
            ["match-service", "18085", "MatchApi 匹配队列骨架", "否；未挂入网关"],
            ["common-api / match-api / chat-api", "—", "共享 DTO 与契约", "作为库使用"],
            ["battle-service", "—", "未列入 microservices/pom；无源码，仅可能残留历史 target/", "否，勿当作可用服务"],
        ],
    )
    add_heading(doc, "14.1 基础设施与启动顺序", 2)
    add_body(
        doc,
        "docker compose -f microservices/docker-compose.yml up -d 可拉起 Nacos、MySQL、Redis 等依赖；"
        "也可再拉起 Prometheus/Grafana。ClickHouse 分析仓用 profile analytics。"
        "最小网关联调启动顺序：auth → player → ai-assist → api-gateway。"
        "完整脚手架可另启 chat-service(:18084)、match-service(:18085)（独立 HTTP，当前不经网关）。"
        "联调健康检查示例：网关上的 /auth/health、/players/{uid}、/ai/health；"
        "版本化路径 /v1/auth/**、/v1/players/**、/v1/ai/** 与旧路径并存。",
    )

    add_heading(doc, "14.2 哪些适合先拆、哪些不要急着拆", 2)
    add_body(
        doc,
        "箱庭回合制战斗虽是 CPU 密集，但不涉及高频位置同步，单体完全可扛。"
        "开服活动洪峰最大且允许最终一致的，优先拆「匹配大厅」与「世界聊天」；"
        "其他高一致写路径（战斗/场景/钱包）保留单体，可显著降低分布式事务风险。",
    )
    add_table(
        doc,
        ["候选能力", "与游戏循环关系", "建议", "原因（白话）"],
        [
            ["匹配大厅", "低–中", "优先可拆（洪峰）", "开服流量尖峰，最终一致即可"],
            ["世界聊天", "低", "优先可拆（洪峰）", "社交洪峰，允许丢序/最终一致"],
            ["Admin / 配置热更", "低", "可拆", "办公室业务，不必绑在战斗心跳上"],
            ["排行榜", "低–中", "其次", "最终一致通常可接受"],
            ["AI 助手", "低", "Sidecar", "只是建议，挂了可回退"],
            ["Auth / 账号", "中", "稍后", "脚手架已有雏形，但未达生产"],
            ["钱包 / 抽卡写路径", "高", "暂不拆", "钱和奖必须同一套规则"],
            ["战斗 / 场景", "高", "暂不拆", "箱庭回合制单体可扛；先证明 Center"],
        ],
    )

    # ========== 十五 ==========
    add_heading(doc, "十五、成熟度评估与已知局限", 1)
    add_heading(doc, "15.1 已经比较像样的能力（Phase 0–2 最小集方向）", 2)
    add_bullet(doc, "模块化单体游戏核：登录、场景、回合战、挑战、Rogue、抽卡、商店/IAP、任务活动、匹配皮肤等。")
    add_bullet(doc, "日常循环：体力、每日任务、战令、主线章节门闸、版本活动容器（DailyLoop/BattlePass 协议已接线）。")
    add_bullet(doc, "缺口补齐玩法：世界 BOSS、深渊赛季、光锥遗器词条、赠礼/红包、图鉴、好感、名片、活动模板等。")
    add_bullet(doc, "可选 Redis：票据、在线表、排行榜、世界聊天、跨节点组队/私聊能力。")
    add_bullet(doc, "Zone 租约心跳与人数上限（默认约 300，可按 Tick 耗时自适应下调）；Global / Zone / Battle 分时钟。")
    add_bullet(doc, "移动反作弊、抽卡扣费流水、本地事务日志与防重放；切图 LoadingTicket 防半截进图。")
    add_bullet(doc, "组队协议与多人共战/助战；成就、新手引导、公会基础 + 公会战 + 公会科技。")
    add_bullet(doc, "Admin 热更/导入/审计/配置版本/聊天审核/敏感审批、Center local/remote、AI 规则教练 + 多轮/画像/主动推送/熔断灰度与 sidecar 旁路。")
    add_bullet(doc, "prod 弱密钥与 IAP Mock 的 fail-fast 护栏；prod 强制 KCP 加密；Prometheus/Grafana 业务看板基线；CI 协议 lint。")

    add_heading(doc, "15.2 明确局限（写进报告是为了避免误判）", 2)
    add_bullet(doc, "可运行主业务在单体；微服务 auth/player 是演示桩；chat/match 仅为独立 HTTP 骨架且未挂网关。")
    add_bullet(doc, "无缝 Cell / 实时战斗仍是实验骨架，默认关闭。")
    add_bullet(doc, "公会基础 + 公会战闭环已落地；部分客户端协议消息体仍可能需联调补全。")
    add_bullet(doc, "对话/过场/家园及缺口补齐玩法：服务端已加深；客户端表现层可继续对齐。")
    add_bullet(doc, "协议已迁到 wire v2，旧客户端必须升级；改 CmdId 必须与客户端同步。")
    add_bullet(doc, "KCP 本身无 TLS（prod 强制会话加密）；可选命令号 HMAC；误用默认 JWT/演示密码上线有风险。")
    add_bullet(doc, "真 IAP 需配齐渠道密钥；battle-service 未列入脚手架模块，勿当作可用服务。")
    add_bullet(doc, "压测参考见 docs/load-test-baseline.md；目标硬件须用 k6 回填，文档初值不可直接当 SLA。")
    add_bullet(doc, "钱包多节点依赖 Redis 分布式锁；仅 DB 事务在跨节点并发下仍有双扣风险窗口。")
    add_bullet(doc, "配置灰度已有 ConfigGrayRelease；卡池等业务读路径需显式按 inGray 分支。")

    add_heading(doc, "15.3 成熟度雷达（定性，非精确打分）", 2)
    add_table(
        doc,
        ["维度", "成熟度观感", "说明"],
        [
            ["单机玩法闭环", "较高", "养成-战斗-抽卡-日常/战令-活动主路径较完整"],
            ["运维热更", "中高", "有协调、导入与审计，仍需流程纪律"],
            ["可观测与加固", "中高", "指标/告警/事务日志已有基线，阈值待压测"],
            ["多节点扩展", "中", "有 Center/Redis 路径，需压测验证"],
            ["微服务替代主工程", "低", "脚手架阶段"],
            ["无缝大世界/实时动作", "低", "非当前主定位，实验开关"],
            ["生产安全默认值", "中高（有护栏）", "取决于是否真正启用 prod 与轮换密钥"],
        ],
    )

    # ========== 十六 ==========
    add_heading(doc, "十六、后续发展建议", 1)
    add_heading(doc, "16.1 短期（巩固可运营闭环）", 2)
    add_bullet(doc, "继续以模块化单体打磨崩铁式体验与数值内容。")
    add_bullet(doc, "把 Admin/热更、排行榜、AI 旁路做成更稳定的可独立部署件。")
    add_bullet(doc, "坚持发布演练、热更审计，并把 Grafana 告警阈值用压测校准。")
    add_bullet(doc, "清理或明确标注演示密钥，避免环境误用。")
    add_bullet(doc, "补齐战斗/抽卡等专用压测场景与关键集成测试（如 Testcontainers）。")

    add_heading(doc, "16.2 中期（验证多节点）", 2)
    add_bullet(doc, "在压测下验证 center.mode=remote + 共享 Redis 的切服与在线一致性。")
    add_bullet(doc, "完善大厅社交与匹配体验，明确跨节点能力边界。")
    add_bullet(doc, "按需评估 TCP+TLS、KCP 加密与管理面网络隔离。")

    add_heading(doc, "16.3 长期（有条件再拆高一致性域）", 2)
    add_bullet(doc, "仅在写路径边界清晰、共享协议成熟后，再考虑拆玩家聚合、战斗/场景等。")
    add_bullet(doc, "本地事务日志可演进为 Outbox + MQ，为拆服务做准备。")
    add_bullet(doc, "若产品目标转向无缝大世界，需要单独的世界分片与实时权威改造，不能只靠拆微服务。")

    # ========== 十七 ==========
    add_heading(doc, "十七、给不同角色读者的阅读指引", 1)
    add_table(
        doc,
        ["角色", "建议重点阅读", "你可以带走的结论"],
        [
            ["产品 / 策划", "第三、七、十五、二十章", "这是崩铁式私服核，内容与活动热更友好"],
            ["程序新人", "第二、五、六、八、十八、十九章", "先跑主工程，别被 microservices 带偏"],
            ["运维 / 安全", "第九、十一、十二、十三、十八章", "prod、密钥、IAP、监控、发布演练是底线"],
            ["架构决策者", "第五、十四、十五、十六章", "单体 + 选择性旁路，先 Center 后硬拆"],
            ["投资人/非技术领导", "第二、三、十五、十九、二十章", "可演示的游戏服主体已在，微服务仍是试验"],
        ],
    )

    # ========== 十八 ==========
    add_heading(doc, "十八、附录：常用命令与配置速查", 1)
    add_heading(doc, "18.1 主工程启动", 2)
    add_code(
        doc,
        "# 1) 一键拉起 MySQL + Redis + 初始化 SQL\n"
        "docker compose -f docker-compose.dev.yml up -d\n"
        "\n"
        "# 2) 按需修改 src/main/resources/application.properties 后启动\n"
        "mvn -DskipTests spring-boot:run\n"
        "\n"
        "# Admin HTTP 默认 8080\n"
        "# 游戏口默认 lunarcore.netty-port=9000\n"
        "# Profile：dev / test / prod（prod 会 fail-fast 拒绝弱密钥）",
    )

    add_heading(doc, "18.2 发布演练", 2)
    add_code(
        doc,
        ".\\scripts\\publish_drill.ps1\n"
        "# 或运行测试 PublishDrillTest 等",
    )

    add_heading(doc, "18.3 微服务脚手架与监控", 2)
    add_code(
        doc,
        "docker compose -f microservices/docker-compose.yml up -d\n"
        "docker compose -f microservices/docker-compose.yml up -d prometheus grafana\n"
        "cd microservices\n"
        "mvn -U clean package\n"
        "# 网关联调：auth → player → ai-assist → api-gateway\n"
        "# 可选另启：chat-service :18084、match-service :18085（不经网关）\n"
        "# Prometheus :9090  Grafana :3000（admin/admin）",
    )

    add_heading(doc, "18.4 关键配置项（摘录）", 2)
    add_table(
        doc,
        ["配置", "含义"],
        [
            ["lunarcore.netty-port", "游戏口，默认 9000"],
            ["server.port", "Admin HTTP，默认 8080"],
            ["lunarcore.data-dir", "配置目录，默认 data"],
            ["lunarcore.center.mode", "local 单机 / remote 多节点"],
            ["lunarcore.redis.enabled", "是否启用共享 Redis"],
            ["lunarcore.internal-api-token / INTERNAL_API_TOKEN", "内部接口共享密钥（prod 须环境变量）"],
            ["mylunarcore.iap.mock-verify", "IAP 是否 Mock（prod 必须 false）"],
            ["lunarcore.ai-assist.remote-enabled", "是否调用远程 AI sidecar"],
            ["lunarcore.ai-assist.env-key-prefix", "AI Redis/会话环境前缀（dev/prod 隔离）"],
            ["lunarcore.ai-assist.free-daily-llm-budget / vip-daily-llm-budget", "免费/高等级玩家每日 LLM 配额"],
            ["lunarcore.ai-assist.strategy-version / gray-strategy-*", "AI 策略基线与 UID 尾号灰度"],
            ["lunarcore.ai-assist.compliance-disclaimer*", "合规免责声明开关与文案"],
            ["lunarcore.zone.max-players", "Zone 人数上限"],
            ["lunarcore.kcp-crypto.enabled", "KCP 会话加密（prod 强制 true）"],
            ["lunarcore.data-retention.*", "数据归档策略（默认关）"],
            ["lunarcore.world.*", "无缝/实时战斗实验开关（默认关）"],
        ],
    )

    add_heading(doc, "18.5 双节点切服最小提示", 2)
    add_code(
        doc,
        "lunarcore.redis.enabled=true\n"
        "lunarcore.redis.host=127.0.0.1\n"
        "lunarcore.redis.port=6379\n"
        "lunarcore.center.mode=remote\n"
        "lunarcore.center.remote-base-url=http://center-host:8080\n"
        "lunarcore.center.advertise-host=<本机对外IP>\n"
        "lunarcore.center.advertise-port=9000",
    )

    add_heading(doc, "18.6 仓库文档索引", 2)
    add_table(
        doc,
        ["文档", "适合谁读", "内容"],
        [
            ["README.md", "所有人", "定位、快速启动、关键配置"],
            ["microservices/README.md", "架构/后端", "脚手架边界与拆分建议"],
            ["docs/production-hardening.md", "运维/安全", "生产加固基线"],
            ["docs/load-test-baseline.md", "运维/测试", "压测结论与回填清单"],
            ["docs/planner-config-guide.md", "策划", "配置怎么改"],
            ["docs/config-versioning.md", "运维/策划", "配置版本与回滚"],
            ["docs/protocol-docs.md", "客户端/服务端", "协议文档生成"],
            ["docs/iap-sandbox.md", "支付联调", "IAP 沙盒步骤"],
            ["docs/multi-node-failover.md", "运维", "跨节点故障转移清单"],
            ["docs/feature-gap-fill.md", "产品/研发", "玩法与技术缺口补齐说明"],
            ["docs/kcp-crypto.md", "网络/安全", "KCP AES-GCM / TLS 说明"],
            ["docs/backup-recovery.md", "运维", "备份与恢复演练"],
            ["docs/release-process.md", "研发/运维", "版本发布与回滚"],
            ["docs/error-codes.md", "全员联调", "业务错误码手册"],
            ["docs/observability-logging.md", "运维", "Loki/链路追踪"],
            ["docs/network-isolation.md", "运维/安全", "Admin/游戏口网络隔离"],
            ["docs/admin-api.md", "联调", "Admin HTTP 接口摘要"],
            ["QUICKSTART.md", "新人", "最小启动步骤与 FAQ"],
            ["docs/adr/0001～0003", "架构/全员", "单体优先、KCP 加密、密钥注入决策"],
            ["docs/microservice-split-roadmap.md", "架构", "微服务拆分阶段路线图"],
            ["docs/archive-strategy.md", "运维", "冷热数据归档策略"],
            ["docs/secret-rotation.md", "安全/运维", "内部 Token 等密钥轮换"],
            ["docs/ops-daily-checklist.md", "运维", "日常巡检 SOP"],
            ["docs/load-test-plan.md", "测试", "压测计划与场景"],
            ["docs/protocol-migration-guide.md", "客户端/服务端", "协议迁移说明"],
        ],
    )

    add_heading(doc, "18.7 打开本 Word 后如何刷新目录页码", 2)
    add_body(
        doc,
        "本文件已插入 Word「自动目录」域与页脚「PAGE」域。"
        "用 Microsoft Word 或 WPS 打开后：若弹出「是否更新域」，请选是；"
        "也可右键目录 →「更新域」→「更新整个目录」。更新后，目录会显示各章真实页码，"
        "页脚也会显示正确的「第 N 页」。",
    )

    # ========== 十九 ==========
    add_heading(doc, "十九、术语小词典（给非技术同学）", 1)
    add_body(
        doc,
        "读技术报告时最卡人的往往不是「逻辑」，而是「名词」。"
        "下面把本报告里反复出现的词翻译成人话。遇到陌生英文缩写，可先回查本章。",
    )
    add_table(
        doc,
        ["术语", "人话解释"],
        [
            ["单体 / 模块化单体", "一个主程序里分很多部门，但仍在同一座大楼办公"],
            ["微服务", "把部门拆成独立小公司；沟通成本更高，要谨慎拆"],
            ["脚手架", "盖楼前搭的架子：能演示结构，但不能当正式大楼住人"],
            ["Netty / KCP", "玩家客户端连游戏服的高速通道（一种网络通信技术）"],
            ["Protobuf / CmdId", "双方约定的报文格式与「消息种类编号」"],
            ["Plane / Floor / Zone", "地图分区与服务器上的场景房间实例"],
            ["AOI", "只同步你附近看得见的人/怪，省流量"],
            ["回合制战斗", "你一步我一步的战斗，不是实时搓招动作战"],
            ["Rogue / 模拟宇宙", "随机地图、一路强化、可重复挑战的玩法模式"],
            ["IAP", "手机内购；服务器要验真小票，不能只信客户端"],
            ["热更", "不关服（或尽量少停服）就换配置/部分逻辑"],
            ["Redis", "多台机器共享的高速小黑板"],
            ["Center 路由", "总调度：决定某个场景归哪台游戏服管"],
            ["Sidecar", "挂在旁边的辅助小服务（如 AI），挂了主业务还能跑"],
            ["JWT / Token", "通行证；丢了或被猜中就危险，所以密钥要轮换"],
            ["prod Profile", "生产模式：更严格，会拒绝一批危险默认配置"],
            ["Prometheus / Grafana", "收集指标 + 画成看板的监控组合"],
            ["fail-fast", "一发现配置危险就立刻拒绝启动，而不是带病运行"],
            ["权威逻辑在服务端", "真正扣钱、开战、发奖由服务器说了算，客户端只是遥控器"],
            ["热更回滚", "新说明书装坏了，还能换回旧的那本"],
            ["灰度发布", "先让一小部分玩家/服务器用新配置，确认没问题再全开"],
            ["分布式锁", "多台机器抢着改同一笔钱包时，先举手排队，避免双扣"],
            ["Wire Version", "协议「方言版本号」；太旧的客户端会被礼貌拒绝"],
        ],
    )

    # ========== 二十 ==========
    add_heading(doc, "二十、如何向他人讲解本项目（电梯演讲稿）", 1)
    add_body(
        doc,
        "如果你只有一分钟向领导、同事或客户介绍 MyLunarCore，可以按下面四句说："
        "（1）这是一套崩坏星穹铁道风格的游戏服务端：分区场景探索，遇敌进回合战，并带抽卡、商店、活动。"
        "（2）真正能开服的是根目录「一个主程序」；旁边的 microservices 只是拆分试验，不能单独当生产账号系统。"
        "（3）运营改活动、卡池、任务主要靠 data 配置和后台热更；AI 助手是可选旁路，挂了游戏照样玩。"
        "（4）要上线必须切生产模式、换掉默认密钥、关掉假支付，并接上监控告警。",
    )
    add_heading(doc, "20.1 三分钟版（稍展开）", 2)
    add_bullet(doc, "玩家：登录 → 场景 → 战斗 → 养成/抽卡/活动 → 社交/公会 → 可选问助手。")
    add_bullet(doc, "技术：Java 17 + Spring Boot 4 + Netty/KCP + Protobuf + MySQL，可选 Redis 做多节点共享。")
    add_bullet(doc, "运维：8080 管后台，9000 跑游戏；Prometheus/Grafana 看业务健康；发布前跑 publish_drill。")
    add_bullet(doc, "边界：不做无缝大世界主路线；不先拆战斗/钱包；优先拆匹配与世界聊天这类洪峰社交能力。")

    add_heading(doc, "20.2 常见问答（FAQ）", 2)
    add_table(
        doc,
        ["别人常问", "你可以这样答"],
        [
            [
                "这是完整上线产品吗？",
                "主工程玩法与运维能力较完整，但仍需按 prod 清单加固；微服务目录仍是实验。",
            ],
            [
                "能当原神私服吗？",
                "产品定位不是无缝大世界动作战，是崩铁式分区 + 回合战。",
            ],
            [
                "为什么不立刻全微服务？",
                "战斗/钱包要强一致；拆错了会引入分布式事务与延迟，得不偿失。",
            ],
            [
                "策划怎么改数值？",
                "改 data 下 JSON/Excel，走导入与热更；详见策划配置手册。",
            ],
            [
                "AI 会不会乱扣费？",
                "不会。AI 只给建议；扣费开战发奖由游戏权威逻辑决定。回答会附合规免责声明。",
            ],
            [
                "AI 挂了游戏还能玩吗？",
                "能。远程超时/熔断后回退规则教练与本地知识；主动推送与问答失败不阻断主玩法。",
            ],
            [
                "怎么看 AI 好不好用？",
                "看 Prometheus AI 指标与玩家「有用/无用」反馈埋点；策略灰度可按 UID 尾号对比。",
            ],
            [
                "两台机器怎么开？",
                "开 Redis，center 切 remote，填广告地址；对照多节点故障转移文档验证。",
            ],
        ],
    )

    # ========== 二十一 ==========
    add_heading(doc, "二十一、发布、备份与日常运维手册摘要", 1)
    add_body(
        doc,
        "本章把仓库里分散的运维文档浓缩成「所有人都能跟着做」的摘要。"
        "细节仍以 docs/release-process.md、docs/backup-recovery.md、docs/error-codes.md、"
        "docs/observability-logging.md、docs/network-isolation.md 为准。",
    )

    add_heading(doc, "21.1 版本号怎么理解", 2)
    add_bullet(doc, "主工程 Maven：major.minor.patch（当前示例为 0.0.1-SNAPSHOT）。")
    add_bullet(doc, "协议：CmdIds.PROTOCOL_WIRE_VERSION；不兼容变更必须升版本并出客户端说明。")
    add_bullet(doc, "配置包：ConfigReleaseService 的 fingerprint / release_id，用于追溯与回滚。")

    add_heading(doc, "21.2 发布前检查（建议按顺序勾）", 2)
    add_bullet(doc, "跑测试：mvn test（含 CmdIdUniquenessTest）。")
    add_bullet(doc, "CI：buf lint + buf breaking（见 .github/workflows/ci.yml）。")
    add_bullet(doc, "发布演练：.\\scripts\\publish_drill.ps1（可选 -Extended）。")
    add_bullet(doc, "压测关键路径：scripts/load/k6_baseline.js，结果回填 docs/load-test-baseline.md。")
    add_bullet(doc, "写 Changelog：按 feat/fix/docs 归类，注明协议号段变更。")

    add_heading(doc, "21.3 发布中与回滚", 2)
    add_body(
        doc,
        "可先对灰度节点开启配置灰度（lunarcore.config-gray.* + ConfigGrayReader）；"
        "配置导入优先 dry-run。观察 Grafana 业务面板与 business-alerts.yml。"
        "若要回滚：POST /api/admin/ops/rollback?confirm=ROLLBACK&reason=...&version=..."
        "或文件列表回滚接口；原因写入 admin_audit_log。"
        "若协议不兼容：必须服务端与客户端一起回滚，禁止只回服务端。",
    )

    add_heading(doc, "21.4 备份与恢复（人话版）", 2)
    add_table(
        doc,
        ["对象", "建议做法", "特别提醒"],
        [
            ["MySQL", "每日全量 mysqldump/云快照；开启 binlog 增量", "钱包权威在 MySQL，务必可恢复"],
            ["Redis", "RDB 拷贝 + 生产建议开 AOF", "票据/锁/聊天可丢；不能当钱的唯一来源"],
            ["配置 data/", "纳入发布包与版本指纹", "热更失败要能回滚到上一指纹"],
            ["月度演练", "恢复到 staging 后跑 publish_drill 冒烟", "校验登录、钱包、抽卡流水、公会、邮件"],
        ],
    )

    add_heading(doc, "21.5 网络隔离与错误码", 2)
    add_body(
        doc,
        "Admin（8080）与游戏口（9000）应在网络层隔离：后台不要对公网裸奔，"
        "游戏口也尽量只对玩家接入层开放。详见 docs/network-isolation.md。"
        "联调时遇到业务失败码，优先查 docs/error-codes.md，"
        "比直接猜「是不是挂了」更高效。日志侧建议开启脱敏与 TraceId，"
        "方便一次请求从网关追到 AI sidecar（docs/observability-logging.md）。",
    )

    add_heading(doc, "21.6 优雅停机在发布窗口的意义", 2)
    add_body(
        doc,
        "GracefulShutdownCoordinator 会：排空匹配 → 中止进行中战斗并收尾 → 停 Netty/KCP → 同步落盘。"
        "发布窗口应覆盖「进行中的抽卡/钱包事务」回归用例，避免关机瞬间留下半截账。",
    )

    # ========== 二十二 ==========
    add_heading(doc, "二十二、架构决策记录（ADR）与持续集成", 1)
    add_body(
        doc,
        "「架构决策记录」（Architecture Decision Record，简称 ADR）是团队把「为什么这样设计」"
        "写成可追溯短文的做法。本仓库在 docs/adr/ 下已有三份关键决策，任何人（含非研发）"
        "都能据此理解「现在为什么不先拆微服务」「游戏口为什么用 KCP+AES-GCM」「生产密钥为什么必须环境注入」。",
    )

    add_heading(doc, "22.1 三份 ADR 一览", 2)
    add_table(
        doc,
        ["编号", "标题", "核心决定（人话）", "对日常的影响"],
        [
            [
                "ADR-0001",
                "生产以模块化单体为先",
                "先把一座大楼盖好并分区办公，不要一上来拆成十几家小公司",
                "生产只跑主工程；拆分优先 Admin/匹配/聊天，钱包与战斗暂缓",
            ],
            [
                "ADR-0002",
                "游戏口 KCP + AES-GCM",
                "玩家走可靠 UDP 通道，登录后下发会话密钥加密；不必先上 DTLS",
                "prod 强制加密；弱网/审核可用 TCP+TLS 备选",
            ],
            [
                "ADR-0003",
                "生产密钥环境注入",
                "钥匙放在环境变量/密钥柜，不写死在配置文件里；弱密钥直接拒绝开机",
                "ProductionSecretsValidator fail-fast；上线必须配 INTERNAL_API_TOKEN 等",
            ],
        ],
    )
    add_body(
        doc,
        "完整原文见：docs/adr/0001-monolith-first.md、docs/adr/0002-kcp-aes-gcm.md、"
        "docs/adr/0003-secret-injection.md。微服务拆分节奏另见 docs/microservice-split-roadmap.md。",
    )

    add_heading(doc, "22.2 持续集成（CI）在替大家盯什么", 2)
    add_body(
        doc,
        "仓库配置了 GitHub Actions（.github/workflows/ci.yml）。"
        "可以把它理解成「每次有人提交代码，机器人自动做一轮体检」，避免坏协议或危险改动悄悄进主分支。",
    )
    add_bullet(doc, "触发：push 到 main/master/develop、Pull Request、夜间定时任务。")
    add_bullet(doc, "协议体检：buf lint + buf breaking——破坏性协议变更必须升 PROTOCOL_WIRE_VERSION。")
    add_bullet(
        doc,
        "定向单测：CmdId 唯一性/完整性、WireVersion 守卫、公会战、切服、抽卡、匹配、热更、PublishDrill 等。",
    )
    add_bullet(doc, "发布冒烟：scripts/publish_drill.ps1。")
    add_bullet(doc, "文档同步：scripts/generate_docs_from_meta.py --check。")
    add_bullet(doc, "日志脱敏扫描：scripts/scan_log_masking.ps1，降低密钥/令牌进日志的风险。")
    add_bullet(doc, "夜间压测烟雾：k6（低并发 health 场景）；提交信息含 [loadtest] 也可触发。")

    add_heading(doc, "22.3 压测能力量级（定性参考）", 2)
    add_body(
        doc,
        "以下数字来自 docs/load-test-baseline.md 的基线模板，用于容量规划讨论，"
        "不是对你当前机器的实测保证。正式上线前请用 scripts/load/k6_baseline.js 按环境回填。",
    )
    add_table(
        doc,
        ["档位", "参考能力（量级）"],
        [
            ["开发机约 8 核 / 16G", "心跳在线约 8k–12k CCU；匹配约 3k–5k 请求/秒"],
            ["生产最小约 16 核 / 32G", "约 18k–25k CCU；匹配约 6k–10k 请求/秒"],
            ["生产标准约 32 核 / 64G × 2 节点", "单节点约 25k–35k CCU；匹配洪峰可考虑拆服务"],
        ],
    )
    add_note(doc, "建议按「预计峰值 × 1.5」校准告警阈值，避免刚好顶满才报警。")

    # ========== 二十三 ==========
    add_heading(doc, "二十三、端口与组件速查总表", 1)
    add_body(
        doc,
        "把「谁在听哪个端口」集中到一张表，方便运维开防火墙、新人对照文档、汇报时一眼说清。"
        "未启用的组件（如微服务、ClickHouse、Sentinel）不影响主工程单机跑通。",
    )
    add_table(
        doc,
        ["端口", "组件", "谁会用到", "备注"],
        [
            ["8080", "主工程 Admin HTTP / Actuator（开发）", "运营、运维、联调", "生产建议仅内网 + HTTPS 反代"],
            ["8081", "生产 Actuator（可配）", "监控抓取", "MANAGEMENT_SERVER_PORT，勿对公网"],
            ["9000", "游戏 TCP / KCP", "玩家客户端", "prod 强制 KCP 加密与协议 HMAC"],
            ["3306", "MySQL", "主工程 / 微服务实验", "权威账本；仅应用网段"],
            ["6379", "Redis", "多节点 / 排行 / 聊天 / 锁", "可丢；不能当钱的唯一来源"],
            ["26379", "Redis Sentinel", "多节点高可用 profile", "见 deploy/docker-compose.multi-node.yml"],
            ["8848", "Nacos", "微服务实验注册配置", "仅脚手架环境"],
            ["9090", "Prometheus", "监控", "deploy/prometheus"],
            ["3000", "Grafana", "看板", "默认账号见 compose，生产必须改"],
            ["8123 / 9009", "ClickHouse", "冷归档分析（可选）", "analytics profile"],
            ["18081", "auth-service", "微服务实验", "演示桩，禁止生产账号"],
            ["18082", "player-service", "微服务实验", "演示桩"],
            ["18083", "ai-assist-service", "AI 旁路", "可独立 compose 启动"],
            ["18084", "chat-service", "微服务实验", "HTTP 占位骨架"],
            ["18085", "match-service", "微服务实验", "HTTP 占位骨架"],
            ["18443", "api-gateway HTTPS", "微服务入口", "路由与鉴权过滤"],
        ],
    )

    add_heading(doc, "23.1 新人最小路径（再浓缩一次）", 2)
    add_code(
        doc,
        "1) docker compose -f docker-compose.dev.yml up -d\n"
        "2) mvn -DskipTests spring-boot:run\n"
        "3) 浏览器打开 http://127.0.0.1:8080/actuator/health\n"
        "4) Admin：http://127.0.0.1:8080/admin/\n"
        "5) 游戏客户端连 TCP/KCP 9000（协议 wire_version ≥ 2）\n"
        "6) 更细步骤与 FAQ：仓库根目录 QUICKSTART.md",
    )

    add_heading(doc, "23.2 完整文档地图（按读者）", 2)
    add_table(
        doc,
        ["你是谁", "建议先读"],
        [
            ["完全不懂技术的同事", "本报告第一～三章、第十九～二十章、第二十四章结语"],
            ["产品 / 策划", "第三章、第七章、docs/planner-config-guide.md、docs/feature-gap-fill.md"],
            ["客户端同学", "第八章、docs/protocol-docs.md、docs/protocol-integration-checklist.md、docs/error-codes.md"],
            ["服务端同学", "第五～九章、docs/adr/*、源码包结构"],
            ["运维 / SRE", "第十一～十三章、第二十一～二十三章、docs/production-hardening.md、docs/ops-daily-checklist.md"],
            ["安全同学", "第十三章、docs/secret-rotation.md、docs/network-isolation.md、docs/kcp-crypto.md"],
            ["架构评审", "第五章、第十四～十六章、docs/microservice-split-roadmap.md、ADR-0001"],
        ],
    )

    # ========== 二十四 ==========
    add_heading(doc, "二十四、结语", 1)
    add_body(
        doc,
        "MyLunarCore 已经不是「只有空壳的演示仓库」：它在单体形态下具备较完整的"
        "崩铁式 RPG 服务端能力——联网、场景、回合战、养成、经济抽卡、体力与每日循环、"
        "战令与主线章节、活动任务、对话过场与家园、公会/公会战/公会科技、竞技场评分、"
        "世界 BOSS/深渊/遗器词条等缺口补齐玩法、成就引导、运维热更与回滚、业务监控，"
        "以及可选的多节点与能力增强后的 AI 助手旁路"
        "（多轮对话、个性化、主动推送、熔断灰度、合规声明与专项指标）。"
        "与此同时，它也很诚实地把微服务目录标成脚手架，把无缝大世界标成实验，"
        "把默认密钥标成仅限本地。",
    )
    add_body(
        doc,
        "对普通人最重要的结论只有三句：第一，想体验或继续开发，请先跑主工程；"
        "第二，想上线，请走 prod 并认真对待密钥、支付验签与监控告警；"
        "第三，想变「更大、更微服务、更无缝」，请先验证 Center 与产品形态，"
        "再决定拆什么——而不是为了拆而拆。",
    )
    add_body(
        doc,
        "本报告基于仓库当前代码与文档快照自动整理生成，可用于汇报、培训与决策讨论。"
        "若后续架构或配置有重大变更，建议重新运行本仓库 scripts/generate_detailed_project_report_docx.py"
        "生成新版，或修订对应章节。"
        "打开本文件后，请记得更新目录域，以便目录页码与正文一致。",
    )
    doc.add_paragraph()
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run("—— 报告正文结束 ——")
    set_run_font(run, size=Pt(11), color=RGBColor(120, 120, 120))

    return doc


def main():
    doc = build_document()
    DESKTOP.mkdir(parents=True, exist_ok=True)
    # 先写 ASCII 名，保证任意控制台编码下都能落盘
    doc.save(str(OUTPUT_ASCII))
    # 再用 Python 原生 unicode 路径复制/覆盖中文名（避免 rename 在部分 Windows 控制台乱码）
    try:
        import shutil

        shutil.copyfile(str(OUTPUT_ASCII), str(OUTPUT_CN))
        print(f"Saved ASCII: {OUTPUT_ASCII}")
        print(f"Saved CN: {OUTPUT_CN}")
    except OSError as exc:
        print(f"Saved: {OUTPUT_ASCII} (CN copy failed: {exc})")


if __name__ == "__main__":
    main()
