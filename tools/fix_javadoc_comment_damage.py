# -*- coding: utf-8 -*-
"""修复 annotate 脚本误加到 Javadoc 行上的行尾注释。"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = [
    "src/main/java/cn/itcast/demo/mylunarcore/battle/BattleNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/scene/SceneNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/rogue/RogueNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/item/ItemNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/gacha/GachaNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/challenge/ChallengeNettyService.java",
]

JAVADOC_TAIL = re.compile(r"\s// .*$")


def fix_file(rel: str) -> bool:
    path = os.path.join(ROOT, rel)
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()
    out = []
    changed = False
    in_javadoc = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("/**"):
            in_javadoc = True
        if in_javadoc:
            cleaned = JAVADOC_TAIL.sub("", line.rstrip()) + "\n"
            if cleaned != line:
                changed = True
            out.append(cleaned)
            if stripped.startswith("*/"):
                in_javadoc = False
            continue
        out.append(line)
    if changed:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(out)
    return changed


def main():
    n = sum(fix_file(t) for t in TARGETS)
    print(f"Fixed Javadoc in {n} files")


if __name__ == "__main__":
    main()
