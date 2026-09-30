# -*- coding: utf-8 -*-
"""为 assist 包中缺少行尾注释的常见语句补上详细中文行尾注释。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/java/cn/itcast/demo/mylunarcore/assist"


def is_skip(t: str) -> bool:
    if not t or t in ("{", "}", "};"):
        return True
    if t.startswith(("//", "/*", "*", "package ", "import ", "@")):
        return True
    if t.startswith(
        (
            "public class",
            "public record",
            "public interface",
            "public enum",
            "public final class",
        )
    ):
        return True
    return False


def looks_signature_param(t: str) -> bool:
    if re.match(r"^[A-Z][\w<>,\s\.]*\s+\w+\s*,?\s*$", t) and "(" not in t and "=" not in t:
        return True
    if (
        t.endswith(") {")
        and not t.startswith(("if", "for", "while", "switch", "catch", "synchronized"))
        and re.match(r"^[\w.<>,\s\[\]]+\s+\w+\)\s*\{$", t)
    ):
        return True
    return False


def looks_multiline_arg(t: str) -> bool:
    if t.endswith(",") and not t.startswith(("if", "for", "return", "this.", "Map.entry")):
        if '"' in t or t.endswith("),") or t.endswith("},") or t.endswith(">,"):
            return True
    if t in (");", "},", "},"):
        return True
    if t.startswith('"') and (t.endswith('"') or t.endswith('",') or t.endswith('" +')):
        return True
    return False


def current_method(lines: list[str], idx: int) -> str:
    for j in range(idx, -1, -1):
        s = lines[j].strip()
        if re.match(r"^(public|private|protected|static).+\w+\s*\(", s):
            mm = re.search(r"(\w+)\s*\(", s)
            if mm:
                return mm.group(1).lower()
    return ""


def guess_comment(t: str, method_hint: str) -> str | None:
    if t == "return null;":
        mapping = {
            "quest": "条件不满足，本任务 tip/分支不输出",
            "activity": "条件不满足，本活动 tip/分支不输出",
            "growth": "条件不满足，本养成 tip 不输出",
            "avatar": "条件不满足，本角色 tip/分支不输出",
            "talent": "条件不满足，本天赋 tip 不输出",
            "meta": "条件不满足，本 meta tip 不输出",
            "match": "匹配失败，本 tip 不命中",
            "search": "无命中知识块，返回空",
            "get": "缓存未命中或未启用，返回 empty",
            "ask": "远程/本地问答不可用，返回 empty",
            "advise": "无匹配路线/建议，返回 empty 或空结果",
            "answer": "百科未命中，返回 empty",
            "extract": "未识别助手提问前缀，返回 null",
            "parse": "解析失败或输入为空，返回空列表",
        }
        for key, msg in mapping.items():
            if key in method_hint:
                return msg
        return "条件不满足，本分支不产出结果"
    if t == "return Optional.empty();":
        return "无可用结果，向上层返回 empty 以便继续降级链路"
    if t == "continue;":
        return "跳过本条无效或不符门槛的数据"
    if t.startswith("this.") and "=" in t and t.endswith(";"):
        field = t.split("=", 1)[0].strip().replace("this.", "")
        return f"保存构造注入的依赖字段 {field}，供后续业务方法使用"
    if re.match(r"^if\s*\(.*\)\s*\{?$", t):
        cond = t[t.find("(") + 1 : t.rfind(")")]
        if "null" in cond or "isEmpty" in cond or "isBlank" in cond:
            return f"判空/空集合后决定是否短路：{cond[:55]}"
        if "enabled" in cond.lower() or "isEnabled" in cond:
            return f"检查功能开关：{cond[:55]}"
        if "retcode" in cond or "status" in cond:
            return f"按业务状态码分支：{cond[:55]}"
        return f"条件分支：{cond[:55]}"
    if re.match(r"^for\s*\(.*\)\s*\{?$", t):
        return f"逐条处理集合元素：{t[4:65]}"
    if re.match(r"^while\s*\(.*\)\s*\{?$", t):
        return f"循环处理直到条件结束：{t[6:65]}"
    if t.startswith("switch "):
        return f"按取值分派处理：{t[7:65]}"
    if t.startswith("try {"):
        return "捕获外部 IO/推理异常，失败时降级而非打挂主流程"
    if t.startswith("catch ("):
        return "记录异常并走降级/错误码返回路径"
    if t.startswith("else if"):
        return f"否则若满足：{t[7:65]}"
    if t == "else {":
        return "上述条件均不成立时的兜底分支"
    if re.match(r"^return\s+.|;$", t) and "//" not in t:
        # 简单 return 常量/字段
        if re.match(r"^return\s+(true|false|0|1|\"\"|List\.of\(\)|Map\.of\(\)|Optional\.empty\(\));$", t):
            return f"返回固定值/空集合：{t[7:]}"
        if re.match(r"^return\s+\w+(\.\w+)*\(\);$", t):
            return f"委托调用并返回：{t[7:]}"
        if re.match(r"^return\s+\w+;$", t):
            return f"返回局部/字段结果：{t[7:]}"
    if t.startswith("Map.entry("):
        # synonym table etc.
        return "同义词映射条目：查询词归一到规范词以提升召回"
    if re.match(r"^\w[\w\.]*\s*=\s*.+;$", t) and not t.startswith("for "):
        left = t.split("=", 1)[0].strip()
        return f"计算并保存局部变量 {left}，供后续分支使用"
    if re.match(r"^\w[\w\.]*\.\w+\(.*\);$", t):
        return f"调用业务方法：{t[:70]}"
    return None


def patch_file(path: Path) -> int:
    lines = path.read_text(encoding="utf-8").splitlines()
    new_lines: list[str] = []
    changed = 0
    brace = 0
    for i, raw in enumerate(lines):
        t = raw.strip()
        brace += raw.count("{") - raw.count("}")
        if is_skip(t) or "//" in raw or brace < 1 or looks_signature_param(t) or looks_multiline_arg(t):
            new_lines.append(raw)
            continue
        prev = lines[i - 1].strip() if i else ""
        if prev.startswith("//") or prev.startswith("*") or prev.endswith("*/"):
            new_lines.append(raw)
            continue
        method = current_method(lines, i)
        cmt = guess_comment(t, method)
        if not cmt:
            new_lines.append(raw)
            continue
        base = raw.rstrip()
        new_lines.append(f"{base} // {cmt}")
        changed += 1
    if changed:
        path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")
    return changed


def main() -> None:
    total = 0
    for path in sorted(ROOT.rglob("*.java")):
        n = patch_file(path)
        if n:
            rel = path.relative_to(ROOT).as_posix()
            print(f"+{n:3d} {rel}")
            total += n
    print(f"done, total_inline_comments_added={total}")


if __name__ == "__main__":
    main()
