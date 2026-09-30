#!/usr/bin/env python3
"""从 docs/meta/cmdid-config-meta.yaml 生成配置清单摘要，保持文档与元数据同步。

用法:
  python scripts/generate_docs_from_meta.py
  python scripts/generate_docs_from_meta.py --check   # 仅校验已生成文件是否过期
"""
from __future__ import annotations

import pathlib
import sys

try:
    import yaml
except ImportError:
    print("PyYAML required: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

ROOT = pathlib.Path(__file__).resolve().parents[1]
META = ROOT / "docs" / "meta" / "cmdid-config-meta.yaml"
OUT = ROOT / "docs" / "generated-config-index.md"


def render() -> str:
    data = yaml.safe_load(META.read_text(encoding="utf-8"))
    lines = [
        "# 配置与 CmdId 索引（自动生成）",
        "",
        f"> 源：`docs/meta/cmdid-config-meta.yaml` · meta version `{data.get('version')}`",
        "",
        "## 配置文件",
        "",
        "| 文件 | Owner | Hot Reload |",
        "|------|-------|------------|",
    ]
    for c in data.get("config_files", []):
        lines.append(
            f"| `{c['name']}` | {c.get('owner','')} | {'是' if c.get('hot_reload') else '否'} |"
        )
    lines += ["", "## CmdId 区间", "", "| 区间 | 模块 |", "|------|------|"]
    for r in data.get("cmd_ranges", []):
        a, b = r["range"]
        lines.append(f"| {a}–{b} | {r.get('module','')} |")
    lines += [
        "",
        "---",
        "",
        "*由 `scripts/generate_docs_from_meta.py` 生成，请勿手改。*",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    check = "--check" in sys.argv
    text = render()
    if check:
        if not OUT.is_file():
            print(f"missing {OUT}", file=sys.stderr)
            sys.exit(2)
        current = OUT.read_text(encoding="utf-8")
        if current.replace("\r\n", "\n") != text.replace("\r\n", "\n"):
            print("generated-config-index.md is stale; run scripts/generate_docs_from_meta.py", file=sys.stderr)
            sys.exit(3)
        print("docs meta index up to date")
        return
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
