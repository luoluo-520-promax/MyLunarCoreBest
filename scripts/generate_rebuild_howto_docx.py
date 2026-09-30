# -*- coding: utf-8 -*-
"""生成 MyLunarCore 从零重写详细教程 Word，输出到桌面。"""

from datetime import date
from pathlib import Path

from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_LINE_SPACING
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

DESKTOP = Path.home() / "Desktop"
OUTPUT = DESKTOP / (
    "MyLunarCore"
    + "\u4ece\u96f6\u91cd\u5199\u9879\u76ee\u8be6\u7ec6\u6559\u7a0b"
    + ".docx"
)
TEMP = DESKTOP / "_MyLunarCore_rebuild_howto_temp.docx"


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
    run = paragraph.add_run()
    fld_begin = OxmlElement("w:fldChar")
    fld_begin.set(qn("w:fldCharType"), "begin")
    instr = OxmlElement("w:instrText")
    instr.set(qn("xml:space"), "preserve")
    instr.text = " PAGE "
    fld_sep = OxmlElement("w:fldChar")
    fld_sep.set(qn("w:fldCharType"), "separate")
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


def add_footer(doc: Document):
    footer = doc.sections[0].footer
    footer.is_linked_to_previous = False
    p = footer.paragraphs[0] if footer.paragraphs else footer.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.clear()
    run1 = p.add_run("MyLunarCore 从零重写详细教程  ·  第 ")
    set_run_font(run1, size=Pt(9), color=RGBColor(90, 90, 90))
    _add_page_number_field(p)
    run2 = p.add_run(" 页")
    set_run_font(run2, size=Pt(9), color=RGBColor(90, 90, 90))


def enable_update_fields_on_open(doc: Document):
    settings = doc.settings.element
    update = OxmlElement("w:updateFields")
    update.set(qn("w:val"), "true")
    settings.append(update)


def add_toc_field(doc: Document):
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
    placeholder.text = "（打开 Word 后右键目录 →「更新域」即可显示完整目录与页码）"
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
    set_run_font(run, size=Pt(22), bold=True, color=RGBColor(25, 75, 140))


def add_subtitle(doc: Document, text: str):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run(text)
    set_run_font(run, size=Pt(11), color=RGBColor(100, 100, 100))


def add_heading(doc: Document, text: str, level: int = 1):
    h = doc.add_heading(text, level=level)
    for run in h.runs:
        set_run_font(run, bold=True, color=RGBColor(25, 75, 140))


def add_body(doc: Document, text: str):
    p = doc.add_paragraph(text)
    for run in p.runs:
        set_run_font(run)


def add_bullet(doc: Document, text: str, level: int = 0):
    p = doc.add_paragraph(text, style="List Bullet")
    p.paragraph_format.left_indent = Cm(0.5 * (level + 1))
    for run in p.runs:
        set_run_font(run)


def add_numbered(doc: Document, text: str):
    p = doc.add_paragraph(text, style="List Number")
    for run in p.runs:
        set_run_font(run)


def add_code(doc: Document, text: str):
    for line in text.split("\n"):
        p = doc.add_paragraph()
        p.paragraph_format.left_indent = Cm(0.4)
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.line_spacing = 1.15
        run = p.add_run(line if line else " ")
        run.font.name = "Consolas"
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor(30, 30, 30)
        run._element.rPr.rFonts.set(qn("w:eastAsia"), "微软雅黑")
    doc.add_paragraph()


def add_callout(doc: Document, title: str, text: str):
    p = doc.add_paragraph()
    run = p.add_run(f"【{title}】{text}")
    set_run_font(run, size=Pt(10.5), color=RGBColor(120, 60, 20))


def add_table(doc: Document, headers, rows):
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.style = "Table Grid"
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    for i, h in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = h
        for p in cell.paragraphs:
            for run in p.runs:
                set_run_font(run, bold=True, size=Pt(10))
    for r_idx, row in enumerate(rows):
        for c_idx, val in enumerate(row):
            cell = table.rows[r_idx + 1].cells[c_idx]
            cell.text = str(val)
            for p in cell.paragraphs:
                for run in p.runs:
                    set_run_font(run, size=Pt(10))
    doc.add_paragraph()


def build_document() -> Document:
    doc = Document()
    set_doc_fonts(doc)
    set_page_layout(doc)
    enable_update_fields_on_open(doc)
    add_footer(doc)

    # —— 封面 ——
    add_title(doc, "MyLunarCore 从零重写项目详细教程")
    add_subtitle(doc, "按依赖顺序 · 垂直切片 · 里程碑验收 · 可对照原仓 diff")
    add_subtitle(doc, "技术栈：Spring Boot 4 + Netty/KCP + Protobuf + MySQL（+ Redis 可选）")
    add_subtitle(doc, f"基于当前代码快照（约 446 个主类 / 22 个 Proto / 201 个测试）· 生成日期：{date.today().isoformat()}")
    doc.add_paragraph()

    add_heading(doc, "目录", 1)
    add_toc_field(doc)
    doc.add_page_break()

    # —— 一、写前必读 ——
    add_heading(doc, "一、写前必读：你在重写什么", 1)
    add_heading(doc, "1.1 项目定位", 2)
    add_body(
        doc,
        "MyLunarCore 是「崩铁式」游戏私服：分区探索 + 回合制战斗为主，附带抽卡、活动、公会、家园、匹配等系统。"
        "它不是原神/鸣潮那种无缝大世界实时动作服。生产形态是「模块化单体」；根目录工程才是可上线的游戏核，"
        "microservices/ 目录只是实验脚手架，不要一开始就拆微服务。",
    )
    add_table(
        doc,
        ["部分", "用途", "重写时怎么对待"],
        [
            ["根目录主工程", "游戏核心 + Admin + 钱包/战斗/场景/热更", "必须完整手写/复刻，生产只跑它"],
            ["data/ 与 resources/data", "策划 JSON/CSV 静态配置", "随模块一起拷贝，先能加载再谈热更"],
            ["src/main/proto", "客户端协议", "与 CmdIds / Handler 同步演进"],
            ["microservices/", "匹配/聊天/AI 旁路实验", "后期可选，前 3～4 周完全忽略"],
            ["deploy/ docs/ scripts/", "运维、文档、导入脚本", "主链路跑通后再补"],
        ],
    )

    add_heading(doc, "1.2 核心架构决策（务必遵守）", 2)
    add_bullet(doc, "ADR-0001：生产以模块化单体为先；勿先拆战斗/场景/钱包写路径。")
    add_bullet(doc, "网络：游戏口 Netty TCP + 可选 KCP（UDP）；管理口 Spring Web HTTP（默认 8080）。")
    add_bullet(doc, "协议：Protobuf + 自研 LunarFrame 帧；命令号集中在 CmdIds.java。")
    add_bullet(doc, "数据：MySQL（JDBC 仓库层）；多节点才强制开 Redis。")
    add_bullet(doc, "安全：prod profile fail-fast 拒绝弱密钥；KCP 加密、INTERNAL_API_TOKEN、DB 密码必须环境变量注入。")

    add_heading(doc, "1.3 请求怎么走（永远画在心里）", 2)
    add_code(
        doc,
        "客户端 (TCP/KCP)\n"
        "  → LunarFrameDecoder / Encoder\n"
        "  → GamePacketDispatcher（按 cmdId 找 Handler）\n"
        "  → *PacketHandlers\n"
        "  → *NettyService / *ApplicationService\n"
        "  → repo（JDBC）→ MySQL\n"
        "  →（可选）PlayerDataSyncService 推送下行",
    )
    add_body(
        doc,
        "Admin 另走一条：浏览器/脚本 → Spring Security → /api/admin/** 或 /internal/** → 热更/导入/运维。"
        "两套入口不要混在游戏 Handler 里写 HTTP。",
    )

    add_heading(doc, "1.4 手敲五条原则", 2)
    add_numbered(doc, "严格按依赖顺序：底层 config/model/repo/net 未稳，不要先写 BattleNettyService。")
    add_numbered(doc, "每完成一小步就 mvn -q compile（或 IDE Build），避免错误堆积。")
    add_numbered(doc, "改协议必须三处同步：*.proto、CmdIds、对应 PacketHandlers（以及测试）。")
    add_numbered(doc, "包名/类名尽量与原项目一致，便于对照 diff 与复用测试。")
    add_numbered(doc, "每模块至少留一个 *Test；先让登录链路绿，再堆玩法。")
    doc.add_page_break()

    # —— 二、环境与骨架 ——
    add_heading(doc, "二、环境准备与工程骨架（第 0～1 天）", 1)
    add_heading(doc, "2.1 工具链", 2)
    add_table(
        doc,
        ["工具", "版本建议", "用途"],
        [
            ["JDK", "17+", "编译运行（pom 中 java.version=17）"],
            ["Maven", "3.9+", "构建、protobuf 插件、测试"],
            ["Docker", "可选但推荐", "docker-compose.dev.yml 起 MySQL/Redis"],
            ["IDE", "IntelliJ IDEA", "运行 MyLunarCoreApplication"],
            ["Python", "3.10+", "活动 Excel 导入、文档脚本（后期）"],
            ["Git", "任意", "按阶段打 tag，方便回滚"],
        ],
    )

    add_heading(doc, "2.2 新建空工程时要落地的文件", 2)
    add_table(
        doc,
        ["顺序", "内容", "说明"],
        [
            ["0.1", "pom.xml", "继承 spring-boot-starter-parent；引入 web/jdbc/security/netty/kcp/protobuf/redis/actuator"],
            ["0.2", "包根", "cn.itcast.demo.mylunarcore 下按域建子包（先建空目录即可）"],
            ["0.3", "MyLunarCoreApplication.java", "@SpringBootApplication 入口"],
            ["0.4", "application.properties + application-dev/test/prod", "端口、数据源、lunarcore.*"],
            ["0.5", "logback-spring.xml", "分类日志"],
            ["0.6", "db 初始化 SQL", "合并或 migration 脚本，先保证能建库"],
            ["0.7", "data/ 静态配置", "先放最小集：必要 Banner/Item/Scene 等"],
            ["0.8", "src/main/proto/", "先放 player_session.proto"],
        ],
    )
    add_callout(
        doc,
        "验收",
        "mvn -DskipTests compile 通过；docker compose -f docker-compose.dev.yml up -d 后能连上 MySQL。",
    )

    add_heading(doc, "2.3 推荐初始包清单", 2)
    add_body(doc, "先建这些包即可启动「最小可运行」；其余玩法包按阶段 8 再加：")
    add_bullet(doc, "config / common / model / repo / net / player / admin / metrics")
    add_bullet(doc, "随后垂直切片：item / battle / scene / gacha / challenge / rogue / activity …")
    add_bullet(doc, "扩展域（后期）：economy、guild、home、matchmaking、assist、world、tx、center …")
    doc.add_page_break()

    # —— 三、分层编写 ——
    add_heading(doc, "三、按依赖分层编写（阶段 1～7）", 1)
    add_body(
        doc,
        "这一章是「地基」。地基不稳，后面所有玩法都会反复返工。每一小节结束都应能编译；标了测试的要跑绿。",
    )

    add_heading(doc, "3.1 阶段 1：配置层 config", 2)
    add_body(doc, "目标：所有 lunarcore.* / mylunarcore.* 配置可绑定，Netty 业务线程池可注入。")
    add_bullet(doc, "LunarCoreProperties：端口、KCP、Redis、center.mode、热更开关等。")
    add_bullet(doc, "NettyGameBusinessConfiguration：业务线程池 Bean。")
    add_bullet(doc, "GameConfigurationManager（及 Wire）：启动时加载静态配置入口。")
    add_callout(doc, "验收", "启动容器能读到 lunarcore.netty-port（或等价键）=9000。")

    add_heading(doc, "3.2 阶段 2：公共基础设施 common", 2)
    add_body(doc, "建议拆成 6 小批，每批可单独编译：")
    add_table(
        doc,
        ["批次", "内容", "例子"],
        [
            ["2A 工具", "日志分类、静态资源 ID、事件 POJO", "AppLogger、LogCategory、GameEvent"],
            ["2B 资源加载", "JSON/CSV 注册表、热更数据", "StaticResourceRegistry、HotfixDataService"],
            ["2C 游戏循环", "Tick、事件发布、GameServer", "PlayerTickRegistry、GameEventPublisher"],
            ["2D 运维", "热更协调、优雅停机、广播", "HotReloadCoordinator、GracefulShutdownCoordinator"],
            ["2E 活动/版本", "活动配置与导入、版本热更", "ActivityConfigService、VersionHotReloadService"],
            ["2F 监控", "流量与服务器指标", "GameTrafficMetrics、ServerMetricsMonitor"],
        ],
    )
    add_callout(doc, "验收", "StaticResourceRegistry / HotReloadCoordinator / GameServer 相关单测通过。")

    add_heading(doc, "3.3 阶段 3：数据模型 model", 2)
    add_bullet(doc, "AccountEntity、PlayerData（核心，字段对齐表结构）。")
    add_bullet(doc, "物品、好友、战斗波次、肉鸽进度、活动配置等 Entity。")
    add_bullet(doc, "原则：Entity 只含字段与访问器，不写业务；复杂状态放 PlayerData 或领域对象。")

    add_heading(doc, "3.4 阶段 4：数据访问层 repo", 2)
    add_bullet(doc, "先写 GameDataRepository（共享 JDBC 封装），再写 PlayerDataRepository。")
    add_bullet(doc, "按表扩展：ItemRepository、GachaRepository、Scene/Monster 配置仓等。")
    add_bullet(doc, "多节点钱包等高级锁可后置到 economy 阶段，但 data_version 字段尽早预留。")
    add_callout(doc, "验收", "PlayerData 能 CRUD；仓库单测绿。")

    add_heading(doc, "3.5 阶段 5：Protobuf 协议", 2)
    add_body(doc, "当前工程约 22 个 proto，建议引入顺序：")
    add_table(
        doc,
        ["优先级", "proto", "对应业务"],
        [
            ["P0", "player_session.proto", "登录 / 心跳 / 同步"],
            ["P0", "version_update.proto", "版本与热更查询"],
            ["P0", "battle_system.proto / scene_system.proto / item_system.proto", "三大核心玩法"],
            ["P1", "gacha / challenge / rogue / activity", "常驻玩法"],
            ["P1", "economy / character / quest / hall", "养成与主线循环"],
            ["P2", "guild / home / matchmaking / dialogue_cutscene", "社交与内容"],
            ["P2", "assist / skin / settings / battle_pass / daily_loop", "扩展系统"],
        ],
    )
    add_code(doc, "mvn -DskipTests compile\n# 生成代码默认在 target/generated-sources/protobuf")
    add_body(doc, "同步维护 CmdIds：每个 CsReq/ScRsp 成对占号；CI 侧可有 CmdIdUniquenessTest / CompletenessTest。")

    add_heading(doc, "3.6 阶段 6：网络层 net（最核心）", 2)
    add_body(doc, "按子步骤写，千万不要一次塞 60 个类：")
    add_table(
        doc,
        ["步骤", "类簇", "职责"],
        [
            ["6A", "CmdIds、LunarFrameConstants、Decoder/Encoder", "命令号与帧格式"],
            ["6B", "PacketCommandHandler、Registry、Dispatcher、幂等", "分发框架"],
            ["6C", "限流、背压、Trace、ChannelOptions", "连接治理"],
            ["6D", "GameServerChannelHandler、GameNettyServer、GameKcpServer", "启服监听"],
            ["6E", "net/mapper/*ProtoMapper、kcp 加密相关", "映射与传输安全"],
        ],
    )
    add_callout(doc, "验收", "LunarFrameCodecTest、GamePacketDispatcherTest 通过；本机 9000 端口可 accept。")

    add_heading(doc, "3.7 阶段 7：玩家会话层 player", 2)
    add_body(doc, "目标：客户端连上 → 登录成功 → 心跳保活 → 基础同步。")
    add_bullet(doc, "会话：GameSession、PlayerChannelAttributes、GameSessionManager。")
    add_bullet(doc, "同步抽象：Syncable、PlayerCoreSyncable、SyncReason、DataChangeScope。")
    add_bullet(doc, "数据：Cache / AsyncLoad / Sync / PeriodicPersistence。")
    add_bullet(doc, "业务：PlayerLoginApplicationService、ConnectionLifecycleService、SessionLock。")
    add_bullet(doc, "Handler：PlayerSessionPacketHandlers、UpdatePacketHandlers。")
    add_callout(doc, "里程碑 M1", "客户端能连 9000，完成登录与心跳。这是整条重写的「生死线」。")
    doc.add_page_break()

    # —— 四、垂直切片 ——
    add_heading(doc, "四、业务模块：垂直切片写法（阶段 8）", 1)
    add_heading(doc, "4.1 标准模板（每个玩法照抄）", 2)
    add_code(
        doc,
        "1) 领域逻辑     XxxManager / XxxApplicationService\n"
        "2) 网络适配     XxxNettyService（把 Packet ↔ 领域对象）\n"
        "3) 入站处理     XxxPacketHandlers（实现 PacketCommandHandler）\n"
        "4) Proto 映射   XxxProtoMapper（可选，复杂结构时）\n"
        "5) 仓库/配置    XxxRepository 或 读 data/*.json\n"
        "6) 单测         XxxNettyServiceTest / Xxx*Test",
    )
    add_body(
        doc,
        "命名约定：入站命令处理集中在 *PacketHandlers；不要把 SQL 写进 Handler；"
        "不要把 Proto Builder 泄漏到 Manager 深层。",
    )

    add_heading(doc, "4.2 建议实现顺序与依赖", 2)
    add_table(
        doc,
        ["顺序", "模块", "先决条件", "完成定义"],
        [
            ["8.1", "item 物品", "PlayerData + repo", "背包增减、推送同步"],
            ["8.2", "battle 战斗", "item + scene 基础", "开战/结算/快照可恢复"],
            ["8.3", "scene 场景", "登录会话", "进图、加载态、基础移动/切图"],
            ["8.4", "gacha 抽卡", "item + economy 钱包雏形", "单抽/十连写路径正确"],
            ["8.5", "challenge 挑战", "battle", "历史记录与领奖"],
            ["8.6", "rogue 肉鸽", "battle + 配置", "开局/选点/结算"],
            ["8.7", "activity 活动", "common 活动配置", "开关与领奖"],
            ["8.8", "economy 经济/IAP", "item", "钱包 CAS；沙盒验单开关"],
            ["8.9", "character/equipment/skin", "item", "养成与外观"],
            ["8.10", "quest/story/dialogue", "scene", "主线旗标与过场"],
            ["8.11", "guild/party/social/home", "player", "社交闭环"],
            ["8.12", "matchmaking/arena/hall", "battle", "匹配与大厅"],
            ["8.13", "battlepass/daily/stamina", "economy", "日环与战令"],
            ["8.14", "world/worldboss/abyss", "scene+battle", "世界内容"],
            ["8.15", "assist AI 助手", "可选 sidecar", "可本地桩或远程熔断"],
            ["8.16", "tx/center/archive/anticheat", "多节点需求", "Outbox、切服、归档"],
        ],
    )

    add_heading(doc, "4.3 战斗模块怎么写（示例拆解）", 2)
    add_numbered(doc, "先定义 EntityState / BattleContext 等纯内存状态。")
    add_numbered(doc, "再写 BattleManager（创建、查找、结算、快照 hydrate）。")
    add_numbered(doc, "BattleSceneFactory 把配置怪物波次灌进战场。")
    add_numbered(doc, "BattleNettyService 解析 CsReq，调用 Manager，组装 ScRsp。")
    add_numbered(doc, "BattlePacketHandlers 注册 CmdIds，只做鉴权与转发。")
    add_numbered(doc, "补 BattleNettyServiceTest：至少覆盖开战成功与非法状态拒绝。")

    add_heading(doc, "4.4 配置驱动玩法怎么写", 2)
    add_bullet(doc, "策划表放 data/*.json（或 Excel → scripts/import_*.py）。")
    add_bullet(doc, "启动或热更时由 *ConfigService / StaticResourceRegistry 加载。")
    add_bullet(doc, "业务只读配置 ID，不写死数值；灰度可读 ConfigGrayReader（后期）。")
    add_bullet(doc, "Admin 热更：HotReloadCoordinator → 通知在线玩家（UpdateNotifyBroadcaster）。")
    doc.add_page_break()

    # —— 五、Admin / 运维 / 观测 ——
    add_heading(doc, "五、管理后台、运维与观测（阶段 9～11）", 1)
    add_heading(doc, "5.1 Admin", 2)
    add_bullet(doc, "AdminSecurityConfig + UserDetails + RBAC 仓库。")
    add_bullet(doc, "登录与 Root 页面；HotReloadController；ConfigImportController。")
    add_bullet(doc, "玩家存档导出/导入、配置版本对比、一键回滚（confirm 口令）。")
    add_bullet(doc, "prod 下 Admin IP 白名单；CSRF/会话按现有文档联调。")
    add_callout(doc, "验收", "开发环境能登录 Admin，并触发一次 reload。")

    add_heading(doc, "5.2 指标与健康检查", 2)
    add_bullet(doc, "Actuator：/actuator/health、/actuator/prometheus。")
    add_bullet(doc, "GameServerMetricsBinder 绑定在线人数、包速率、战斗数等。")
    add_bullet(doc, "deploy/prometheus + grafana 可在压测阶段再接。")

    add_heading(doc, "5.3 生产加固清单（重写时预留钩子）", 2)
    add_bullet(doc, "ProductionSecretsValidator：弱 INTERNAL_API_TOKEN / DB 密码直接失败。")
    add_bullet(doc, "IAP mock-verify 仅限非 prod。")
    add_bullet(doc, "KCP AES-GCM；多节点必须 Redis + center.mode=remote。")
    add_bullet(doc, "备份恢复、发布演练脚本（scripts/publish_drill.ps1）在主链路后补。")
    doc.add_page_break()

    # —— 六、测试 ——
    add_heading(doc, "六、测试策略（贯穿全程，阶段 12）", 1)
    add_table(
        doc,
        ["优先级", "类型", "代表", "何时写"],
        [
            ["P0", "编解码", "LunarFrameCodecTest", "net 一写完立刻"],
            ["P0", "会话", "GameSessionManagerTest", "登录里程碑前"],
            ["P0", "仓库", "PlayerDataRepositoryTest", "repo 阶段"],
            ["P0", "命令号", "CmdIdUniquenessTest", "每次加协议"],
            ["P1", "模块服务", "*NettyServiceTest", "每个垂直切片末尾"],
            ["P2", "集成", "热更/真实配置加载", "第 4～5 周"],
            ["P2", "协议干燥", "CmdIdProtoCompletenessTest", "协议稳定后"],
        ],
    )
    add_code(
        doc,
        "mvn test -Punit\n"
        "mvn test -Pintegration\n"
        "mvn spring-boot:run\n"
        "# 或 IDE 运行 MyLunarCoreApplication（非 prod profile）",
    )
    add_body(
        doc,
        "建议：每完成一个垂直切片就 git commit（或打 tag phase-8-battle）。"
        "不要攒到「全写完」再测——游戏服回归成本极高。",
    )
    doc.add_page_break()

    # —— 七、时间表 ——
    add_heading(doc, "七、推荐手敲节奏与最小路径", 1)
    add_heading(doc, "7.1 18～30 天节奏（全职或高强度兼职）", 2)
    add_table(
        doc,
        ["周次", "阶段", "产出"],
        [
            ["第 1 周", "0～4", "工程骨架、配置、model、repo；能读写玩家"],
            ["第 2 周", "5～7", "Proto + net + player；登录心跳里程碑"],
            ["第 3 周", "8.1～8.3", "item + battle + scene 核心三角"],
            ["第 4 周", "8.4～8.7", "抽卡/挑战/肉鸽/活动"],
            ["第 5 周", "8.8～8.12 + Admin", "经济社交 + 后台热更"],
            ["第 6 周+", "扩展玩法 + 测试 + 加固", "战令/公会/家园/压测/prod 开关"],
        ],
    )

    add_heading(doc, "7.2 时间紧时的最小可运行路径", 2)
    add_code(
        doc,
        "pom\n"
        "→ LunarCoreProperties + AppLogger\n"
        "→ GameDataRepository + PlayerData\n"
        "→ player_session.proto + CmdIds\n"
        "→ LunarFrameCodec + GamePacketDispatcher + GameNettyServer\n"
        "→ GameSessionManager + PlayerLoginApplicationService\n"
        "→ PlayerSessionPacketHandlers + GameServer",
    )
    add_body(doc, "打通后：启动 → 监听 9000 → 登录 → 心跳。然后再按 4.2 表加模块，每加一个就跑对应测试。")

    add_heading(doc, "7.3 当前规模对照（便于估工时）", 2)
    add_table(
        doc,
        ["包/区域", "约略文件数", "备注"],
        [
            ["net", "61", "最大，优先拆步写"],
            ["common", "51", "基础设施，勿与玩法搅在一起"],
            ["assist", "36", "可整体后置或旁路"],
            ["player", "31", "登录里程碑关键"],
            ["repo / admin / model", "26 / 25 / 21", "地基"],
            ["economy / battle / scene", "19 / 16 / 13", "核心商业与玩法"],
            ["其余玩法包", "若干", "按垂直切片逐个点亮"],
        ],
    )
    doc.add_page_break()

    # —— 八、日常编码规范 ——
    add_heading(doc, "八、日常编码规范与对照原仓方法", 1)
    add_heading(doc, "8.1 一天怎么写", 2)
    add_numbered(doc, "早上：选定今日切片（例如「只做 ItemPacketHandlers 入站」）。")
    add_numbered(doc, "对照原仓同名类，先抄清接口与依赖，再填实现。")
    add_numbered(doc, "写完 compile → 相关单测 → 手工连一次（若涉及网络）。")
    add_numbered(doc, "晚上：记录「明日依赖清单」（缺哪个 Bean / 哪个 CmdId）。")

    add_heading(doc, "8.2 对照原项目的高效姿势", 2)
    add_bullet(doc, "用 IDEA 双窗口：左边原仓，右边新仓，按包同步。")
    add_bullet(doc, "优先复用测试：把 *Test 先迁过去，实现「测不通就补代码」。")
    add_bullet(doc, "协议以 proto + CmdIds 为单一真相；不要在 Handler 里魔法数字。")
    add_bullet(doc, "静态配置先保证能加载；数值平衡以后再说。")

    add_heading(doc, "8.3 常见坑", 2)
    add_bullet(doc, "先写微服务：会在登录/钱包上撞分布式事务，违背 ADR-0001。")
    add_bullet(doc, "Handler 里开新线程乱碰 PlayerData：必须走会话锁/约定线程模型。")
    add_bullet(doc, "只改 proto 不改 CmdIds：客户端对不上号。")
    add_bullet(doc, "prod 配置拿去本地：密钥校验失败或误连生产库。")
    add_bullet(doc, "战斗状态只放内存不做快照：多节点/宕机无法恢复。")
    add_bullet(doc, "热更只改内存不落版本：无法回滚，运维会崩。")
    doc.add_page_break()

    # —— 九、微服务 ——
    add_heading(doc, "九、微服务什么时候再碰", 1)
    add_body(
        doc,
        "重写主工程稳定前，不要启动 microservices/ 作为主路径。待单体登录、战斗、钱包、热更都跑通后，"
        "再按路线图拆「无状态洪峰域」：",
    )
    add_table(
        doc,
        ["阶段", "可拆内容", "不可拆"],
        [
            ["1", "匹配大厅、世界聊天广播", "钱包 / 抽卡写路径 / 战斗"],
            ["2", "Admin/热更独立进程（调 /internal/**）", "玩家权威状态写"],
            ["3", "有 Outbox + 压测证据后再评估高一致域", "在此之前保持单体"],
        ],
    )
    add_body(doc, "AI 助手可用 sidecar（ai-assist-service）旁路，网关熔断即可，不影响游戏权威逻辑。")
    doc.add_page_break()

    # —— 十、验收清单 ——
    add_heading(doc, "十、分里程碑验收清单", 1)
    add_heading(doc, "M1 登录可用", 2)
    add_bullet(doc, "MySQL 可连；主工程启动无致命错误。")
    add_bullet(doc, "9000 可连；登录成功；心跳维持；断线清理会话。")
    add_bullet(doc, "Admin health 200。")

    add_heading(doc, "M2 核心三角", 2)
    add_bullet(doc, "进场景、开战斗、结算改背包。")
    add_bullet(doc, "相关 NettyService 单测通过。")

    add_heading(doc, "M3 商业闭环", 2)
    add_bullet(doc, "抽卡扣币加道具；钱包并发安全（至少单机正确）。")
    add_bullet(doc, "IAP mock 可开关；prod 路径拒绝 mock。")

    add_heading(doc, "M4 运营可维护", 2)
    add_bullet(doc, "配置热更 + 版本记录 + 回滚演练。")
    add_bullet(doc, "关键指标进 Prometheus；错误码可查询。")

    add_heading(doc, "M5 扩展内容", 2)
    add_bullet(doc, "公会/家园/匹配/战令等按产品优先级点亮。")
    add_bullet(doc, "多节点需求出现时再开 Redis center.mode=remote，并做切服演练。")
    doc.add_page_break()

    # —— 十一、附录 ——
    add_heading(doc, "十一、附录：命令速查与文档索引", 1)
    add_heading(doc, "11.1 常用命令", 2)
    add_code(
        doc,
        "# 依赖\n"
        "docker compose -f docker-compose.dev.yml up -d\n\n"
        "# 编译 / 运行\n"
        "mvn -DskipTests compile\n"
        "mvn -DskipTests spring-boot:run\n\n"
        "# 测试\n"
        "mvn test -Punit\n"
        "mvn test -Pintegration\n\n"
        "# 发布演练（后期）\n"
        ".\\scripts\\publish_drill.ps1",
    )

    add_heading(doc, "11.2 原仓文档该何时读", 2)
    add_table(
        doc,
        ["文档", "何时打开"],
        [
            ["QUICKSTART.md", "第一天环境"],
            ["docs/adr/0001-monolith-first.md", "想拆服务之前"],
            ["docs/protocol-docs.md / protocol-migration-guide.md", "改协议时"],
            ["docs/planner-config-guide.md / config-versioning.md", "接策划表与热更时"],
            ["docs/kcp-crypto.md / production-hardening.md", "上 prod 前"],
            ["docs/multi-node-failover.md", "开多节点时"],
            ["docs/feature-gap-fill.md", "对照还缺哪些玩法"],
            ["docs/error-codes.md / admin-api.md", "联调与排错"],
            ["microservices/README.md", "仅实验拆分时"],
        ],
    )

    add_heading(doc, "11.3 重写完成的定义", 2)
    add_body(
        doc,
        "不是「文件数等于原仓」，而是：M1～M4 验收全过；你需要的玩法切片有测试；"
        "prod 加固项可开关；你能独立加一个新 CmdId 玩法（proto → CmdIds → Handler → Service → 测试）而不翻车。"
        "到这一步，重写才算成功——其余内容可以继续按垂直切片迭代。",
    )

    add_body(doc, "— 文档结束 — 祝手敲顺利。每阶段先编译、先测试、先登录，再谈花活。")
    return doc


def main():
    doc = build_document()
    DESKTOP.mkdir(parents=True, exist_ok=True)
    doc.save(str(TEMP))
    if OUTPUT.exists():
        OUTPUT.unlink()
    TEMP.rename(OUTPUT)
    print(f"已生成文档：{OUTPUT}")


if __name__ == "__main__":
    main()
