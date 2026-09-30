# -*- coding: utf-8 -*-
"""恢复 assist 包中因错误编码产生的中文乱码，并删除敷衍自动注释。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")

BAD_INLINE = [
    " // 执行当前业务步骤",
    " // 保存当前业务计算结果",
    " // 按当前分支输出业务结果",
]

# 以「计算并保存局部变量」开头的敷衍注释整段删掉
BAD_PREFIX_RE = re.compile(r"\s*//\s*计算并保存局部变量.*$")


def looks_mojibake(text: str) -> bool:
    return any(x in text for x in ("鍔", "璇诲", "缂撳", "鍙", "鎸夊", "鍚"))


def line_is_mojibake(line: str) -> bool:
    return any(x in line for x in ("鍔", "璇诲", "缂撳", "鍙", "鎸夊", "鍚", "鐩稿", "杩"))


def recover_mojibake(text: str) -> str:
    """UTF-8 字节被按 GBK 解码后再存成 UTF-8 的逆向恢复；仅处理乱码行。"""
    out = []
    for line in text.splitlines(keepends=True):
        if not line_is_mojibake(line):
            out.append(line)
            continue
        try:
            out.append(line.encode("gbk").decode("utf-8"))
        except Exception:
            out.append(line)
    return "".join(out)


def strip_bad_comments(text: str) -> str:
    lines = []
    for line in text.splitlines():
        for bad in BAD_INLINE:
            if line.endswith(bad):
                line = line[: -len(bad)].rstrip()
        line = BAD_PREFIX_RE.sub("", line).rstrip()
        # 其它明显敷衍模式
        line = re.sub(r"\s*//\s*条件分支：.*$", "", line).rstrip() if "条件分支：" in line and "判空" not in line else line
        # 太泛的「条件分支」若仅复述条件，可保留带「判空」的；上面逻辑有点乱，改成只删纯复述
        lines.append(line)
    return "\n".join(lines) + "\n"


def clean_generic_branch_comments(text: str) -> str:
    """删除脚本生成的纯复述型 if 注释，例如 `// 条件分支：ttl > 0`。"""
    out = []
    for line in text.splitlines():
        if re.search(r"//\s*条件分支：", line) or re.search(r"//\s*检查功能开关：", line):
            # 若注释几乎只是复述条件表达式，删掉行尾这段，后续人工/agent 再补
            line = re.sub(r"\s*//\s*(条件分支|检查功能开关|判空/空集合后决定是否短路|逐条处理集合元素|循环处理直到条件结束|按取值分派处理)：.*$", "", line)
        if re.search(r"//\s*调用业务方法：", line):
            line = re.sub(r"\s*//\s*调用业务方法：.*$", "", line)
        if re.search(r"//\s*委托调用并返回：", line):
            line = re.sub(r"\s*//\s*委托调用并返回：.*$", "", line)
        if re.search(r"//\s*返回局部/字段结果：", line):
            line = re.sub(r"\s*//\s*返回局部/字段结果：.*$", "", line)
        if re.search(r"//\s*返回固定值/空集合：", line):
            line = re.sub(r"\s*//\s*返回固定值/空集合：.*$", "", line)
        if re.search(r"//\s*注入/保存依赖字段 ", line) or re.search(r"//\s*保存构造注入的依赖字段 ", line):
            # 这些其实还可以，保留
            pass
        out.append(line.rstrip())
    return "\n".join(out) + "\n"


def main() -> None:
    recovered = 0
    cleaned = 0
    for path in sorted(ROOT.rglob("*.java")):
        raw = path.read_bytes()
        text = raw.decode("utf-8-sig")  # 去掉可能的 BOM
        original = text
        if looks_mojibake(text):
            text = recover_mojibake(text)
            recovered += 1
        text2 = strip_bad_comments(text)
        text2 = clean_generic_branch_comments(text2)
        if text2 != original or raw.startswith(b"\xef\xbb\xbf"):
            path.write_bytes(text2.encode("utf-8"))
            cleaned += 1
            print(f"fixed {path.relative_to(ROOT).as_posix()} mojibake={looks_mojibake(original)}")
    print(f"done recovered={recovered} written={cleaned}")


if __name__ == "__main__":
    main()
