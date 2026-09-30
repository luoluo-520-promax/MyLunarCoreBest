# -*- coding: utf-8 -*-
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")


def main() -> None:
    print("=== corruption scan ===")
    for p in sorted(ROOT.rglob("*.java")):
        t = p.read_text(encoding="utf-8")
        issues = []
        if any(0xE000 <= ord(c) <= 0xF8FF for c in t):
            issues.append("pua")
        if "\ufffd" in t:
            issues.append("fffd")
        if "銆" in t or "婧" in t:
            issues.append("mojibake_mark")
        if "（注释编码已修复占位）" in t:
            issues.append("placeholder")
        lines = t.splitlines()
        for i, line in enumerate(lines):
            if line.strip() == "/**":
                for j in range(i + 1, min(i + 4, len(lines))):
                    s = lines[j].strip()
                    if not s:
                        continue
                    if s.startswith(("private ", "public ", "protected ", "@", "static ")):
                        issues.append(f"unclosed@{i + 1}")
                    break
            if "?)'" in line or '?")' in line or '?;' in line or "?," in line:
                issues.append(f"badquote@{i + 1}")
        if issues:
            print(p.relative_to(ROOT).as_posix(), sorted(set(issues))[:10])
    print("done")


if __name__ == "__main__":
    main()
