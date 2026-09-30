# -*- coding: utf-8 -*-
"""Generate Java game server development guide PDF."""

from fpdf import FPDF
from pathlib import Path

DESKTOP = Path.home() / "Desktop"
OUTPUT = DESKTOP / "Java\u6e38\u620f\u670d\u52a1\u5668\u5f00\u53d1\u6307\u5357.pdf"
FONT = Path(r"C:\Windows\Fonts\simhei.ttf")


class GuidePDF(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("SimHei", size=9)
            self.set_text_color(120, 120, 120)
            self.cell(0, 8, "Java 游戏服务器开发指南", align="C", new_x="LMARGIN", new_y="NEXT")
            self.ln(2)

    def footer(self):
        self.set_y(-12)
        self.set_font("SimHei", size=9)
        self.set_text_color(120, 120, 120)
        self.cell(0, 8, f"第 {self.page_no()} 页", align="C")

    def section_title(self, title: str):
        self.ln(4)
        self.set_font("SimHei", size=16)
        self.set_text_color(25, 75, 140)
        self.multi_cell(0, 10, title)
        self.ln(2)

    def sub_title(self, title: str):
        self.ln(2)
        self.set_font("SimHei", size=13)
        self.set_text_color(40, 40, 40)
        self.multi_cell(0, 8, title)
        self.ln(1)

    def body(self, text: str):
        self.set_x(self.l_margin)
        self.set_font("SimHei", size=11)
        self.set_text_color(30, 30, 30)
        self.multi_cell(self.epw, 7, text)
        self.ln(1)

    def bullet(self, text: str):
        self.set_x(self.l_margin)
        self.set_font("SimHei", size=11)
        self.set_text_color(30, 30, 30)
        self.multi_cell(self.epw, 7, f"- {text}")

    def numbered(self, index: int, text: str):
        self.set_x(self.l_margin)
        self.set_font("SimHei", size=11)
        self.set_text_color(30, 30, 30)
        self.multi_cell(self.epw, 7, f"{index}. {text}")

    def code_block(self, text: str):
        self.set_x(self.l_margin)
        self.set_fill_color(245, 245, 245)
        self.set_font("SimHei", size=10)
        self.set_text_color(20, 20, 20)
        for line in text.split("\n"):
            self.cell(self.epw, 6, f"  {line}", new_x="LMARGIN", new_y="NEXT", fill=True)
        self.ln(2)

    def table(self, headers, rows, col_widths=None):
        if col_widths is None:
            col_widths = [self.epw / len(headers)] * len(headers)
        total = sum(col_widths)
        col_widths = [w * self.epw / total for w in col_widths]
        self.set_x(self.l_margin)
        self.set_font("SimHei", size=10)
        self.set_fill_color(230, 240, 250)
        self.set_text_color(20, 20, 20)
        for i, h in enumerate(headers):
            self.cell(col_widths[i], 8, h, border=1, fill=True)
        self.ln()
        self.set_fill_color(255, 255, 255)
        for row in rows:
            x0 = self.l_margin
            y0 = self.get_y()
            row_heights = []
            for i, cell in enumerate(row):
                self.set_xy(x0 + sum(col_widths[:i]), y0)
                self.multi_cell(col_widths[i], 7, cell, border=1, fill=False)
                row_heights.append(self.get_y() - y0)
            self.set_xy(self.l_margin, y0 + max(row_heights))
        self.ln(2)


def build_pdf():
    pdf = GuidePDF()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_font("SimHei", "", str(FONT))
    pdf.add_page()

    # Cover
    pdf.ln(40)
    pdf.set_font("SimHei", size=28)
    pdf.set_text_color(25, 75, 140)
    pdf.multi_cell(0, 14, "Java 游戏服务器\n开发指南", align="C")
    pdf.ln(10)
    pdf.set_font("SimHei", size=14)
    pdf.set_text_color(80, 80, 80)
    pdf.multi_cell(0, 8, "准备阶段 · 分步开发 · 模块规划 · 实践建议", align="C")
    pdf.ln(20)
    pdf.set_font("SimHei", size=11)
    pdf.multi_cell(0, 7, "本文档整合 Java 游戏服务器从零到生产的完整路径，\n并以 MyLunarCore 项目为参考示例。", align="C")
    pdf.ln(30)
    pdf.set_font("SimHei", size=10)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 8, "生成日期：2026-07-09", align="C")

    pdf.add_page()
    pdf.section_title("目录概览")
    for item in [
        "一、开发前需要做的准备",
        "  1. 明确游戏类型与架构边界",
        "  2. 技术栈选型",
        "  3. 协议与数据设计",
        "  4. 开发环境与工程规范",
        "  5. 运维与监控准备",
        "二、准备好后的开发步骤",
        "  阶段 1：项目骨架",
        "  阶段 2：网络层",
        "  阶段 3：玩家与会话",
        "  阶段 4：玩法模块",
        "  阶段 5：生产化与扩展",
        "三、模块依赖关系",
        "四、新手常见坑与建议",
        "五、结合 MyLunarCore 的下一步建议",
    ]:
        pdf.body(item)

    pdf.add_page()
    pdf.section_title("一、开发前需要做的准备")

    pdf.sub_title("1. 明确游戏类型与架构边界")
    pdf.body("先回答几个关键问题，它们会决定技术选型和模块划分：")
    pdf.table(
        ["问题", "影响"],
        [
            ["回合制 / 实时 / 半实时？", "是否需要固定 Tick、帧同步、KCP/UDP"],
            ["单服 / 分服 / 跨服？", "数据库分片、网关、服务发现"],
            ["在线规模目标（百人 / 万人）？", "线程模型、内存、连接数"],
            ["客户端协议（Protobuf / JSON / 自定义二进制）？", "编解码层设计"],
        ],
        [55, 135],
    )
    pdf.body(
        "MyLunarCore 选择了：Spring Boot 单体 + Netty（TCP）+ KCP（UDP）+ Protobuf，"
        "适合有实时战斗、需要低延迟的 RPG/动作类玩法。"
    )

    pdf.sub_title("2. 技术栈选型")
    pdf.body("基础层（MyLunarCore 已采用）：")
    for item in [
        "Java 17+ — LTS，支持虚拟线程、Records 等现代特性",
        "Spring Boot — 依赖注入、配置管理、Web 管理端",
        "Netty — 高并发长连接",
        "MySQL — 玩家持久化",
        "Protobuf — 协议定义与版本管理",
    ]:
        pdf.bullet(item)
    pdf.ln(2)
    pdf.body("按需补充：")
    for item in [
        "Redis — 会话、排行榜、限流、分布式锁",
        "消息队列（Kafka/RabbitMQ）— 异步事件、日志、跨服",
        "Nacos / Consul — 配置中心、服务发现（MyLunarCore 已有 microservices 脚手架）",
        "Docker + K8s — 部署与扩缩容",
    ]:
        pdf.bullet(item)

    pdf.sub_title("3. 协议与数据设计")
    pdf.body("在写业务代码前，先把「契约」定下来：")
    pdf.code_block(
        "客户端 ←→ [帧头 + cmdId + body] ←→ 服务端\n"
        "                ↓\n"
        "         Protobuf / JSON 定义\n"
        "                ↓\n"
        "         CmdIds 枚举 + Handler 注册"
    )
    pdf.body("MyLunarCore 已有对应实现：")
    for item in [
        "src/main/proto/ — 协议定义",
        "LunarFrameDecoder/Encoder — 帧编解码",
        "PacketCommandRegistry + @PacketCmd — 按 cmdId 分发",
        "src/main/resources/data/*.json — 静态配置表（怪物、卡池、活动等）",
    ]:
        pdf.bullet(item)
    pdf.ln(2)
    pdf.body("建议提前产出：协议文档（cmdId 列表、请求/响应结构）、ER 图、配置表规范（ID 规则、热更流程）。")

    pdf.sub_title("4. 开发环境与工程规范")
    for item in [
        "IDE：IntelliJ IDEA",
        "构建：Maven / Gradle",
        "版本控制：Git + 分支策略（main / develop / feature）",
        "代码规范：包结构、命名、日志分类（AppLogger + LogCategory）",
        "测试：单元测试 + 集成测试（TestNG / JUnit）",
    ]:
        pdf.bullet(item)

    pdf.sub_title("5. 运维与监控准备")
    for item in [
        "日志：分级、按模块（SYSTEM / BATTLE / GACHA 等）",
        "指标：在线人数、包 QPS、延迟（GameTrafficMetrics）",
        "热更：配置表、Hotfix（HotReloadCoordinator、hotfix.json）",
        "管理后台：GM、封禁、数据查询（admin 包）",
    ]:
        pdf.bullet(item)

    pdf.add_page()
    pdf.section_title("二、准备好后的开发步骤（推荐顺序）")
    pdf.body("整体按「骨架 → 连接 → 玩家 → 玩法 → 运维」五个阶段推进。")

    pdf.sub_title("阶段 1：项目骨架（1–2 周）")
    pdf.table(
        ["步骤", "内容", "MyLunarCore 对应"],
        [
            ["1.1", "创建 Spring Boot 工程，统一依赖版本", "pom.xml、Java 17"],
            ["1.2", "配置管理（端口、DB、游戏循环参数）", "LunarCoreProperties"],
            ["1.3", "日志与异常规范", "AppLogger、LogCategory"],
            ["1.4", "数据库表 + Repository 层", "model/、repo/"],
            ["1.5", "静态配置加载", "StaticResourceRegistry"],
        ],
        [18, 72, 100],
    )
    pdf.body("产出：能启动、能读配置、能连 DB，还没有客户端协议。")

    pdf.sub_title("阶段 2：网络层（2–3 周）")
    pdf.table(
        ["步骤", "内容", "MyLunarCore 对应"],
        [
            ["2.1", "Netty Server 启动（TCP，可选 KCP/UDP）", "GameNettyServer、GameKcpServer"],
            ["2.2", "帧协议编解码", "LunarFrameDecoder/Encoder"],
            ["2.3", "cmdId → Handler 分发", "GamePacketDispatcher"],
            ["2.4", "连接生命周期（绑定 Channel、断线清理）", "PlayerChannelAttributes"],
            ["2.5", "心跳、超时、可选 TLS", "GameNettyTlsSupport"],
            ["2.6", "幂等/防重放（可选）", "PacketIdempotencyHandler"],
        ],
        [18, 72, 100],
    )
    pdf.body("产出：客户端能连上、发心跳、收到 echo 或简单响应。")

    pdf.sub_title("阶段 3：玩家与会话（2–3 周）")
    pdf.table(
        ["步骤", "内容", "MyLunarCore 对应"],
        [
            ["3.1", "登录/注册（账号校验、Token/Session）", "PlayerSessionPacketHandlers"],
            ["3.2", "加载 Player 数据到内存", "OnlinePlayer、PlayerData"],
            ["3.3", "游戏主循环 Tick", "GameServer、PlayerTickRegistry"],
            ["3.4", "数据同步（增量/全量）", "PlayerCoreSyncable 等"],
            ["3.5", "下线持久化", "Repository + 定时/事件落库"],
        ],
        [18, 72, 100],
    )
    pdf.body("产出：登录 → 进入游戏 → 定时 Tick → 下线存盘，形成最小可玩闭环。")

    pdf.add_page()
    pdf.sub_title("阶段 4：玩法模块（按优先级迭代）")
    pdf.body("每个玩法建议统一模式：")
    pdf.code_block("PacketHandler → XxxNettyService → XxxManager/Runtime → Repository/Config")
    pdf.table(
        ["模块", "典型职责", "MyLunarCore 包"],
        [
            ["场景", "进入场景、AOI、移动同步", "scene/"],
            ["战斗", "回合/实时战斗、技能、波次", "battle/"],
            ["副本/挑战", "关卡、奖励、历史记录", "challenge/"],
            ["抽卡", "卡池、保底、Banner", "gacha/"],
            ["肉鸽", "房间、天赋、随机事件", "rogue/"],
            ["物品", "背包、使用、合成", "item/"],
        ],
        [30, 70, 90],
    )
    pdf.body("开发顺序建议：")
    for i, item in enumerate(
        [
            "场景 + 移动 — 验证同步与 Tick",
            "战斗 — 核心玩法，依赖配置表与状态机",
            "物品 + 抽卡 — 经济系统",
            "副本/活动 — 依赖前面模块",
        ],
        1,
    ):
        pdf.numbered(i, item)
    pdf.ln(2)
    pdf.body("每个模块：配置表 → 领域逻辑 → 网络接口 → 单元测试（如 BattleContextTest）。")

    pdf.sub_title("阶段 5：生产化与扩展（持续）")
    pdf.table(
        ["步骤", "内容", "MyLunarCore 对应"],
        [
            ["5.1", "配置热更", "HotReloadCoordinator"],
            ["5.2", "活动排期（日切、限时活动）", "ActivityScheduleService"],
            ["5.3", "GM/运营后台", "admin/"],
            ["5.4", "事件驱动解耦", "GameEventPublisher"],
            ["5.5", "压测与监控", "ServerMetricsMonitor"],
            ["5.6", "微服务拆分（可选）", "microservices/"],
        ],
        [18, 72, 100],
    )

    pdf.add_page()
    pdf.section_title("三、模块依赖关系（便于排期）")
    pdf.code_block(
        "                    ┌─────────────┐\n"
        "                    │  net/ 网络层 │\n"
        "                    └──────┬──────┘\n"
        "                           │\n"
        "              ┌────────────┼────────────┐\n"
        "              ▼            ▼            ▼\n"
        "        ┌──────────┐ ┌──────────┐ ┌──────────┐\n"
        "        │ player/  │ │  common/ │ │  config/ │\n"
        "        │ 会话数据  │ │ 游戏循环  │ │ 配置表   │\n"
        "        └────┬─────┘ └────┬─────┘ └────┬─────┘\n"
        "             └────────────┼────────────┘\n"
        "                          ▼\n"
        "        scene → battle → item/gacha/challenge/rogue"
    )
    pdf.body("原则：底层（net、player、config）稳定后再堆玩法；玩法之间通过事件或 Manager 接口解耦，避免循环依赖。")

    pdf.section_title("四、新手常见坑与建议")
    for i, item in enumerate(
        [
            "先跑通「登录 → 心跳 → 存盘」，再做大系统；否则后期改协议成本高。",
            "协议版本化：Protobuf 字段只增不改，cmdId 预留区间。",
            "业务与 IO 分离：Handler 里少做阻塞 DB；必要时异步或缓存。",
            "Tick 里避免重逻辑：战斗结算、抽卡可放独立线程或事件队列。",
            "配置与代码分离：数值策划改 JSON，服务端热更，少发版。",
            "测试先行：Repository、战斗公式、抽卡概率等要有单测。",
        ],
        1,
    ):
        pdf.numbered(i, item)

    pdf.section_title("五、结合 MyLunarCore 的下一步建议")
    pdf.body("MyLunarCore 已经具备：")
    for item in [
        "网络层（Netty + KCP + 包分发）",
        "游戏循环与在线玩家 Tick",
        "战斗、抽卡、副本、肉鸽等业务包",
        "配置热更、活动排期、管理端",
        "微服务迁移脚手架",
    ]:
        pdf.bullet(item)
    pdf.ln(2)
    pdf.body("若从零仿照学习，按上述 5 个阶段顺序做；若在现有项目上继续开发，建议：")
    for i, item in enumerate(
        [
            "用 FlowDemoCli / 单测理解一条完整链路（登录 → 战斗 → 抽卡）",
            "补全客户端联调与压测",
            "按 microservices/README.md 逐步拆 auth、player，再拆 battle、gacha 等",
        ],
        1,
    ):
        pdf.numbered(i, item)

    pdf.ln(6)
    pdf.set_font("SimHei", size=10)
    pdf.set_text_color(100, 100, 100)
    pdf.multi_cell(0, 7, "— 文档结束 —", align="C")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    pdf.output(str(OUTPUT))
    print(f"PDF saved to: {OUTPUT}")


if __name__ == "__main__":
    build_pdf()
