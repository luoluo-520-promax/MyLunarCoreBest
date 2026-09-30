# -*- coding: utf-8 -*-
"""剥离无法恢复的乱码注释，保留可执行代码。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")

MARKERS = (
    "鍔", "璇诲", "缂撳", "鍙", "鎸夊", "鍚", "鐩稿", "杩", "鏈", "淇濆",
    "鍘熷", "鏌ヨ", "鍐欏", "瀹為", "浠呯", "鍛戒", "绮剧", "渚涘",
)


def has_pua(s: str) -> bool:
    return any(0xE000 <= ord(ch) <= 0xF8FF for ch in s)


def is_corrupt(s: str) -> bool:
    return has_pua(s) or any(m in s for m in MARKERS) or "\ufffd" in s


def sanitize_line(line: str) -> str:
    stripped = line.lstrip()
    indent = line[: len(line) - len(stripped)]

    # 整行注释/javadoc
    if stripped.startswith("//"):
        if is_corrupt(stripped):
            return ""  # 删除损坏整行注释
        return line.rstrip()
    if stripped.startswith("*") or stripped.startswith("/*") or stripped.startswith("*/"):
        if is_corrupt(stripped):
            # 保留 javadoc 结构最小骨架
            if stripped.startswith("/**"):
                return indent + "/**"
            if stripped.startswith("*/"):
                return indent + "*/"
            if stripped.startswith("*"):
                return indent + " *"
            return ""
        return line.rstrip()

    # 行尾注释
    if "//" in line:
        code, comment = line.split("//", 1)
        if is_corrupt(comment):
            return code.rstrip()
        return line.rstrip()
    return line.rstrip()


def main() -> None:
    for path in sorted(ROOT.rglob("*.java")):
        lines = path.read_text(encoding="utf-8-sig").splitlines()
        new_lines = []
        removed = 0
        for line in lines:
            s = sanitize_line(line)
            if s == "" and line.strip() != "":
                removed += 1
                continue
            new_lines.append(s)
        # 压缩连续空 javadoc 骨架
        text = "\n".join(new_lines) + "\n"
        text = re.sub(r"/\*\*\n(?:\s*\*\n)+ \*/", "/** */", text)
        path.write_bytes(text.encode("utf-8"))
        print(f"{path.relative_to(ROOT).as_posix()}: removed_corrupt_lines~={removed}")


if __name__ == "__main__":
    main()
