# -*- coding: utf-8 -*-
"""用词典 + GBK 逆向修复 assist 包中损坏的中文字符串，并尽量修复断开的引号。"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path("src/main/java/cn/itcast/demo/mylunarcore/assist")

# 直接替换（含 PUA / 断引号的完整片段）
EXACT = {
    # --- synonyms ---
    'Map.entry("涓荤嚎", "浠诲姟")': 'Map.entry("主线", "任务")',
    'Map.entry("鏀嚎", "浠诲姟")': 'Map.entry("支线", "任务")',
    'Map.entry("鍗℃睜", "鎶藉崱")': 'Map.entry("卡池", "抽卡")',
    'Map.entry("鎵泲", "鎶藉崱")': 'Map.entry("扭蛋", "抽卡")',
    'Map.entry("淇濆簳", "鎶藉崱")': 'Map.entry("保底", "抽卡")',
    'Map.entry("鍟嗗簵", "shop")': 'Map.entry("商店", "shop")',
    'Map.entry("鍟嗗煄", "shop")': 'Map.entry("商城", "shop")',
    'Map.entry("鐑棬", "浣跨敤鐜?),': 'Map.entry("热门", "使用率"),',
    'Map.entry("鍑哄満", "浣跨敤鐜?)': 'Map.entry("出场", "使用率")',
    # broken with PUA variants
    'Map.entry("鏀\ue21c嚎", "浠诲姟")': 'Map.entry("支线", "任务")',
    'Map.entry("鎵\ue161泲", "鎶藉崱")': 'Map.entry("扭蛋", "抽卡")',
    'Map.entry("鐑\ue162棬", "浣跨敤鐜?),': 'Map.entry("热门", "使用率"),',
    'Map.entry("鍑哄満", "浣跨敤鐜?)': 'Map.entry("出场", "使用率")',
}

# 词级替换（在整文件上做）
WORDS = {
    "涓荤嚎": "主线",
    "浠诲姟": "任务",
    "鍗℃睜": "卡池",
    "鎶藉崱": "抽卡",
    "淇濆簳": "保底",
    "鍟嗗簵": "商店",
    "鍟嗗煄": "商城",
    "鍑哄満": "出场",
    "鍏绘垚": "养成",
    "绐佺牬": "突破",
    "澶╄祴": "天赋",
    "鍔犵偣": "加点",
    "娲诲姩": "活动",
    "鍘嗗彶": "历史",
    "鍔垮姏": "势力",
    "瀵艰埅": "导航",
    "鑳屾櫙": "背景",
    "閭欢": "邮件",
    "閭": "邮箱",
    "鐑棬": "热门",
    "璺嚎": "路线",
    "璺緞": "路径",
    "浣跨敤鐜": "使用率",
    "涓栫晫瑙": "世界观",
    "鐧剧": "百科",
    "鎬庝箞璧": "怎么走",
    "鎬庝箞鍋": "怎么做",
    "涓嬩竴姝": "下一步",
    "甯姪鎴": "帮帮我",
    "鍘诲摢": "去哪",
    "鏍囪": "标记",
    "娌块€": "捷径",
    "鏄皝": "是谁",
    "浠€涔堝娍鍔": "什么势力",
    "璁茶": "说说",
    "浠嬬粛涓€涓": "介绍一下",
    "鏃呬汉鍚戝": "旅人向导",
    "鐜╁": "玩家",
    "闂": "问题",
    "鐧藉悕鍗": "白名单",
    "閰嶇疆鐭ヨ瘑": "配置知识",
    "鐩爣": "目标",
    "鍔╂墜": "助手",
    "鏆傛椂涓嶅彲鐢": "暂时不可用",
    "璇风◢鍚庡啀璇": "请稍后再试",
    "鏍规嵁褰撳墠杩涘害锛屽缓璁紭鍏": "根据当前进度，建议优先",
    "鐩稿叧閰嶇疆": "相关配置",
    "鐩戞祴": "监测",
    "鏆傛湭鍖归厤鍒板叿浣撳缓璁": "暂未匹配到具体建议",
}


def try_gbk_chunk(s: str) -> str:
    out = []
    i = 0
    chars = list(s)
    while i < len(chars):
        # skip PUA
        if 0xE000 <= ord(chars[i]) <= 0xF8FF or chars[i] == "\ufffd":
            i += 1
            continue
        # try longest recoverable run
        matched = None
        for j in range(len(chars), i, -1):
            chunk = "".join(ch for ch in chars[i:j] if not (0xE000 <= ord(ch) <= 0xF8FF) and ch != "\ufffd")
            if len(chunk) < 2:
                continue
            try:
                recovered = chunk.encode("gbk").decode("utf-8")
                matched = (j, recovered)
                break
            except Exception:
                continue
        if matched:
            j, recovered = matched
            out.append(recovered)
            i = j
        else:
            out.append(chars[i])
            i += 1
    return "".join(out)


def fix_broken_quotes(text: str) -> str:
    """修复因乱码导致的明显断引号模式，如 "xxx?) / "xxx?; / "xxx?,"""
    text = text.replace('?)', '")')
    text = text.replace('?;', '";')
    text = text.replace('?,', '",')
    text = text.replace('? +', '" +')
    text = text.replace('? :', '" :')
    text = text.replace("锛?", "。")
    text = text.replace("鈥?", "…")
    text = text.replace("鈥?", "—")
    return text


def fix_text(text: str) -> str:
    for bad, good in EXACT.items():
        text = text.replace(bad, good)
    # longer phrases first
    for bad, good in sorted(WORDS.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(bad, good)
    text = fix_broken_quotes(text)

    # recover remaining comment tails / strings via chunk gbk
    def recover_line(line: str) -> str:
        if not any(ord(c) > 127 for c in line):
            return line
        # already mostly good chinese?
        if sum(1 for c in line if "\u4e00" <= c <= "\u9fff") > 5 and not any(
            0xE000 <= ord(c) <= 0xF8FF for c in line
        ):
            # still may contain mojibake syllables
            if not any(x in line for x in ("鎶", "鍟", "浠", "鍔", "璇", "缂", "杩", "鍙", "鐩", "鏌", "淇", "鏈", "鍚")):
                return line
        recovered = try_gbk_chunk(line)
        return recovered

    lines = [recover_line(ln) for ln in text.splitlines()]
    text = "\n".join(lines) + "\n"
    text = fix_broken_quotes(text)
    return text


# 关键方法整块替换（保证语法正确）
BLOCK_FIXES: dict[str, list[tuple[str, str]]] = {
    "AiAssistApplicationService.java": [
        (
            'AssistAnswer err = AssistAnswer.of(5, "助手暂时不可用，请稍后再试。", "error", List.of(), List.of());',
            None,  # placeholder - applied after word fix
        )
    ]
}


def repair_ai_assist_application_service(text: str) -> str:
    # error message line - match broadly
    text = re.sub(
        r'AssistAnswer\.of\(5,\s*"[^"]*",\s*"error"',
        'AssistAnswer.of(5, "助手暂时不可用，请稍后再试。", "error"',
        text,
    )
    text = re.sub(
        r'return lower\.contains\("[^"]*"\) \|\| lower\.contains\("[^"]*"\) \|\| lower\.contains\("mail"\);',
        'return lower.contains("邮件") || lower.contains("邮箱") || lower.contains("mail");',
        text,
    )
    # resolveScene body - replace whole method for safety
    resolve = '''
    static String resolveScene(String scene, String question) {
        if (scene != null && !scene.isBlank()) {
            return scene.trim().toLowerCase(Locale.ROOT); // 客户端显式传入 scene 时优先采用并归一小写
        }
        String q = question.toLowerCase(Locale.ROOT); // 无 scene 时用问题关键词推断场景
        if (q.contains("抽卡") || q.contains("卡池") || q.contains("保底") || q.contains("gacha")) {
            return "gacha"; // 抽卡相关问题
        }
        if (q.contains("任务") || q.contains("主线") || q.contains("quest")) {
            return "quest"; // 任务相关问题
        }
        if (q.contains("活动") || q.contains("activity")) {
            return "activity"; // 活动相关问题
        }
        if (q.contains("养成") || q.contains("突破") || q.contains("天赋") || q.contains("加点")) {
            return "growth"; // 养成相关问题
        }
        if (q.contains("商店") || q.contains("买") || q.contains("shop")) {
            return "shop"; // 商店相关问题
        }
        if (q.contains("邮件") || q.contains("mail")) {
            return "mail"; // 邮件相关问题
        }
        if (q.contains("使用率") || q.contains("热门") || q.contains("出场") || q.contains("popular") || q.contains("meta")) {
            return "meta"; // 全服热门/使用率问题
        }
        if (q.contains("历史") || q.contains("势力") || q.contains("世界观") || q.contains("百科") || q.contains("背景")) {
            return "lore"; // 世界观百科问题
        }
        if (q.contains("路线") || q.contains("导航") || q.contains("怎么走") || q.contains("路径")) {
            return "explore"; // 探索导航问题
        }
        return "general"; // 无法归类时走通用场景
    }
'''
    text = re.sub(
        r"static String resolveScene\(String scene, String question\) \{.*?\n    \}",
        resolve.strip() + "\n",
        text,
        count=1,
        flags=re.S,
    )

    fallback = '''
    private static String buildRuleFallbackAnswer(String question,
                                                  List<CoachHint> related,
                                                  List<RagKnowledgeService.KnowledgeChunk> knowledge,
                                                  Map<String, Object> ctx) {
        StringBuilder sb = new StringBuilder(); // 拼接规则降级答复
        Object mailSummary = ctx == null ? null : ctx.get("mailSummary"); // 邮件摘要（若有）
        if (mailSummary instanceof String ms && !ms.isBlank()) {
            sb.append(ms); // 优先附上未读邮件摘要
        }
        Object monitoring = ctx == null ? null : ctx.get("monitoringSummary"); // 监测摘要
        if (monitoring instanceof String summary && !summary.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' '); // 与前文用空格分隔
            }
            sb.append("监测：").append(summary).append('。'); // 标注监测信息
        }
        if (related != null && !related.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("根据当前进度，建议优先："); // 引导规则教练提示
            for (int i = 0; i < related.size(); i++) {
                CoachHint h = related.get(i); // 当前 hint
                if (i > 0) {
                    sb.append('；'); // 多条 hint 用分号分隔
                }
                sb.append(h.title()).append(" — ").append(h.message()); // 标题 + 文案
            }
        }
        if (knowledge != null && !knowledge.isEmpty()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("相关配置："); // 附带 RAG 命中片段
            for (int i = 0; i < Math.min(3, knowledge.size()); i++) {
                RagKnowledgeService.KnowledgeChunk c = knowledge.get(i); // 取前 3 条防过长
                if (i > 0) {
                    sb.append('；');
                }
                sb.append('[').append(c.id()).append(']').append(truncate(c.text(), 60)); // [id]+截断文本
            }
        }
        if (sb.isEmpty()) {
            return "暂未匹配到具体建议。你可以打开任务或活动界面查看当前目标。（问题：" + question + "）"; // 无任何材料时的兜底句
        }
        return sb.toString(); // 返回拼接完成的规则答复
    }
'''
    text = re.sub(
        r"private static String buildRuleFallbackAnswer\(String question,.*?return sb\.toString\(\);\n    \}",
        fallback.strip() + "\n",
        text,
        count=1,
        flags=re.S,
    )
    text = re.sub(
        r'return s\.length\(\) <= max \? s : s\.substring\(0, max\) \+ "[^"]*";',
        'return s.length() <= max ? s : s.substring(0, max) + "…";',
        text,
    )
    return text


def repair_rag_synonyms(text: str) -> str:
    syn = '''
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("主线", "任务"), // 主线口语归一到任务知识块
            Map.entry("支线", "任务"), // 支线口语归一到任务知识块
            Map.entry("卡池", "抽卡"), // 卡池口语归一到抽卡知识块
            Map.entry("扭蛋", "抽卡"), // 扭蛋口语归一到抽卡知识块
            Map.entry("保底", "抽卡"), // 保底口语归一到抽卡知识块
            Map.entry("商店", "shop"), // 商店口语对齐英文 type=shop
            Map.entry("商城", "shop"), // 商城口语对齐英文 type=shop
            Map.entry("热门", "使用率"), // 热门口语归一到使用率统计块
            Map.entry("出场", "使用率") // 出场口语归一到使用率统计块
    );
'''
    text = re.sub(
        r"private static final Map<String, String> SYNONYMS = Map\.ofEntries\([\s\S]*?\);",
        syn.strip(),
        text,
        count=1,
    )
    # fix broken javadoc before fields if sanitize ate closings
    text = text.replace(
        """    /**
    private final ActivityConfigService activityConfigService;
    /** 浠诲姟閰嶇疆浠撳簱銆?*/
    private final QuestConfigRepository questConfigRepository;
    /** 鎶藉崱閰嶇疆鏈嶅姟銆?*/
    private final GachaConfigService gachaConfigService;
    /** 鍟嗗簵閰嶇疆浠撳簱銆?*/
    private final ShopConfigRepository shopConfigRepository;
    /**
    private final AvatarUsageStatsService avatarUsageStatsService;""",
        """    /** 活动配置服务：重建时抽取活动知识块 */
    private final ActivityConfigService activityConfigService;
    /** 任务配置仓库：重建时抽取任务知识块 */
    private final QuestConfigRepository questConfigRepository;
    /** 抽卡配置服务：重建时抽取卡池知识块 */
    private final GachaConfigService gachaConfigService;
    /** 商店配置仓库：重建时抽取商店知识块 */
    private final ShopConfigRepository shopConfigRepository;
    /** 角色使用率统计：重建时抽取热门/出场知识块 */
    private final AvatarUsageStatsService avatarUsageStatsService;""",
    )
    return text


def main() -> None:
    for path in sorted(ROOT.rglob("*.java")):
        text = path.read_text(encoding="utf-8-sig")
        fixed = fix_text(text)
        if path.name == "AiAssistApplicationService.java":
            fixed = repair_ai_assist_application_service(fixed)
        if path.name == "RagKnowledgeService.java":
            fixed = repair_rag_synonyms(fixed)
        if fixed != text:
            path.write_bytes(fixed.encode("utf-8"))
            print("fixed", path.relative_to(ROOT).as_posix())


if __name__ == "__main__":
    main()
