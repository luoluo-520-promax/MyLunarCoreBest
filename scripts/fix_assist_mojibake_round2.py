# -*- coding: utf-8 -*-
"""二次修复：尽力恢复仍含乱码的行。"""
from __future__ import annotations

from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")

MARKERS = ("鍔", "璇诲", "缂撳", "鍙", "鎸夊", "鍚", "鐩稿", "杩", "鏈", "淇濆", "鍘熷", "鏌ヨ", "鍐欏")


def is_bad(line: str) -> bool:
    return any(m in line for m in MARKERS)


def recover_line(line: str) -> str:
    if not is_bad(line):
        return line
    # 先去掉无法用 GBK 表示的残余符号，再逆向
    cleaned = (
        line.replace("\ufffd", "")
        .replace("€", "")
        .replace("\u00a0", " ")
    )
    try:
        return cleaned.encode("gbk").decode("utf-8")
    except Exception:
        pass
    # 逐字符：能进 GBK 的留下，不能的丢掉后再整体逆变换
    buf = []
    for ch in cleaned:
        try:
            ch.encode("gbk")
            buf.append(ch)
        except UnicodeEncodeError:
            # 常见中文标点的乱码残留，直接丢弃
            continue
    try:
        return "".join(buf).encode("gbk").decode("utf-8")
    except Exception:
        return line


def main() -> None:
    fixed_files = 0
    fixed_lines = 0
    for path in sorted(ROOT.rglob("*.java")):
        text = path.read_text(encoding="utf-8-sig")
        lines = text.splitlines()
        new_lines = []
        changed = False
        for line in lines:
            if is_bad(line):
                recovered = recover_line(line)
                if recovered != line and not is_bad(recovered):
                    new_lines.append(recovered)
                    fixed_lines += 1
                    changed = True
                    continue
                # 若仍失败，至少去掉明显损坏的 javadoc/注释行中的乱码，保留代码
                if line.lstrip().startswith(("*", "//", "/*")):
                    # 用可逆失败时保留英文/代码部分
                    ascii_kept = "".join(ch if ord(ch) < 128 else "" for ch in line)
                    if ascii_kept.strip() in ("*", "//", "/*", "*/") or len(ascii_kept.strip()) < 3:
                        # 整行注释已无意义，改成简短占位，避免乱码留存
                        indent = line[: len(line) - len(line.lstrip())]
                        if line.lstrip().startswith("*"):
                            new_lines.append(indent + "* （注释编码已修复占位）")
                        else:
                            new_lines.append(indent + "// （注释编码已修复占位）")
                        fixed_lines += 1
                        changed = True
                        continue
            new_lines.append(line)
        if changed:
            path.write_bytes(("\n".join(new_lines) + "\n").encode("utf-8"))
            fixed_files += 1
            print("updated", path.relative_to(ROOT).as_posix())
    print(f"done files={fixed_files} lines={fixed_lines}")


if __name__ == "__main__":
    main()
