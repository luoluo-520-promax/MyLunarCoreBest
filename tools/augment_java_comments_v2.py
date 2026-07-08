# -*- coding: utf-8 -*-
"""为 Java 方法补充 Javadoc，并为方法体内无注释的语句追加简短中文行尾注释。"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP = ("target", "generated-sources", "tools")

METHOD_RE = re.compile(
    r"^(\s+)(public|protected|private)\s+(?:static\s+)?(?:final\s+)?"
    r"[\w<>,\?\[\]\s]+\s+(\w+)\s*\("
)
SKIP_METHOD_NAMES = {"class", "interface", "enum", "if", "for", "while", "switch", "catch"}

LINE_COMMENT_HINTS = [
    (re.compile(r"^\s*return\b.*//"), None),  # 已有注释则跳过
    (re.compile(r"^\s*throw\b"), "抛出未捕获的业务异常"),
    (re.compile(r"String\s+sql\s*="), "待执行的 SQL 语句"),
    (re.compile(r"jdbcTemplate\."), "JDBC 模板数据库操作"),
    (re.compile(r"rs\.get"), "从 ResultSet 读取列值"),
    (re.compile(r"it\.set"), "填充实体字段"),
    (re.compile(r"e\.set"), "填充实体字段"),
    (re.compile(r"assert\w+"), "测试断言"),
    (re.compile(r"when\("), "Mock 桩行为"),
    (re.compile(r"@Mock"), "Mock 依赖字段"),
]


def has_javadoc_above(lines, idx):
    j = idx - 1
    while j >= 0 and not lines[j].strip():
        j -= 1
    if j < 0:
        return False
    t = lines[j].strip()
    if t.startswith("@Override") or t.startswith("@Test") or t.startswith("@Before") or t.startswith("@After"):
        j -= 1
        while j >= 0 and not lines[j].strip():
            j -= 1
        if j < 0:
            return False
        t = lines[j].strip()
    return t.startswith("/**") or t.startswith("*")


def javadoc_for(name: str, indent: str) -> list[str]:
    hints = {
        "find": "按条件查询",
        "load": "加载数据",
        "list": "列出记录",
        "count": "统计数量",
        "update": "更新数据库",
        "add": "新增记录",
        "set": "设置字段",
        "get": "获取",
        "map": "映射 ResultSet 行",
        "ensure": "确保记录存在",
        "apply": "应用变更",
        "to": "转换为",
        "handle": "处理协议请求",
        "on": "事件/回调处理",
    }
    desc = "业务方法"
    for k, v in hints.items():
        if name.lower().startswith(k):
            desc = v
            break
    return [
        f"{indent}/**\n",
        f"{indent} * {desc}（方法名 {name}）。\n",
        f"{indent} */\n",
    ]


def trailing_hint(line):
    s = line.rstrip()
    if '"""' in s or s.strip().endswith('"""'):
        return None
    if "//" in s or not s.strip():
        return None
    t = s.strip()
    if t in ("{", "}", "};", ")") or t.startswith("@") or t.startswith("import ") or t.startswith("package "):
        return None
    indent = len(s) - len(s.lstrip())
    if indent < 4:
        return None
    for pat, hint in LINE_COMMENT_HINTS:
        if pat.search(s):
            if hint is None:
                return None
            return hint
    return None


def process_file(path: str) -> bool:
    with open(path, encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()

    out = []
    changed = False
    i = 0
    while i < len(lines):
        line = lines[i]
        m = METHOD_RE.match(line)
        if m and m.group(3) not in SKIP_METHOD_NAMES and not has_javadoc_above(lines, i):
            # 勿把构造器 Javadoc 插在已损坏的 this.xxx = 赋值块前
            nxt = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if not nxt.startswith("this."):
                indent = m.group(1)
                for jl in javadoc_for(m.group(3), indent):
                    out.append(jl)
                changed = True

        hint = trailing_hint(line)
        if hint and "//" not in line:
            line = line.rstrip() + f" // {hint}\n"
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
            if fn.endswith(".java"):
                p = os.path.join(dp, fn)
                if process_file(p):
                    updated.append(os.path.relpath(p, ROOT))
    print(f"Updated {len(updated)} files")


if __name__ == "__main__":
    main()
