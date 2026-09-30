# -*- coding: utf-8 -*-
"""Excel 策划表一键导入：排期 / 卡池 / 道具 / 活动详情 / 统一活动。"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib import error, request

try:
    import pandas as pd
except ImportError as exc:  # pragma: no cover - runtime guard
    print("缺少依赖 pandas，请先执行: pip install -r scripts/requirements-import.txt", file=sys.stderr)
    raise SystemExit(1) from exc

ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
ACTIVITIES_DIR = DATA_DIR / "activities"

SHEET_SCHEDULE = "排期"
SHEET_BANNERS = "卡池"
SHEET_ITEMS = "道具"
SHEET_DETAIL = "活动详情"
SHEET_UNIFIED = "统一活动"

SCHEDULE_COLUMNS = ["activityId", "beginTime", "endTime", "moduleId"]
BANNER_COLUMNS = ["id", "gachaType", "beginTime", "endTime", "rateUpItems5", "rateUpItems4"]
ITEM_COLUMNS = ["id", "name", "stack"]
DETAIL_COLUMNS = [
    "activityId", "name", "activityType", "moduleId", "beginTime", "endTime", "unlockLevel",
    "conditions", "description", "gameplay", "rules", "stages", "costAndLimits",
    "rewards", "rewardMethod", "pointsTokens", "shopId", "shopProducts",
    "uiResources", "displayText", "extra",
]
UNIFIED_COLUMNS = [
    "activityId", "name", "activityType", "moduleId", "beginTime", "endTime", "unlockLevel",
    "conditions", "description", "gameplay", "rules", "stages", "costAndLimits",
    "rewards", "rewardMethod", "pointsTokens", "shopId", "shopProducts",
    "uiResources", "displayText", "banners", "items", "extra",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="将 Excel 策划表导入 data/ 配置并可选触发 /reload")
    parser.add_argument("excel", nargs="?", help="Excel 文件路径")
    parser.add_argument("--excel", dest="excel_opt", help="Excel 文件路径")
    parser.add_argument("--generate-template", action="store_true", help="生成示例策划表 Excel")
    parser.add_argument("--template-output", default=str(ROOT / "data" / "activity_template.xlsx"))
    parser.add_argument("--reload-url", default="", help="导入后 POST 热重载，如 http://localhost:8080/api/admin/ops/reload")
    parser.add_argument("--session-cookie", default="", help="JSESSIONID=... 管理端登录 Cookie")
    return parser.parse_args()


def ensure_dirs() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    ACTIVITIES_DIR.mkdir(parents=True, exist_ok=True)


def parse_time(value: Any) -> int:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return 0
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value).strip()
    if not text:
        return 0
    if text.isdigit():
        return int(text)
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return int(datetime.strptime(text, fmt).timestamp())
        except ValueError:
            continue
    raise ValueError(f"无法解析时间: {value}")


def parse_int_list(value: Any) -> list[int]:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return []
    if isinstance(value, list):
        return [int(v) for v in value]
    text = str(value).strip()
    if not text:
        return []
    return [int(part.strip()) for part in text.replace(";", ",").split(",") if part.strip()]


def parse_json_field(value: Any, default: Any) -> Any:
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return default
    if isinstance(value, (dict, list)):
        return value
    text = str(value).strip()
    if not text:
        return default
    return json.loads(text)


def read_sheet(excel_path: Path, sheet_name: str) -> pd.DataFrame:
    try:
        df = pd.read_excel(excel_path, sheet_name=sheet_name)
    except ValueError:
        return pd.DataFrame()
    if df.empty:
        return df
    df = df.dropna(how="all")
    return df


def export_schedule(df: pd.DataFrame) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        if pd.isna(row.get("activityId")):
            continue
        rows.append({
            "activityId": int(row["activityId"]),
            "beginTime": parse_time(row.get("beginTime")),
            "endTime": parse_time(row.get("endTime")),
            "moduleId": int(row.get("moduleId", 0)),
        })
    return rows


def export_banners(df: pd.DataFrame) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        if pd.isna(row.get("id")):
            continue
        rows.append({
            "id": int(row["id"]),
            "gachaType": str(row.get("gachaType", "Normal")),
            "beginTime": parse_time(row.get("beginTime")),
            "endTime": parse_time(row.get("endTime")),
            "rateUpItems5": parse_int_list(row.get("rateUpItems5")),
            "rateUpItems4": parse_int_list(row.get("rateUpItems4")),
        })
    return rows


def export_items_csv(df: pd.DataFrame, output: Path) -> None:
    with output.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["# Generated by import_activity_excel.py"])
        writer.writerow(ITEM_COLUMNS)
        for _, row in df.iterrows():
            if pd.isna(row.get("id")):
                continue
            writer.writerow([
                int(row["id"]),
                str(row.get("name", "")),
                int(row.get("stack", 1)),
            ])


def build_activity_config(row: pd.Series, include_relations: bool) -> dict[str, Any]:
    activity_id = int(row["activityId"])
    config: dict[str, Any] = {
        "activityId": activity_id,
        "name": str(row.get("name", "")),
        "activityType": str(row.get("activityType", "")),
        "moduleId": int(row.get("moduleId", 0)),
        "beginTime": parse_time(row.get("beginTime")),
        "endTime": parse_time(row.get("endTime")),
        "unlockLevel": int(row.get("unlockLevel", 0)),
        "conditions": parse_json_field(row.get("conditions"), []),
        "description": str(row.get("description", "")),
        "gameplay": str(row.get("gameplay", "")),
        "rules": str(row.get("rules", "")),
        "stages": parse_json_field(row.get("stages"), []),
        "costAndLimits": parse_json_field(row.get("costAndLimits"), {}),
        "rewards": parse_json_field(row.get("rewards"), []),
        "rewardMethod": str(row.get("rewardMethod", "auto")),
        "pointsTokens": parse_json_field(row.get("pointsTokens"), []),
        "shopId": int(row.get("shopId", 0)),
        "shopProducts": parse_json_field(row.get("shopProducts"), []),
        "uiResources": parse_json_field(row.get("uiResources"), {}),
        "displayText": parse_json_field(row.get("displayText"), {}),
        "banners": [] if not include_relations else parse_json_field(row.get("banners"), []),
        "items": [] if not include_relations else parse_json_field(row.get("items"), []),
        "extra": parse_json_field(row.get("extra"), {}),
    }
    return config


def export_activity_details(df: pd.DataFrame) -> list[dict[str, Any]]:
    configs: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        if pd.isna(row.get("activityId")):
            continue
        activity_id = int(row["activityId"])
        config = build_activity_config(row, include_relations=False)
        detail_path = ACTIVITIES_DIR / f"activity.{activity_id}.json"
        detail_path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
        configs.append(config)
    return configs


def export_unified(df: pd.DataFrame) -> list[dict[str, Any]]:
    configs: list[dict[str, Any]] = []
    for _, row in df.iterrows():
        if pd.isna(row.get("activityId")):
            continue
        activity_id = int(row["activityId"])
        config = build_activity_config(row, include_relations=True)
        detail_path = ACTIVITIES_DIR / f"activity.{activity_id}.json"
        detail_path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")
        configs.append(config)
    return configs


def write_json(path: Path, data: Any) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def read_json_list(path: Path) -> list[dict[str, Any]]:
    if not path.is_file():
        return []
    data = json.loads(path.read_text(encoding="utf-8"))
    return data if isinstance(data, list) else []


def merge_by_key(existing: list[dict[str, Any]], incoming: list[dict[str, Any]], key: str) -> list[dict[str, Any]]:
    merged: dict[Any, dict[str, Any]] = {row[key]: row for row in existing if key in row}
    for row in incoming:
        if key in row:
            merged[row[key]] = row
    return list(merged.values())


def merge_schedule(existing_path: Path, incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return merge_by_key(read_json_list(existing_path), incoming, "activityId")


def merge_banners(existing_path: Path, incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return merge_by_key(read_json_list(existing_path), incoming, "id")


def merge_unified(existing_path: Path, incoming: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return merge_by_key(read_json_list(existing_path), incoming, "activityId")


def merge_items_csv(existing_path: Path, incoming_df: pd.DataFrame) -> None:
    existing_rows: dict[int, dict[str, Any]] = {}
    if existing_path.is_file():
        df = pd.read_csv(existing_path, comment="#")
        for _, row in df.iterrows():
            if pd.isna(row.get("id")):
                continue
            existing_rows[int(row["id"])] = row.to_dict()

    for _, row in incoming_df.iterrows():
        if pd.isna(row.get("id")):
            continue
        existing_rows[int(row["id"])] = {
            "id": int(row["id"]),
            "name": str(row.get("name", "")),
            "stack": int(row.get("stack", 1)),
        }

    with existing_path.open("w", encoding="utf-8", newline="") as fp:
        writer = csv.writer(fp)
        writer.writerow(["# Generated by import_activity_excel.py"])
        writer.writerow(ITEM_COLUMNS)
        for item_id in sorted(existing_rows):
            row = existing_rows[item_id]
            writer.writerow([row["id"], row["name"], row["stack"]])


def generate_template(output: Path) -> None:
    ensure_dirs()
    with pd.ExcelWriter(output, engine="openpyxl") as writer:
        pd.DataFrame([
            {"activityId": 5000701, "beginTime": "2026-07-01 00:00:00", "endTime": "2026-08-01 00:00:00", "moduleId": 50007},
        ], columns=SCHEDULE_COLUMNS).to_excel(writer, sheet_name=SHEET_SCHEDULE, index=False)

        pd.DataFrame([
            {
                "id": 9001,
                "gachaType": "AvatarUp",
                "beginTime": "2026-07-01 00:00:00",
                "endTime": "2026-08-01 00:00:00",
                "rateUpItems5": "1102,1204",
                "rateUpItems4": "1105,1106",
            },
        ], columns=BANNER_COLUMNS).to_excel(writer, sheet_name=SHEET_BANNERS, index=False)

        pd.DataFrame([
            {"id": 90001, "name": "夏日代币", "stack": 999},
        ], columns=ITEM_COLUMNS).to_excel(writer, sheet_name=SHEET_ITEMS, index=False)

        pd.DataFrame([
            {
                "activityId": 5000701,
                "name": "夏日庆典",
                "activityType": "seasonal",
                "moduleId": 50007,
                "beginTime": "2026-07-01 00:00:00",
                "endTime": "2026-08-01 00:00:00",
                "unlockLevel": 20,
                "conditions": '[{"type":"level","intValue":20}]',
                "description": "参与夏日庆典，收集代币兑换限定奖励。",
                "gameplay": "完成每日任务与关卡挑战，获取夏日代币并在活动商店兑换道具。",
                "rules": "每日参与次数有限；代币活动结束清零。",
                "stages": '[{"stageId":1,"name":"开幕阶段","unlockScore":0,"rewards":[{"itemId":90001,"count":50,"type":"token"}]}]',
                "costAndLimits": '{"costItemId":90001,"costCount":10,"dailyLimit":5,"totalLimit":50}',
                "rewards": '[{"itemId":90001,"count":100,"type":"token","grantTiming":"immediate"}]',
                "rewardMethod": "milestone",
                "pointsTokens": '[{"tokenId":90001,"name":"夏日代币","maxStack":9999}]',
                "shopId": 7001,
                "shopProducts": '[{"productId":1,"itemId":90002,"price":200,"currencyId":90001,"dailyLimit":1}]',
                "uiResources": '{"bannerImage":"ui/activity/summer_banner.png","themeColor":"#FF8C42"}',
                "displayText": '{"title":"夏日庆典","bannerText":"限时UP"}',
                "extra": '{"bannerText":"限时UP"}',
            },
        ], columns=DETAIL_COLUMNS).to_excel(writer, sheet_name=SHEET_DETAIL, index=False)

        pd.DataFrame([
            {
                "activityId": 5000701,
                "name": "夏日庆典",
                "activityType": "seasonal",
                "moduleId": 50007,
                "beginTime": "2026-07-01 00:00:00",
                "endTime": "2026-08-01 00:00:00",
                "unlockLevel": 20,
                "conditions": '[{"type":"level","intValue":20}]',
                "description": "参与夏日庆典，收集代币兑换限定奖励。",
                "gameplay": "完成每日任务与关卡挑战，获取夏日代币并在活动商店兑换道具。",
                "rules": "每日参与次数有限；代币活动结束清零。",
                "stages": '[{"stageId":1,"name":"开幕阶段","unlockScore":0}]',
                "costAndLimits": '{"costItemId":90001,"costCount":10,"dailyLimit":5}',
                "rewards": '[{"itemId":90001,"count":100,"type":"token"}]',
                "rewardMethod": "milestone",
                "pointsTokens": '[{"tokenId":90001,"name":"夏日代币"}]',
                "shopId": 7001,
                "shopProducts": '[{"productId":1,"itemId":90002,"price":200,"currencyId":90001}]',
                "uiResources": '{"bannerImage":"ui/activity/summer_banner.png"}',
                "displayText": '{"title":"夏日庆典","bannerText":"限时UP"}',
                "banners": '[{"id":9001,"gachaType":"AvatarUp","rateUpItems5":[1102],"rateUpItems4":[1105]}]',
                "items": '[{"id":90001,"name":"夏日代币","stack":999}]',
                "extra": '{"source":"unified"}',
            },
        ], columns=UNIFIED_COLUMNS).to_excel(writer, sheet_name=SHEET_UNIFIED, index=False)
    print(f"模板已生成: {output}")


def trigger_reload(url: str, cookie: str) -> None:
    if not url:
        return
    req = request.Request(url, method="POST", data=b"")
    req.add_header("Content-Type", "application/json")
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            print(f"热重载成功: status={resp.status}, body={body}")
    except error.HTTPError as exc:
        print(f"热重载失败: status={exc.code}, body={exc.read().decode('utf-8', errors='replace')}", file=sys.stderr)
        raise SystemExit(1) from exc


def import_excel(excel_path: Path) -> None:
    ensure_dirs()
    summary: dict[str, Any] = {}

    schedule_df = read_sheet(excel_path, SHEET_SCHEDULE)
    if not schedule_df.empty:
        schedule = export_schedule(schedule_df)
        schedule_path = DATA_DIR / "ActivityScheduling.json"
        schedule = merge_schedule(schedule_path, schedule)
        write_json(schedule_path, schedule)
        summary["schedule"] = len(schedule)

    banner_df = read_sheet(excel_path, SHEET_BANNERS)
    if not banner_df.empty:
        banners = export_banners(banner_df)
        banner_path = DATA_DIR / "Banners.json"
        banners = merge_banners(banner_path, banners)
        write_json(banner_path, banners)
        summary["banners"] = len(banners)

    item_df = read_sheet(excel_path, SHEET_ITEMS)
    if not item_df.empty:
        merge_items_csv(DATA_DIR / "items_config.csv", item_df)
        summary["items"] = len(item_df)

    detail_df = read_sheet(excel_path, SHEET_DETAIL)
    if not detail_df.empty:
        details = export_activity_details(detail_df)
        summary["activity_details"] = len(details)

    unified_df = read_sheet(excel_path, SHEET_UNIFIED)
    if not unified_df.empty:
        unified = export_unified(unified_df)
        unified_path = DATA_DIR / "ActivityConfigs.json"
        unified = merge_unified(unified_path, unified)
        write_json(unified_path, unified)
        summary["unified"] = len(unified)

    print("导入完成:", json.dumps(summary, ensure_ascii=False))


def main() -> None:
    args = parse_args()
    if args.generate_template:
        generate_template(Path(args.template_output))
        return

    excel = args.excel_opt or args.excel
    if not excel:
        print("请指定 Excel 路径，或使用 --generate-template 生成模板", file=sys.stderr)
        raise SystemExit(2)

    import_excel(Path(excel))
    trigger_reload(args.reload_url, args.session_cookie)


if __name__ == "__main__":
    main()
