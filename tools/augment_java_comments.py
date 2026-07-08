# -*- coding: utf-8 -*-
"""为 Java 源文件中缺少注释的 import / package 行补充简体中文行注释（不改动业务逻辑）。"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = ("target", "generated-sources", "tools")

IMPORT_HINTS = {
    "lombok": "Lombok 注解或代码生成",
    "springframework": "Spring 框架类",
    "netty": "Netty 网络库",
    "jackson": "Jackson JSON 库",
    "junit": "JUnit 测试",
    "testng": "TestNG 测试框架",
    "mockito": "Mockito 模拟框架",
    "slf4j": "SLF4J 日志接口",
    "jdbc": "JDBC 数据访问",
    "protobuf": "Protobuf 协议",
    "jsonwebtoken": "JJWT 令牌库",
    "reactor": "Project Reactor 响应式",
    "io.netty": "Netty IO",
    "java.util": "JDK 集合或工具",
    "java.time": "JDK 日期时间",
    "java.sql": "JDK SQL 类型",
    "java.io": "JDK IO",
    "java.math": "JDK 数学类型",
    "java.nio": "JDK NIO",
    "javax.crypto": "JDK 加密",
    "mylunarcore": "本项目业务类",
}

def hint_for_import(line: str) -> str:
    imp = line.strip().removeprefix("import ").removesuffix(";").strip()
    if imp.startswith("static "):
        return f"静态导入：{imp[7:]}"
    for key, hint in IMPORT_HINTS.items():
        if key in imp.lower():
            return hint
    simple = imp.split(".")[-1]
    return f"引入 {simple}"


def process_file(path: str) -> bool:
    with open(path, encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()

    out = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if stripped.startswith("package ") and (not out or not out[-1].strip().startswith("//")):
            pkg = stripped.replace("package", "").replace(";", "").strip()
            out.append(f"// 当前类所属包：{pkg}\n")
            changed = True

        elif stripped.startswith("import ") and (not out or not out[-1].strip().startswith("//")):
            out.append(f"// {hint_for_import(stripped)}\n")
            changed = True

        out.append(line)
        i += 1

    if changed:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(out)
    return changed


def main():
    updated = []
    for dp, _, fs in os.walk(ROOT):
        if any(s in dp for s in SKIP):
            continue
        for fn in fs:
            if not fn.endswith(".java"):
                continue
            p = os.path.join(dp, fn)
            if process_file(p):
                updated.append(os.path.relpath(p, ROOT))
    print(f"Updated {len(updated)} files")
    for u in sorted(updated)[:80]:
        print(" ", u)
    if len(updated) > 80:
        print(f"  ... and {len(updated) - 80} more")


if __name__ == "__main__":
    main()
