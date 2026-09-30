# -*- coding: utf-8 -*-
"""尝试恢复 Java 源码中被损坏的中文字符串字面量。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")


def strip_pua(s: str) -> str:
    return "".join(ch for ch in s if not (0xE000 <= ord(ch) <= 0xF8FF) and ch != "\ufffd")


def try_recover(s: str) -> str | None:
    cleaned = strip_pua(s).replace("€", "")
    if cleaned == s and not any(0x4e00 <= ord(c) <= 0x9fff for c in s):
        return None
    # 已是正常中文则跳过
    if "助手" in s or "任务" in s or "邮件" in s:
        if not any(0xE000 <= ord(c) <= 0xF8FF for c in s) and "\ufffd" not in s:
            # 可能仍含乱码字符
            if not any(ord(c) > 0x9fff and ord(c) < 0xE000 for c in s if ord(c) > 127):
                pass
    try:
        recovered = cleaned.encode("gbk").decode("utf-8")
        return recovered
    except Exception:
        return None


# 已知正确字符串（来自会话早期未损坏版本 / 业务约定）
KNOWN_FIXES: dict[str, str] = {
    # AiAssistApplicationService
    'AssistAnswer.of(5, "鍔╂墜鏆傛椂涓嶅彲鐢\ue7d2紝璇风◢鍚庡啀璇曘€?, "error"':
        'AssistAnswer.of(5, "助手暂时不可用，请稍后再试。", "error"',
    'return lower.contains("閭\ue1bb欢") || lower.contains("閭\ue1be\ue188") || lower.contains("mail");':
        'return lower.contains("邮件") || lower.contains("邮箱") || lower.contains("mail");',
    'if (q.contains("鍏绘垚") || q.contains("绐佺牬") || q.contains("澶╄祴") || q.contains("鍔犵偣")) {':
        'if (q.contains("养成") || q.contains("突破") || q.contains("天赋") || q.contains("加点")) {',
    'if (q.contains("閭\ue1bb欢") || q.contains("mail")) {':
        'if (q.contains("邮件") || q.contains("mail")) {',
}


def recover_file(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    original = text
    # 1) exact known replacements for unique broken snippets
    for bad, good in KNOWN_FIXES.items():
        if bad in text:
            text = text.replace(bad, good)

    # 2) recover quoted strings that look corrupted
    def repl_string(m: re.Match) -> str:
        full = m.group(0)
        inner = m.group(1)
        if not any(0xE000 <= ord(c) <= 0xF8FF for c in inner) and "\ufffd" not in inner:
            # check mojibake markers
            if not any(x in inner for x in ("鍔", "鍏", "閭", "鐑", "璺", "妫", "瑙", "鏍")):
                return full
        recovered = try_recover(inner)
        if recovered and recovered != inner:
            # escape for java string
            recovered = recovered.replace("\\", "\\\\").replace('"', '\\"')
            return '"' + recovered + '"'
        return full

    text2 = re.sub(r'"((?:\\.|[^"\\])*)"', repl_string, text)

    if text2 != original:
        path.write_bytes(text2.encode("utf-8"))
        return 1
    return 0


def main() -> None:
    n = 0
    for path in sorted(ROOT.rglob("*.java")):
        content = path.read_text(encoding="utf-8")
        if any(0xE000 <= ord(c) <= 0xF8FF for c in content) or "\ufffd" in content or any(
            x in content for x in ("鍔╂", "閭", "鐑", "璺\ue21c")
        ):
            n += recover_file(path)
            print("processed", path.relative_to(ROOT).as_posix())
    print("files_updated", n)


if __name__ == "__main__":
    main()
