# -*- coding: utf-8 -*-
"""Generate MyLunarCore hand-rebuild plan PDF to Desktop."""

from fpdf import FPDF
from pathlib import Path

DESKTOP = Path.home() / "Desktop"
OUTPUT = DESKTOP / "MyLunarCore手敲复刻计划.pdf"
FONT = Path(r"C:\Windows\Fonts\simhei.ttf")


class PlanPDF(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("SimHei", size=9)
            self.set_text_color(120, 120, 120)
            self.cell(0, 8, "MyLunarCore 手敲复刻计划", align="C", new_x="LMARGIN", new_y="NEXT")
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
        self.set_font("SimHei", size=9)
        self.set_text_color(20, 20, 20)
        for line in text.split("\n"):
            self.cell(self.epw, 5.5, f"  {line}", new_x="LMARGIN", new_y="NEXT", fill=True)
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
    pdf = PlanPDF()
    pdf.set_auto_page_break(auto=True, margin=15)
    pdf.add_font("SimHei", "", str(FONT))
    pdf.add_page()

    # Cover
    pdf.ln(35)
    pdf.set_font("SimHei", size=26)
    pdf.set_text_color(25, 75, 140)
    pdf.multi_cell(0, 14, "MyLunarCore\n手敲复刻计划", align="C")
    pdf.ln(10)
    pdf.set_font("SimHei", size=14)
    pdf.set_text_color(80, 80, 80)
    pdf.multi_cell(0, 8, "按依赖顺序 · 分阶段编写 · 里程碑验收", align="C")
    pdf.ln(16)
    pdf.set_font("SimHei", size=11)
    pdf.multi_cell(
        0,
        7,
        "本文档整合 MyLunarCore 项目从零手敲复刻的完整编写顺序，\n"
        "涵盖 175 个主代码文件、77 个测试、9 个 Proto 协议。",
        align="C",
    )
    pdf.ln(24)
    pdf.set_font("SimHei", size=10)
    pdf.set_text_color(120, 120, 120)
    pdf.cell(0, 8, "生成日期：2026-07-12", align="C")

    pdf.add_page()
    pdf.section_title("目录概览")
    for item in [
        "一、总体架构与核心链路",
        "二、阶段 0：工程骨架",
        "三、阶段 1：配置层 config",
        "四、阶段 2：公共基础设施 common",
        "五、阶段 3：数据模型 model",
        "六、阶段 4：数据访问层 repo",
        "七、阶段 5：Protobuf 协议",
        "八、阶段 6：网络层 net",
        "九、阶段 7：玩家会话层 player",
        "十、阶段 8：业务模块（垂直切片）",
        "十一、阶段 9：管理后台 admin",
        "十二、阶段 10：监控 metrics",
        "十三、阶段 11：Demo 与脚本",
        "十四、阶段 12：测试补全",
        "十五、推荐手敲节奏（18-25 天）",
        "十六、最小可运行路径",
        "十七、手敲五条原则",
    ]:
        pdf.body(item)

    pdf.add_page()
    pdf.section_title("一、总体架构与核心链路")
    pdf.body(
        "MyLunarCore 是 Spring Boot 4 + Netty/KCP + Protobuf + MySQL 的私有游戏服。"
        "按依赖从底向上编写，每阶段结束都能 mvn compile 或 mvn test 验证。"
    )
    pdf.sub_title("架构分层")
    pdf.code_block(
        "客户端 (TCP/KCP)\n"
        "    ↓\n"
        "net 层：GameNettyServer / GameKcpServer → LunarFrame Codec\n"
        "    → GamePacketDispatcher → *PacketHandlers\n"
        "    ↓\n"
        "player 层：GameSessionManager → PlayerLoginApplicationService\n"
        "    → PlayerDataSyncService\n"
        "    ↓\n"
        "业务模块：battle / scene / item / gacha / challenge / rogue / activity\n"
        "    ↓\n"
        "基础设施：repo → MySQL；common / config / admin HTTP"
    )
    pdf.sub_title("核心链路")
    pdf.body(
        "客户端发包 → Netty 编解码 → GamePacketDispatcher → *PacketHandlers "
        "→ *NettyService / *ApplicationService → repo → MySQL"
    )
    pdf.sub_title("包结构与文件规模")
    pdf.table(
        ["包名", "文件数", "职责"],
        [
            ["common", "34", "游戏循环、热更、活动、监控"],
            ["net", "34", "Netty/KCP、编解码、包分发"],
            ["player", "21", "会话、登录、同步、持久化"],
            ["repo", "19", "JDBC 数据访问"],
            ["model", "18", "Entity 与 PlayerData"],
            ["battle", "9", "战斗逻辑"],
            ["admin", "10", "Spring Security 管理端"],
            ["gacha", "6", "抽卡系统"],
            ["config", "5", "配置绑定与线程池"],
            ["item/scene/challenge/rogue", "各 3", "玩法模块"],
            ["metrics", "1", "Prometheus 指标"],
        ],
        [35, 20, 135],
    )

    pdf.add_page()
    pdf.section_title("二、阶段 0：工程骨架（第 1 天）")
    pdf.table(
        ["顺序", "内容", "文件/说明"],
        [
            ["0.1", "Maven 项目", "pom.xml（Spring Boot 4.0.4、Netty、KCP、Protobuf、MySQL 等）"],
            ["0.2", "目录结构", "src/main/java/cn/itcast/demo/mylunarcore/ 下 15 个子包"],
            ["0.3", "主入口", "MyLunarCoreApplication.java"],
            ["0.4", "配置文件", "application.properties + dev/test/prod"],
            ["0.5", "日志", "logback-spring.xml"],
            ["0.6", "数据库", "lunarcore_merged.sql"],
            ["0.7", "静态数据", "data/ 下 JSON/CSV + resources/data/ 副本"],
            ["0.8", "测试骨架", "testng-unit.xml、testng-integration.xml"],
        ],
        [18, 35, 137],
    )
    pdf.body("验收：mvn compile 通过（此时只有空壳主类）")

    pdf.section_title("三、阶段 1：配置层 config（5 文件）")
    pdf.table(
        ["顺序", "类", "说明"],
        [
            ["1.1", "LunarCoreProperties.java", "所有 lunarcore.* 配置绑定"],
            ["1.2", "NettyGameBusinessConfiguration.java", "Netty 业务线程池 Bean"],
            ["1.3", "GameConfigurationManager.java", "游戏配置管理"],
            ["1.4", "GameConfigurationManagerWire.java", "配置加载与 PostConstruct 接线"],
        ],
        [18, 70, 102],
    )
    pdf.body("验收：启动 Spring 容器，能读到 lunarcore.netty.port=9000")

    pdf.add_page()
    pdf.section_title("四、阶段 2：公共基础设施 common（34 文件）")

    pdf.sub_title("2A — 无依赖工具类（先写）")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.1", "LogCategory.java、AppLogger.java"],
            ["2.2", "StaticResourceId.java"],
            ["2.3", "HotfixData.java"],
            ["2.4", "GameEvent.java、BattleEvent.java 等事件 POJO"],
            ["2.5", "OnlinePlayer.java"],
        ],
        [18, 172],
    )

    pdf.sub_title("2B — 静态资源与配置加载")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.6", "StaticResourceRegistry.java"],
            ["2.7", "TabularStaticResource.java、GameResourceFactory.java"],
            ["2.8", "HotfixDataService.java"],
            ["2.9", "ConfigFileService.java"],
        ],
        [18, 172],
    )

    pdf.sub_title("2C — 游戏循环与事件")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.10", "PlayerTickRegistry.java"],
            ["2.11", "GameEventPublisher.java、BattleEventLoggingListener.java"],
            ["2.12", "ActivityScheduleService.java"],
            ["2.13", "GameServer.java"],
            ["2.14", "GameServerPacketCache.java"],
        ],
        [18, 172],
    )

    pdf.sub_title("2D — 热更新与运维")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.15", "HotReloadCoordinator.java"],
            ["2.16", "ConsoleReloadCommandListener.java"],
            ["2.17", "GracefulShutdownCoordinator.java"],
            ["2.18", "UpdateNotifyBroadcaster.java"],
        ],
        [18, 172],
    )

    pdf.add_page()
    pdf.sub_title("2E — 活动 / 版本 / 导入")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.19", "ActivityConfigService、ActivityConfigHotReloadService"],
            ["2.20", "ActivityImportService、ActivityNettyService"],
            ["2.21", "VersionHotReloadService、VersionNettyService、VersionUpdateMapper"],
            ["2.22", "ConfigImportService"],
        ],
        [18, 172],
    )

    pdf.sub_title("2F — 监控")
    pdf.table(
        ["顺序", "类"],
        [
            ["2.23", "GameTrafficMetrics.java"],
            ["2.24", "ServerMetricsMonitor.java"],
        ],
        [18, 172],
    )
    pdf.body("验收：GameServerTest、StaticResourceRegistryTest、HotReloadCoordinatorTest 通过")

    pdf.section_title("五、阶段 3：数据模型 model（18 文件）")
    pdf.table(
        ["顺序", "类", "说明"],
        [
            ["3.1", "AccountEntity.java", "账号"],
            ["3.2", "PlayerData.java", "玩家核心数据（最重要）"],
            ["3.3", "GameItemEntity、FriendEntity", "物品、好友"],
            ["3.4", "战斗相关 Entity", "BattleMonsterWaveEntity 等"],
            ["3.5", "肉鸽相关 Entity", "RoguePlayerDataEntity、RogueTalentEntity 等"],
            ["3.6", "ActivityConfig.java", "活动配置"],
            ["3.7", "其他 Entity", "按 SQL 表逐个对应"],
        ],
        [18, 65, 107],
    )
    pdf.body("原则：Entity 只放字段 + getter/setter，不写业务逻辑")

    pdf.section_title("六、阶段 4：数据访问层 repo（19 文件）")
    pdf.table(
        ["顺序", "类", "说明"],
        [
            ["4.1", "GameDataRepository.java", "基础 JDBC 封装（其他 repo 依赖它）"],
            ["4.2", "PlayerDataRepository.java", "玩家 CRUD"],
            ["4.3", "ItemRepository.java", "物品"],
            ["4.4", "GachaRepository.java", "抽卡"],
            ["4.5", "其余 Repository", "MonsterConfig、SceneConfig、ChallengeHistory 等"],
        ],
        [18, 70, 102],
    )
    pdf.body("验收：GameDataRepositoryTest、PlayerDataRepositoryTest 通过")

    pdf.add_page()
    pdf.section_title("七、阶段 5：Protobuf 协议（第 5 天）")
    pdf.table(
        ["顺序", "proto 文件", "对应业务"],
        [
            ["5.1", "player_session.proto", "登录/心跳/同步"],
            ["5.2", "version_update.proto", "版本热更"],
            ["5.3", "battle_system.proto", "战斗"],
            ["5.4", "scene_system.proto", "场景"],
            ["5.5", "item_system.proto", "物品"],
            ["5.6", "gacha_system.proto", "抽卡"],
            ["5.7", "challenge_system.proto", "挑战"],
            ["5.8", "rogue_system.proto", "肉鸽"],
            ["5.9", "activity_system.proto", "活动"],
        ],
        [18, 65, 107],
    )
    pdf.body("操作：mvn compile 生成 Java 类到 target/generated-sources/protobuf")

    pdf.section_title("八、阶段 6：网络层 net（34 文件，核心）")

    pdf.sub_title("6A — 协议常量与帧格式")
    pdf.table(
        ["顺序", "类"],
        [
            ["6.1", "CmdIds.java — 所有命令号常量"],
            ["6.2", "LunarFrameConstants.java"],
            ["6.3", "LunarFrameDecoder.java、LunarFrameEncoder.java"],
        ],
        [18, 172],
    )

    pdf.sub_title("6B — 分发与处理框架")
    pdf.table(
        ["顺序", "类"],
        [
            ["6.4", "PacketCommandHandler.java（接口/注解）"],
            ["6.5", "PacketCommandRegistry.java"],
            ["6.6", "GamePacketDispatcher.java"],
            ["6.7", "PacketIdempotencyHandler.java"],
        ],
        [18, 172],
    )

    pdf.sub_title("6C — 连接治理")
    pdf.table(
        ["顺序", "类"],
        [
            ["6.8", "NettyChannelOptions.java"],
            ["6.9", "ConnectionPacketRateLimiterHandler.java"],
            ["6.10", "GameLoginRateLimiter.java"],
            ["6.11", "NettyBackpressureHandler.java"],
            ["6.12", "NetTraceContext.java、NetTraceContextHandler.java"],
        ],
        [18, 172],
    )

    pdf.add_page()
    pdf.sub_title("6D — 服务器启动")
    pdf.table(
        ["顺序", "类"],
        [
            ["6.13", "GameServerChannelHandler.java"],
            ["6.14", "GameNettyServer.java（TCP）"],
            ["6.15", "UkcpChannelAccessor.java"],
            ["6.16", "GameServerKcpListener.java、GameKcpServer.java（KCP/UDP）"],
        ],
        [18, 172],
    )

    pdf.sub_title("6E — Proto Mapper")
    pdf.table(
        ["顺序", "类"],
        [
            ["6.17", "ItemProtoMapper.java、ActivityProtoMapper.java"],
        ],
        [18, 172],
    )
    pdf.body("验收：LunarFrameCodecTest、GamePacketDispatcherTest 通过")

    pdf.section_title("九、阶段 7：玩家会话层 player（21 文件）")
    pdf.table(
        ["顺序", "类", "说明"],
        [
            ["7.1", "GameSession、PlayerChannelAttributes", "会话对象"],
            ["7.2", "Syncable、PlayerCoreSyncable、SyncReason、DataChangeScope", "同步抽象"],
            ["7.3", "PlayerSessionLockService", "会话锁"],
            ["7.4", "GameSessionManager", "在线玩家管理"],
            ["7.5", "PlayerDataCacheService", "Caffeine 缓存"],
            ["7.6", "PlayerDataAsyncLoadService", "异步加载"],
            ["7.7", "PlayerDataSyncService", "数据同步推送"],
            ["7.8", "PlayerDataPeriodicPersistenceService", "定时落库"],
            ["7.9", "PlayerLoginApplicationService", "登录业务"],
            ["7.10", "PlayerSessionService", "会话服务"],
            ["7.11", "ConnectionLifecycleService", "连接生命周期"],
            ["7.12", "PlayerContextResolver", "上下文解析"],
            ["7.13", "PlayerSessionPacketHandlers", "登录/心跳/同步 Handler"],
            ["7.14", "UpdatePacketHandlers", "版本查询 Handler"],
        ],
        [18, 85, 87],
    )
    pdf.body("里程碑：客户端能连上 9000 端口并完成登录")

    pdf.add_page()
    pdf.section_title("十、阶段 8：业务模块（垂直切片）")
    pdf.body("每个模块遵循同一模式：业务逻辑 → NettyService → PacketHandlers → 测试")

    modules = [
        ("8.1 物品 item（3 文件）", "ItemJsonParser → ItemApplicationService → ItemNettyService → ItemPacketHandlers"),
        ("8.2 战斗 battle（9 文件）", "EntityState → BattleContext → BattleManager → BattleSceneFactory → BattleNettyService → BattlePacketHandlers"),
        ("8.3 场景 scene（3 文件）", "SceneContext → SceneNettyService → ScenePacketHandlers"),
        ("8.4 抽卡 gacha（6 文件）", "GachaBannerType → GachaConfigService → GachaApplicationService → GachaNettyService → GachaPacketHandlers"),
        ("8.5 挑战 challenge（3 文件）", "ChallengeManager → ChallengeNettyService → ChallengePacketHandlers"),
        ("8.6 肉鸽 rogue（3 文件）", "RogueNettyService → RoguePacketHandlers"),
        ("8.7 活动", "ActivityNettyService + ActivityProtoMapper（net/mapper）"),
    ]
    for title, flow in modules:
        pdf.sub_title(title)
        pdf.body(flow)
    pdf.body("每模块验收：对应 *NettyServiceTest 通过")

    pdf.section_title("十一、阶段 9：管理后台 admin（10 文件）")
    pdf.table(
        ["顺序", "类"],
        [
            ["9.1", "AdminSecurityConfig.java"],
            ["9.2", "AdminUserDetailsService.java、AdminRbacRepository.java"],
            ["9.3", "AdminLoginController.java、RootController.java"],
            ["9.4", "HotReloadController.java"],
            ["9.5", "ConfigImportController.java"],
            ["9.6", "AdminSecurityAdvice.java"],
        ],
        [18, 172],
    )
    pdf.body("验收：POST /api/admin/ops/reload 能触发热更新")

    pdf.section_title("十二、阶段 10：监控 metrics（1 文件）")
    pdf.body("GameServerMetricsBinder.java — Prometheus 指标绑定")
    pdf.body("验收：访问 /actuator/prometheus 有游戏指标")

    pdf.section_title("十三、阶段 11：Demo 与脚本（可选）")
    for item in [
        "demo/FlowDemoCli.java、FlowDemoArgs.java 等",
        "scripts/import_activity_excel.py 等活动导入脚本",
    ]:
        pdf.bullet(item)

    pdf.add_page()
    pdf.section_title("十四、阶段 12：测试补全（贯穿全程）")
    pdf.table(
        ["优先级", "测试类型", "代表文件"],
        [
            ["P0", "网络编解码", "LunarFrameCodecTest"],
            ["P0", "登录会话", "GameSessionManagerTest"],
            ["P0", "数据仓库", "GameDataRepositoryTest"],
            ["P1", "各模块 Service", "BattleNettyServiceTest、GachaNettyServiceTest 等"],
            ["P2", "集成", "UpdateMechanismIntegrationTest、RealDataConfigUsageTest"],
        ],
        [25, 45, 120],
    )
    pdf.sub_title("运行命令")
    pdf.code_block(
        "mvn test -Punit              # 单元测试\n"
        "mvn test -Pintegration       # 集成测试\n"
        "mvn spring-boot:run -Penv-dev   # 本地启动"
    )

    pdf.section_title("十五、推荐手敲节奏（18-25 天）")
    pdf.table(
        ["周", "目标", "产出"],
        [
            ["第 1 周", "阶段 0-4", "能连 MySQL、读写玩家数据"],
            ["第 2 周", "阶段 5-7", "客户端能登录、心跳"],
            ["第 3 周", "阶段 8（item+battle+scene）", "三大核心玩法可用"],
            ["第 4 周", "阶段 8（gacha+challenge+rogue+activity）", "全玩法"],
            ["第 5 周", "阶段 9-12", "运维后台 + 测试 + 调优"],
        ],
        [25, 55, 110],
    )

    pdf.section_title("十六、最小可运行路径（时间紧时优先）")
    pdf.code_block(
        "pom → LunarCoreProperties → AppLogger → GameDataRepository → PlayerData\n"
        "→ CmdIds → LunarFrameCodec → GamePacketDispatcher → GameNettyServer\n"
        "→ GameSessionManager → PlayerLoginApplicationService\n"
        "→ PlayerSessionPacketHandlers → GameServer"
    )
    pdf.body("打通后：启动 → 监听 9000 → 登录 → 心跳，再逐步加 battle/scene/item 等模块。")

    pdf.section_title("十七、手敲五条原则")
    for i, item in enumerate(
        [
            "严格按依赖顺序：不要先写 BattleNettyService 再写 BattleManager",
            "每写完一个类就编译：mvn compile -q，避免错误堆积",
            "Proto 与 CmdIds 同步：改命令号必须同时改 proto、Handler、Registry",
            "对照原项目目录结构：包名、类名保持一致，便于 diff 验证",
            "测试驱动验证：每模块至少跑一个 *Test，确认行为一致",
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
