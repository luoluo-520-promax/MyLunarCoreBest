# -*- coding: utf-8 -*-
"""清理残缺 javadoc，并修复仍损坏的用户可见中文字符串。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")

STRING_FIXES = [
    # (regex or exact-ish pattern, replacement) — 尽量用明确完整字面量
    (
        r'AssistAnswer\.of\(4,\s*"[^"]*",',
        'AssistAnswer.of(4, "助手响应超时，已降级到基础建议。请稍后再试或打开任务/活动界面。",',
    ),
    (
        r'return\s+"暂时无法给出[^"]*";',
        'return "暂时无法给出建议，请打开对应界面查看官方说明。";',
    ),
    (
        r'"暂无匹配[^"]*";',
        '"暂无匹配的导航路线，请打开任务界面查看目标区域。");',
    ),
    (
        r'return\s+"全服角色使用率样本[^"]*";',
        'return "全服角色使用率样本不足";',
    ),
    (
        r'"当前没有[^"]*任务[^"]*",\s*"quest-guide"\)',
        '"当前没有进行中的任务。可打开任务界面接取或查看已完成进度。", "quest-guide")',
    ),
    (
        r'return\s+"暂无推荐[^"]*";',
        'return "暂无推荐。";',
    ),
    (
        r'"旅人向导"',
        '"旅人向导"',
    ),
]

EXACT_LINE_REPLACEMENTS = {
}


def clean_javadoc(lines: list[str]) -> list[str]:
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        # 删除占位注释
        if "（注释编码已修复占位）" in line:
            i += 1
            continue
        # 空/残缺 javadoc：/** 后紧跟代码声明
        if stripped == "/**":
            # 收集到 */ 或代码
            j = i + 1
            body = []
            closed = False
            while j < len(lines):
                t = lines[j].strip()
                if t.startswith("*/"):
                    closed = True
                    break
                if t.startswith("private ") or t.startswith("public ") or t.startswith("protected ") or t.startswith("@") or t.startswith("static "):
                    break
                if t.startswith("*") or t == "":
                    body.append(lines[j])
                    j += 1
                    continue
                break
            if closed:
                # 若 body 几乎为空或只含空白星号，删除整个 javadoc
                meaningful = [b for b in body if b.strip() not in ("", "*") and not re.match(r"^\*\s*$", b.strip())]
                # 仍含明显乱码则删除
                joined = "\n".join(body)
                if not meaningful or any(ord(c) > 0x9fff for c in joined for c in joined) or "銆" in joined or "婧" in joined:
                    i = j + 1
                    continue
                # 保留正常 javadoc
                out.extend(lines[i : j + 1])
                i = j + 1
                continue
            else:
                # 未闭合：丢弃 /**，保留后续代码
                i += 1
                continue
        # 单行损坏 javadoc /** xxx */
        if stripped.startswith("/**") and stripped.endswith("*/"):
            if "銆" in stripped or "婧" in stripped or any(0xE000 <= ord(c) <= 0xF8FF for c in stripped):
                i += 1
                continue
        out.append(line)
        i += 1
    return out


def fix_known_strings(text: str) -> str:
    # 明确业务文案修复
    replacements = [
        (
            'AssistAnswer.of(4, "助手响应超时，已降级到基纭€寤鸿銆傝稍后再试或打开任务/活动界面銆",',
            'AssistAnswer.of(4, "助手响应超时，已降级到基础建议。请稍后再试或打开任务/活动界面。",',
        ),
        (
            'return "暂时无法给出寤鸿锛岃打开对应界面查看官方说明銆";',
            'return "暂时无法给出建议，请打开对应界面查看官方说明。";',
        ),
        (
            '"暂无匹配鐨勫鑸矾线，请打开任务界面查看目标区域銆");',
            '"暂无匹配的导航路线，请打开任务界面查看目标区域。");',
        ),
        (
            'return "鍏ㄦ湇瑙掕壊浣跨敤鐜囨牱鏈笉瓒";',
            'return "全服角色使用率样本不足";',
        ),
        (
            'StringBuilder sb = new StringBuilder("鍏ㄦ湇鐑棬瑙掕壊(鎸夊嚭鍦?锛?);',
            'StringBuilder sb = new StringBuilder("全服热门角色(按出场)：");',
        ),
        (
            '"当前没有杩涜涓殑任务。可打开任务界面接取或查看已完成进度銆", "quest-guide")',
            '"当前没有进行中的任务。可打开任务界面接取或查看已完成进度。", "quest-guide")',
        ),
        (
            'return "鏆傛棤鎺ㄨ崘銆";',
            'return "暂无推荐。";',
        ),
        (
            'return base + "銆傛湭鎷ユ湁瑙掕壊涓嶄細鍑虹幇鍦ㄦ帹鑽愪腑銆";',
            'return base + "。未拥有角色不会出现在推荐中。";',
        ),
        (
            'user.append("妫€索到的配缃煡识：\\n");',
            'user.append("检索到的配置知识：\\n");',
        ),
        (
            'sb.append(\'銆").append(persona).append("銆");',
            'sb.append("【").append(persona).append("】");',
        ),
        (
            'String answer = "銆" + persona + "銆戝叧浜庛€" + nullToEmpty(best.name()) + "銆嶏細" + bo',
            'String answer = "【" + persona + "】关于「" + nullToEmpty(best.name()) + "」：" + bo',
        ),
    ]
    for a, b in replacements:
        text = text.replace(a, b)

    # 通用：把明显半损坏的句末 銆 换成 。
    text = text.replace("銆", "。")
    # 修复 Rag 字段区
    text = re.sub(
        r"/\*\*\s*\n\s*private final ActivityConfigService activityConfigService;",
        "/** 活动配置服务：重建时抽取活动知识块 */\n    private final ActivityConfigService activityConfigService;",
        text,
    )
    text = re.sub(
        r"/\*\*\s*\n\s*private final AvatarUsageStatsService",
        "/** 角色使用率统计：重建时抽取热门出场知识块 */\n    private final AvatarUsageStatsService",
        text,
    )
    text = re.sub(
        r"/\*\*\s*\n\s*private final LunarCoreProperties properties;",
        "/** 全局配置：读取 RAG 最低得分等阈值 */\n    private final LunarCoreProperties properties;",
        text,
    )
    text = re.sub(
        r"/\*\*[^\n]*世界观[^\n]*\*/\s*\n\s*private final WorldLoreService",
        "/** 世界观知识来源：存在时纳入 lore 知识块 */\n    private final WorldLoreService",
        text,
    )
    text = re.sub(
        r"/\*\*[^\n]*任务配置仓库[^\n]*\*/",
        "/** 任务配置仓库：重建时抽取任务知识块 */",
        text,
    )
    text = re.sub(
        r"/\*\*[^\n]*抽卡配置服务[^\n]*\*/",
        "/** 抽卡配置服务：重建时抽取卡池知识块 */",
        text,
    )
    text = re.sub(
        r"/\*\*[^\n]*商店配置仓库[^\n]*\*/",
        "/** 商店配置仓库：重建时抽取商店知识块 */",
        text,
    )
    return text


def main() -> None:
    for path in sorted(ROOT.rglob("*.java")):
        raw = path.read_text(encoding="utf-8-sig")
        lines = raw.splitlines()
        cleaned = clean_javadoc(lines)
        text = "\n".join(cleaned) + "\n"
        text = fix_known_strings(text)
        path.write_bytes(text.encode("utf-8"))
        print("cleaned", path.relative_to(ROOT).as_posix())


if __name__ == "__main__":
    main()
