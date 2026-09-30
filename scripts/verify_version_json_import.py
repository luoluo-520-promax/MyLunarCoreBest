# -*- coding: utf-8 -*-
"""验证 hotfix.json 版本全字段是否可被解析。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOTFIX = ROOT / "data" / "hotfix.json"

REQUIRED_VERSION_FIELDS = {
    "clientResourceBaseUrl": str,
    "hotfixVersion": str,
    "patchVersion": int,
    "gameResourcePack": dict,
    "audioLanguagePacks": list,
    "versionInfo": dict,
    "deletedFiles": list,
    "configFiles": list,
    "patchTool": dict,
}


def main() -> int:
    data = json.loads(HOTFIX.read_text(encoding="utf-8"))
    missing = []
    for field, typ in REQUIRED_VERSION_FIELDS.items():
        if field not in data:
            missing.append(field)
            continue
        if not isinstance(data[field], typ):
            missing.append(f"{field}(type={type(data[field]).__name__})")

    nested_checks = [
        ("gameResourcePack.version", data.get("gameResourcePack", {}).get("version")),
        ("gameResourcePack.hash", data.get("gameResourcePack", {}).get("hash")),
        ("versionInfo.files", data.get("versionInfo", {}).get("files")),
        ("patchTool.algorithm", data.get("patchTool", {}).get("algorithm")),
        ("patchTool.verifyToolUrl", data.get("patchTool", {}).get("verifyToolUrl")),
    ]
    for name, value in nested_checks:
        if not value:
            missing.append(name)

    if missing:
        print("版本配置缺失字段:", missing)
        return 1

    print("版本 JSON 字段验证通过:")
    print(f"  - hotfixVersion: {data['hotfixVersion']}")
    print(f"  - gameResourcePack: {data['gameResourcePack']['version']} / hash={data['gameResourcePack']['hash'][:16]}...")
    print(f"  - audioLanguagePacks: {len(data['audioLanguagePacks'])} 个语言包")
    print(f"  - versionInfo.files: {len(data['versionInfo']['files'])} 个清单文件")
    print(f"  - deletedFiles: {len(data['deletedFiles'])} 个")
    print(f"  - configFiles: {len(data['configFiles'])} 个")
    print(f"  - patchTool: algorithm={data['patchTool']['algorithm']}, verifyToolUrl={data['patchTool']['verifyToolUrl']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
