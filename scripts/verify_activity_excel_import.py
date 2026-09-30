# -*- coding: utf-8 -*-
"""验证 Excel 导入后活动全字段是否落盘并可被 JSON 解析。"""

from __future__ import annotations

import json
import shutil
import sys
import tempfile
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import import_activity_excel as importer  # noqa: E402

REQUIRED_ACTIVITY_FIELDS = [
    "activityId", "activityType", "beginTime", "endTime", "conditions", "name",
    "description", "gameplay", "rules", "stages", "costAndLimits", "rewards",
    "rewardMethod", "pointsTokens", "shopId", "shopProducts", "uiResources", "displayText",
]


def main() -> int:
    template = ROOT / "target" / "test_activity_template.xlsx"
    if not template.is_file():
        importer.generate_template(template)

    with tempfile.TemporaryDirectory() as tmp:
        data_dir = Path(tmp) / "data"
        activities_dir = data_dir / "activities"
        data_dir.mkdir(parents=True)
        activities_dir.mkdir(parents=True)

        original_data = importer.DATA_DIR
        original_activities = importer.ACTIVITIES_DIR
        importer.DATA_DIR = data_dir
        importer.ACTIVITIES_DIR = activities_dir
        try:
            importer.import_excel(template)
        finally:
            importer.DATA_DIR = original_data
            importer.ACTIVITIES_DIR = original_activities

        detail_path = activities_dir / "activity.5000701.json"
        unified_path = data_dir / "ActivityConfigs.json"
        assert detail_path.is_file(), "活动详情 JSON 未生成"
        assert unified_path.is_file(), "ActivityConfigs.json 未生成"

        detail = json.loads(detail_path.read_text(encoding="utf-8"))
        unified = json.loads(unified_path.read_text(encoding="utf-8"))
        assert isinstance(unified, list) and len(unified) == 1

        missing = [f for f in REQUIRED_ACTIVITY_FIELDS if f not in detail or detail.get(f) in (None, "", [], {})]
        # 允许空集合的字段做特殊判断
        optional_empty_ok = {"conditions", "stages", "pointsTokens", "shopProducts", "uiResources", "displayText", "costAndLimits"}
        real_missing = []
        for f in missing:
            if f in optional_empty_ok:
                if f not in detail:
                    real_missing.append(f)
            else:
                real_missing.append(f)

        if real_missing:
            print("Excel 导入缺失字段:", real_missing)
            return 1

        print("Excel 导入活动字段验证通过:")
        for field in REQUIRED_ACTIVITY_FIELDS:
            value = detail.get(field)
            preview = json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value
            if isinstance(preview, str) and len(preview) > 80:
                preview = preview[:80] + "..."
            print(f"  - {field}: {preview}")

        # 统一活动 sheet 应包含 banners/items
        unified_entry = unified[0]
        assert unified_entry.get("banners"), "统一活动应包含 banners"
        assert unified_entry.get("items"), "统一活动应包含 items"
        print("Excel 统一活动关联字段: banners/items OK")
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
