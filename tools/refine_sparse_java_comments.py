# -*- coding: utf-8 -*-
"""整理「当前类所属包」风格的 Java 文件：去重 import 注释、压缩空行、为方法体补充行尾中文注释。"""
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP = ("target", "generated-sources", "tools", "microservices")

PACKAGE_DESC = {
    "AppLogger": "按日志分类构造 SLF4J Logger",
    "ConsoleReloadCommandListener": "控制台 stdin 监听：识别 /reload 命令并触发热重载",
    "HotReloadCoordinator": "/reload 聚合入口：热修复、活动、抽卡配置与静态资源失效",
    "HotfixDataService": "从 classpath/file 加载 HotfixData 并与 /reload 联动",
    "StaticResourceRegistry": "静态表格资源注册表：启动登记路径，支持热失效后延迟重载",
    "GameEventPublisher": "Spring ApplicationEvent 发布的薄封装",
    "GameTrafficMetrics": "游戏协议流量计数与统计",
    "ServerMetricsMonitor": "JVM 与业务指标定时采集",
    "OnlinePlayer": "在线玩家快照：uid、Channel 与 Tick 状态",
    "PlayerTickRegistry": "在线玩家 Tick 注册表",
    "GameResourceFactory": "静态资源工厂：按类型创建 Tabular 等资源",
    "GameResourceKind": "静态资源种类枚举",
    "StaticResourceId": "已知静态表格资源 ID 与路径枚举",
    "TabularStaticResource": "表格型静态资源：延迟加载 CSV 行",
    "Tickable": "可被游戏主循环 Tick 的对象接口",
    "PlayerDataPeriodicPersistenceService": "玩家数据定时落库服务",
    "PlayerDataAsyncLoadService": "玩家数据异步加载服务",
    "PlayerDataSyncService": "玩家数据同步编排服务",
    "PlayerSyncCoordinator": "多 Syncable 模块的同步协调器",
    "PlayerSessionService": "登录、登出与会话校验 Netty 业务",
    "PlayerChannelAttributes": "Channel 上存放玩家 uid 等属性的键",
    "PlayerCurrencyHelper": "玩家货币字段读写辅助",
    "PlayerCoreSyncable": "玩家核心字段同步模块",
    "AvatarSyncable": "角色 Avatar 数据同步模块",
    "LineupSyncable": "阵容 Lineup 数据同步模块",
    "UnlockSyncable": "解锁进度同步模块",
    "Syncable": "可同步到客户端/数据库的玩家数据片段",
    "SyncReason": "触发同步的原因枚举",
    "GameDataRepository": "游戏全局数据 JDBC 仓储",
}

LINE_RULES = [
    (re.compile(r"this\.(\w+) = \1;"), lambda m: f"保存注入的 {m.group(1)} 引用"),
    (re.compile(r"this\.(\w+) = (\w+);"), lambda m: f"保存注入的 {m.group(1)} 引用"),
    (re.compile(r"return (\w+);"), lambda m: f"返回 {m.group(1)}"),
    (re.compile(r"return false;"), "加载或校验失败"),
    (re.compile(r"return true;"), "操作成功"),
    (re.compile(r"reload\(\);"), "重新加载热修复 JSON"),
    (re.compile(r"reloadSchedule\(\);"), "重新加载活动排期 JSON"),
    (re.compile(r"reloadAll\(\);"), "失效全部已注册静态表格缓存"),
    (re.compile(r"invalidate\(\);"), "标记资源需下次访问时重新加载"),
    (re.compile(r"publishEvent\("), "分发给所有 @EventListener 监听方法"),
    (re.compile(r"if \(!resource\.exists\(\)\)"), "资源路径不存在则保留旧数据"),
    (re.compile(r"if \(next == null\)"), "JSON 解析结果为 null 时使用空对象"),
    (re.compile(r"if \(!properties\.isReloadConsoleEnabled\(\)\)"), "配置关闭时不启动 stdin 监听"),
    (re.compile(r"thread\.setDaemon\(true\)"), "守护线程不阻止 JVM 退出"),
    (re.compile(r"thread\.start\(\)"), "启动后台读取线程"),
    (re.compile(r"thread\.interrupt\(\)"), "打断 readLine 阻塞以加快退出"),
    (re.compile(r"stopped\.set\(true\)"), "通知循环线程退出"),
    (re.compile(r"for \(StaticResourceId id"), "遍历所有已知静态资源枚举项"),
    (re.compile(r"for \(TabularStaticResource r"), "逐个失效已缓存的表格资源"),
    (re.compile(r"byType\.put\("), "注册 resourceType 到表格资源实例"),
    (re.compile(r"Optional\.ofNullable\("), "包装可能为 null 的查找结果"),
    (re.compile(r"Collections\.unmodifiableMap\("), "返回不可修改的注册表视图"),
    (re.compile(r"LoggerFactory\.getLogger\("), "按分类名创建或复用 SLF4J Logger"),
    (re.compile(r"log\.(info|warn|error|debug)\("), "记录运行日志"),
    (re.compile(r"try \("), "try-with-resources 自动关闭流"),
    (re.compile(r"\} catch \(Exception e\) \{"), "捕获异常并记录，避免中断主流程"),
]

SKIP_BODY = re.compile(
    r"^\s*(?:public|protected|private|static|final|@|\*|/\*\*|package |import |\}|class |interface |record |\{|\)|$)"
)


def class_name(text: str) -> str:
    m = re.search(r"public\s+(?:final\s+)?class\s+(\w+)", text)
    return m.group(1) if m else ""


def hint_for_line(stripped: str):
    if "//" in stripped and re.search(r"[\u4e00-\u9fff]", stripped):
        return None
    for pat, hint in LINE_RULES:
        m = pat.search(stripped)
        if m:
            return hint(m) if callable(hint) else hint
    if stripped.startswith("if ("):
        return "条件分支"
    if stripped.startswith("return "):
        return "返回结果"
    if stripped.startswith("this."):
        return "字段赋值"
    return None


GENERIC_COMMENT = re.compile(
    r"^// (?:Spring 框架类|本项目业务类|JDK 集合或工具|SLF4J 日志接口|引入 PostConstruct|引入 PreDestroy)$"
)


def collect_preceding_comments(lines: list[str], idx: int) -> tuple[list[str], int]:
    """收集 import/package 前连续的 // 注释（跳过中间空行）。"""
    comments = []
    j = idx - 1
    while j >= 0:
        s = lines[j].strip()
        if s == "":
            j -= 1
            continue
        if s.startswith("//"):
            comments.insert(0, s[2:].strip())
            j -= 1
            continue
        break
    return comments, j


def dedupe_imports(lines: list[str]) -> list[str]:
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("import ") and i > 0:
            comments, _ = collect_preceding_comments(lines, i)
            while out and out[-1].strip().startswith("//"):
                out.pop()
            while out and out[-1].strip() == "":
                out.pop()
            if comments:
                chosen = None
                for c in comments:
                    if not GENERIC_COMMENT.match("// " + c):
                        chosen = c
                        break
                if chosen is None:
                    chosen = comments[-1]
                out.append(f"// {chosen}\n")
            out.append(line)
            i += 1
            continue
        out.append(line)
        i += 1
    return out


def fix_duplicate_package_comment(lines: list[str]) -> list[str]:
    """移除 package 前重复的包注释行，只保留最有意义的一条。"""
    out = []
    i = 0
    while i < len(lines):
        if lines[i].strip().startswith("package "):
            comments = []
            j = i - 1
            while j >= 0:
                s = lines[j].strip()
                if s == "":
                    j -= 1
                    continue
                if s.startswith("//"):
                    comments.insert(0, s[2:].strip())
                    j -= 1
                    continue
                break
            while out and (out[-1].strip().startswith("//") or out[-1].strip() == ""):
                out.pop()
            if comments:
                chosen = comments[-1]
                for c in comments:
                    if "所在包" not in c and "模块" not in c and "业务模块" not in c:
                        chosen = c
                        break
                out.append(f"// {chosen}\n")
            out.append(lines[i])
            return out + fix_duplicate_package_comment(lines[i + 1 :])
        out.append(lines[i])
        i += 1
    return lines


def compress_blanks(lines: list[str]) -> list[str]:
    out = []
    prev_blank = False
    for line in lines:
        blank = line.strip() == ""
        if blank and prev_blank:
            continue
        out.append(line)
        prev_blank = blank
    return out


def fix_package_comment(lines: list[str], cn: str) -> list[str]:
    out = []
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("package "):
            desc = PACKAGE_DESC.get(cn, "业务模块")
            if i > 0 and lines[i - 1].strip().startswith("//"):
                out.pop()  # 替换旧 package 注释
            out.append(f"// {desc}\n")
            out.append(lines[i])
            return out + lines[i + 1 :]
        out.append(lines[i])
        i += 1
    return lines


def annotate_body(lines: list[str]) -> list[str]:
    out = []
    in_class = False
    brace = 0
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("public class ") or stripped.startswith("public final class "):
            in_class = True
        if in_class:
            brace += stripped.count("{") - stripped.count("}")
        if (
            in_class
            and brace >= 2
            and stripped
            and not stripped.startswith("//")
            and not SKIP_BODY.match(stripped)
            and "//" not in stripped
        ):
            hint = hint_for_line(stripped)
            if hint:
                line = line.rstrip() + f" // {hint}\n"
        out.append(line)
    return out


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if "// Spring 框架类" not in text and "// 当前类所属包" not in text:
        return False
    cn = class_name(text)
    lines = text.splitlines(keepends=True)
    if "// 当前类所属包" in text:
        lines = fix_package_comment(lines, cn)
    lines = fix_duplicate_package_comment(lines)
    lines = dedupe_imports(lines)
    lines = compress_blanks(lines)
    lines = annotate_body(lines)
    new_text = "".join(lines)
    if new_text != text:
        path.write_text(new_text, encoding="utf-8")
        return True
    return False


def main():
    updated = []
    for p in ROOT.rglob("src/**/*.java"):
        if any(s in str(p) for s in SKIP):
            continue
        if process_file(p):
            updated.append(str(p.relative_to(ROOT)))
    print(f"refined={len(updated)}")
    for u in sorted(updated):
        print(u)


if __name__ == "__main__":
    main()
