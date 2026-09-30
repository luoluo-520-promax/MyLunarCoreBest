# -*- coding: utf-8 -*-
import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")


def gaps_of(path: Path):
    lines = path.read_text(encoding="utf-8").splitlines()
    brace = 0
    gaps = []
    code = 0
    covered = 0
    for i, raw in enumerate(lines):
        t = raw.strip()
        brace += raw.count("{") - raw.count("}")
        skip = (
            not t
            or t in ("{", "}", "};")
            or t.startswith(
                (
                    "//",
                    "/*",
                    "*",
                    "package ",
                    "import ",
                    "@",
                    "public class",
                    "public record",
                    "public interface",
                    "public enum",
                    "public final class",
                )
            )
        )
        if skip or brace < 1:
            continue
        if re.search(r"\b(public|private|protected)\b.+\(", t) and not any(
            t.startswith(x) for x in ("if ", "for ", "while ", "switch ", "return ", "new ")
        ):
            continue
        if re.match(r"^[A-Z][A-Za-z0-9_<>,\s\.]*\s+[a-zA-Z_][a-zA-Z0-9_]*\s*,?\s*$", t) and "(" not in t:
            continue
        if (
            t.endswith(") {")
            and not any(t.startswith(x) for x in ("if", "for", "while", "switch", "try", "catch", "else", "synchronized"))
            and re.match(r"^[\w.<>,\s\[\]]+\s+\w+\)\s*\{$", t)
        ):
            continue
        if t.endswith(",") and not t.startswith(("if", "for", "return", "this.", "Map.entry")):
            if '"' in t or t.endswith(("),", "},", ">,")):
                continue
        if t.startswith('"') and (t.endswith('"') or t.endswith('",') or t.endswith('" +')):
            continue
        code += 1
        prev = lines[i - 1].strip() if i > 0 else ""
        ok = ("//" in t) or prev.startswith("//") or prev.startswith("*") or prev.endswith("*/")
        if ok:
            covered += 1
        else:
            gaps.append((i + 1, t[:110]))
    pct = round(100 * covered / code) if code else 100
    return pct, code - covered, code, gaps


def main():
    results = []
    for path in sorted(ROOT.rglob("*.java")):
        pct, g, c, gaps = gaps_of(path)
        results.append((pct, g, c, path.relative_to(ROOT).as_posix(), gaps))
    results.sort()
    print("Below 95%:")
    for pct, g, c, f, gaps in results:
        if pct < 95:
            print(f"{pct:3d}% miss={g:3d}/{c:<4d} {f}")
    print("---")
    print(
        f"avg={sum(r[0] for r in results)/len(results):.1f}% "
        f"covered={sum(r[2]-r[1] for r in results)}/{sum(r[2] for r in results)}"
    )
    for pct, g, c, f, gaps in results:
        if g >= 10:
            print(f"\n==== {f} ({g}) ====")
            for ln, t in gaps[:25]:
                print(f"{ln}: {t}")


if __name__ == "__main__":
    main()
