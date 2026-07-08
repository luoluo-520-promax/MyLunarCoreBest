#!/usr/bin/env python3
"""为 Java 源文件添加 package 前注释与逐语句中文注释（AuthController 风格）。"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

PACKAGE_DESC = {
    "challenge.domain": "挑战玩法领域实体",
    "challenge.repo": "挑战玩法数据仓储",
    "challenge.runtime": "挑战玩法运行时状态",
    "challenge.service": "挑战玩法 Netty 业务服务",
    "gacha.config": "抽卡卡池配置",
    "gacha.domain": "抽卡玩家数据实体",
    "gacha.repo": "抽卡数据仓储",
    "gacha.service": "抽卡 Netty 业务与热更新",
    "game": "在线玩家与游戏主循环 Tick",
    "item.repo": "背包道具数据仓储",
    "item.runtime": "道具 JSON 解析",
    "item.service": "道具 Netty 业务服务",
    "logging": "统一日志分类与门面",
    "metrics": "服务器指标与流量统计",
    "reload": "配置与资源热重载",
    "repository.game": "游戏全局数据仓储",
    "repository.player": "玩家数据仓储",
    "resource": "静态表格资源加载",
    "hotfix": "客户端热修复配置",
    "cache": "协议包体序列化缓存",
    "activity": "活动排期服务",
    "rogue.domain": "模拟宇宙领域实体",
    "rogue.repo": "模拟宇宙数据仓储",
    "rogue.runtime": "模拟宇宙运行时",
    "rogue.service": "模拟宇宙 Netty 业务",
    "scene.repo": "场景配置仓储",
    "scene.runtime": "场景运行时",
    "scene.service": "场景 Netty 业务",
    "session": "游戏会话与 Channel 绑定",
    "sync": "玩家数据同步与持久化",
}

IMPORT_HINTS = {
    "lombok.Data": "Lombok 自动生成 getter/setter 等",
    "lombok.Getter": "Lombok 只生成 getter",
    "org.springframework.stereotype.Repository": "声明为 Spring 数据访问 Bean",
    "org.springframework.stereotype.Service": "声明为 Spring 业务服务 Bean",
    "org.springframework.stereotype.Component": "声明为 Spring 组件 Bean",
    "org.springframework.jdbc.core.JdbcTemplate": "Spring JDBC 模板",
    "io.netty.channel.Channel": "Netty 客户端连接通道",
    "io.netty.util.AttributeKey": "Channel 属性键",
    "org.slf4j.Logger": "SLF4J 日志接口",
    "java.util.concurrent.ConcurrentHashMap": "线程安全哈希表",
    "java.util.Optional": "可能为空的单值容器",
    "java.sql.Timestamp": "SQL 时间戳类型",
    "java.util.List": "列表集合",
    "java.util.Map": "键值映射",
    "java.util.Collections": "集合工具类",
    "java.util.ArrayList": "可变数组列表",
    "jakarta.annotation.PostConstruct": "Bean 初始化后回调",
    "jakarta.annotation.PreDestroy": "Bean 销毁前回调",
}


def pkg_suffix(pkg: str) -> str:
    base = "cn.itcast.demo.mylunarcore."
    if pkg.startswith(base):
        return pkg[len(base):]
    return pkg


def package_comment(pkg: str) -> str:
    suf = pkg_suffix(pkg)
    for key, desc in PACKAGE_DESC.items():
        if suf == key or suf.startswith(key + "."):
            return f"// {desc}所在包"
    parts = suf.split(".")
    return f"// {parts[-1]} 模块所在包"


def hint_for_import(imp: str) -> str:
    if imp in IMPORT_HINTS:
        return IMPORT_HINTS[imp]
    seg = imp.rsplit(".", 1)[-1]
    if seg.endswith("Proto"):
        return f"协议 {seg} 消息定义"
    if seg.endswith("Entity"):
        return f"实体 {seg}"
    if seg.endswith("Repository"):
        return f"仓储 {seg}"
    if seg.endswith("Service"):
        return f"服务 {seg}"
    if seg.endswith("Manager"):
        return f"管理器 {seg}"
    if seg.endswith("Properties"):
        return f"配置属性 {seg}"
    return f"{seg} 类型"


def has_pkg_comment(text: str) -> bool:
    return text.lstrip().startswith("//")


def process_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    if has_pkg_comment(text):
        return False

    lines = text.splitlines(keepends=True)
    out = []
    i = 0
    # skip leading blank
    while i < len(lines) and lines[i].strip() == "":
        out.append(lines[i])
        i += 1

    if i >= len(lines) or not lines[i].startswith("package "):
        return False

    pkg_line = lines[i].strip()
    pkg = pkg_line.replace("package ", "").replace(";", "").strip()
    out.append(package_comment(pkg) + "\n")
    out.append(lines[i])
    i += 1

    imports = []
    body_start = i
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("import "):
            imports.append(lines[i])
            i += 1
            continue
        if s == "" and imports:
            i += 1
            continue
        break

    for imp_line in imports:
        imp = imp_line.strip().replace("import ", "").replace(";", "").strip()
        if imp.startswith("static "):
            out.append(imp_line)
        else:
            out.append(f"// {hint_for_import(imp)}\n")
            out.append(imp_line)

    if imports and i < len(lines) and lines[i - 1].strip() != "":
        out.append("\n")

    # rest of file with light statement comments
    rest = "".join(lines[i:])
    rest = annotate_body(rest)
    out.append(rest)

    new_text = "".join(out)
    if new_text != text:
        path.write_text(new_text, encoding="utf-8")
        return True
    return False


def annotate_body(body: str) -> str:
    """为常见语句模式追加行尾或行前注释（避免重复注释）。"""
    lines = body.splitlines(keepends=True)
    result = []
    for line in lines:
        stripped = line.strip()
        indent = line[: len(line) - len(line.lstrip())]

        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/**"):
            result.append(line)
            continue
        if stripped.startswith("@") and not stripped.startswith("@Override"):
            result.append(line)
            continue

        # 已有行尾中文注释则跳过
        if "//" in stripped and re.search(r"[\u4e00-\u9fff]", stripped):
            result.append(line)
            continue

        extra = None
        if re.match(r"private\s+final\s+", stripped):
            extra = " // 不可变依赖"
        elif re.match(r"private\s+static\s+final\s+Logger", stripped):
            extra = " // 本类日志记录器"
        elif re.match(r"private\s+static\s+final\s+AttributeKey", stripped):
            extra = " // Channel 上存放玩家 uid 的属性键"
        elif re.match(r"return\s+.*\.newBuilder\(\)", stripped) and "setRetcode" in stripped:
            extra = " // 构造协议响应"
        elif stripped == "}" or stripped == "};":
            result.append(line)
            continue
        elif stripped.startswith("if (") and "== null" in stripped:
            extra = " // 空值校验"
        elif stripped.startswith("if (playerId") or "playerId <=" in stripped:
            extra = " // 未登录或非法玩家"
        elif stripped.startswith("this."):
            extra = None
        elif stripped.startswith("return null"):
            extra = " // 无数据"
        elif stripped.startswith("return "):
            if "Optional.empty" in stripped:
                extra = " // 查无记录"
            elif "Collections.empty" in stripped:
                extra = " // 返回空集合"
            elif extra is None and "newBuilder" not in stripped:
                extra = None

        if extra and not stripped.endswith(extra.strip()):
            # 行尾追加
            nl = "\n" if line.endswith("\n") else ""
            core = line.rstrip("\n\r")
            result.append(core.rstrip() + extra + nl)
        else:
            result.append(line)

    return "".join(result)


def main():
    list_file = ROOT / "files-needing-comments.txt"
    paths = []
    if list_file.exists():
        for raw in list_file.read_text(encoding="utf-8").splitlines():
            p = raw.strip()
            if not p or p.startswith("#"):
                continue
            # 从 challenge 行起（用户要求）
            paths.append(ROOT / p.replace("\\", "/"))
    else:
        paths = list(ROOT.rglob("src/**/*.java"))

    changed = []
    for p in paths:
        if not p.exists():
            continue
        if process_file(p):
            changed.append(str(p.relative_to(ROOT)))

    print(f"modified={len(changed)}")
    for c in changed:
        print(c)


if __name__ == "__main__":
    main()
