#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""为缺少 javadoc 的测试类批量写入中文注释：类说明 + 每个 @Test 的验证点与关键断言摘要。"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(r"c:\Users\ASUS\IdeaProjects\test\MyLunarCore")
LIST = ROOT / "scripts" / "_thin_tests.txt"


def is_thin(text: str) -> bool:
    return len(re.findall(r"/\*\*", text)) == 0 and len(re.findall(r"(?m)^\s*//", text)) < 3


def summarize_asserts(body: str) -> list[str]:
    tips: list[str] = []
    for line in body.splitlines():
        s = line.strip()
        if not s.startswith("assert") and "verify(" not in s and "when(" not in s:
            continue
        s = re.sub(r"\s+", " ", s)
        if len(s) > 120:
            s = s[:117] + "..."
        tips.append(s)
        if len(tips) >= 6:
            break
    return tips


def find_methods(text: str) -> list[tuple[int, int, str, str, str]]:
    """Return list of (start, end_of_anno_block, display, method_name, body_approx) for @Test methods.
    start is index of first annotation line (@Test or @DisplayName before method).
    """
    results = []
    # Match either order: DisplayName then Test, or Test then DisplayName
    pat = re.compile(
        r"(?P<block>(?:[ \t]*@DisplayName\(\"(?P<d1>[^\"]*)\"\)\s*\n)?"
        r"(?:[ \t]*@\w+(?:\([^;]*?\))?\s*\n)*?"
        r"[ \t]*@Test\s*\n"
        r"(?:[ \t]*@DisplayName\(\"(?P<d2>[^\"]*)\"\)\s*\n)?"
        r"(?:[ \t]*@\w+(?:\([^;]*?\))?\s*\n)*?"
        r"[ \t]*void\s+(?P<name>\w+)\s*\([^)]*\)[^{]*\{)",
        re.MULTILINE,
    )
    for m in pat.finditer(text):
        d = m.group("d1") or m.group("d2") or m.group("name")
        name = m.group("name")
        # extract body until matching braces roughly: from { to next \n    @Test or \n}
        start_body = m.end() - 1  # at {
        depth = 0
        i = start_body
        while i < len(text):
            ch = text[i]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    i += 1
                    break
            i += 1
        body = text[start_body:i]
        results.append((m.start(), m.end(), d, name, body))
    return results


def class_meta(text: str) -> tuple[str, str]:
    m = re.search(
        r'@DisplayName\("([^"]+)"\)\s*\n(?:@[^\n]+\n)*class\s+(\w+)',
        text,
    )
    if m:
        return m.group(1), m.group(2)
    m2 = re.search(r"class\s+(\w+)", text)
    name = m2.group(1) if m2 else "Test"
    return name, name


def annotate_file(text: str) -> str | None:
    if not is_thin(text):
        return None
    disp, cname = class_meta(text)
    class_doc = (
        f"/**\n"
        f" * {disp}。\n"
        f" * <p>\n"
        f" * 针对相关生产代码的单元/切片测试类 {{@code {cname}}}：\n"
        f" * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。\n"
        f" */\n"
    )

    # Insert class doc before the @DisplayName that sits immediately above class, else before class
    if re.search(r'@DisplayName\("[^"]+"\)\s*\n(?:@[^\n]+\n)*class\s+' + re.escape(cname), text):
        text = re.sub(
            r'(@DisplayName\("[^"]+"\)\s*\n(?:@[^\n]+\n)*class\s+' + re.escape(cname) + r")",
            class_doc + r"\1",
            text,
            count=1,
        )
    else:
        text = re.sub(
            r"(class\s+" + re.escape(cname) + r")",
            class_doc + r"\1",
            text,
            count=1,
        )

    methods = find_methods(text)
    # Insert from the end so offsets remain valid
    for start, end, d, name, body in reversed(methods):
        # skip if already has javadoc immediately before
        before = text[max(0, start - 80) : start]
        if "/**" in before:
            continue
        tips = summarize_asserts(body)
        lines = [
            "    /**",
            f"     * 验证点：{d}。",
            f"     * <p>测试方法 {{@code {name}}}：",
        ]
        if tips:
            lines.append("     * <ul>")
            for t in tips:
                # escape */
                t = t.replace("*/", "* /")
                lines.append(f"     *   <li>{{@code {t}}}</li>")
            lines.append("     * </ul>")
        else:
            lines.append("     * 按用例准备数据后断言返回值或协作对象调用是否符合预期。")
        lines.append("     */")
        doc = "\n".join(lines) + "\n"
        text = text[:start] + doc + text[start:]

    return text


def main() -> int:
    raw_list = LIST.read_text(encoding="utf-8-sig")
    paths = [Path(p.strip()) for p in raw_list.splitlines() if p.strip()]
    limit = int(sys.argv[1]) if len(sys.argv) > 1 else len(paths)
    offset = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    done = 0
    for path in paths[offset : offset + limit]:
        if not path.exists():
            print("missing", str(path).encode("ascii", "replace").decode("ascii"))
            continue
        raw = path.read_text(encoding="utf-8")
        out = annotate_file(raw)
        if out is None:
            continue
        path.write_bytes(out.encode("utf-8"))
        done += 1
        print("ok", path.name)
    print("DONE", done)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
