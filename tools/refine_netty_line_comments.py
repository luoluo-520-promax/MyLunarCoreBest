# -*- coding: utf-8 -*-
"""将 Netty 服务中的占位行尾注释（执行语句、续行参数等）替换为具体语义，无法推断则删除。"""
import os
import re
import sys
from typing import List, Optional, Tuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 从 annotate 脚本复用规则
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from annotate_netty_line_comments import LINE_RULES, CONSTRUCTOR_FIELD  # noqa: E402

BAD_HINTS = frozenset({
    "执行语句",
    "多行调用的续行参数",
    "多行构造/调用的续行参数",
    "多行调用的闭合参数",
    "声明类成员依赖或常量",
    "完成方法调用",
    "创建 Protobuf Builder",
    "条件校验",
    "循环处理",
    "返回协议响应",
    "协议处理方法入口",
    "内部辅助方法",
    "构造器注入仓储依赖",
    "构造器注入管理器依赖",
    "创建运行时或 Protobuf 对象",
    "不满足前置条件时走备用分支",
})

GENERIC_PREFIXES = (
    "注入 ",
    "设置 Protobuf 字段 ",
    "设置错误返回码 ",
    "设置成功返回码 0",
)

# 无信息量的机械注释，直接删除
STRIP_EXACT = frozenset({
    "构建不可变 Protobuf 消息",
    "批量写入 Protobuf 重复字段",
    "追加元素到 Protobuf 列表",
    "成功 retcode=0",
})

# 追加更具体的匹配规则（优先于 annotate 中的泛化规则）
EXTRA_RULES: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"private static final Logger log ="), "本类 SLF4J 日志"),
    (re.compile(r"AttributeKey\.valueOf\(\"playerUid\"\)"), "Channel 属性键：登录 uid"),
    (re.compile(r"private final ChallengeManager"), "挑战运行时索引 challengeUid→状态"),
    (re.compile(r"private final BattleMonsterWaveRepository"), "关卡怪物波次配置仓储"),
    (re.compile(r"private final ChallengeHistoryRepository"), "挑战历史最佳成绩仓储"),
    (re.compile(r"private final ChallengeGroupRewardRepository"), "挑战组星级领奖掩码仓储"),
    (re.compile(r"int challengeType = \(int\) req\.getChallengeType"), "读取挑战类型"),
    (re.compile(r"int challengeId = \(int\) req\.getChallengeId"), "读取挑战关卡 ID"),
    (re.compile(r"int lineupId = \(int\) req\.getLineupId"), "读取出战阵容 ID"),
    (re.compile(r"int groupId = deriveGroupId"), "由 challengeId 推导挑战组 ID"),
    (re.compile(r"int stageId = deriveStageId"), "映射为加载波次用的 stageId"),
    (re.compile(r"waves = Collections\.emptyList"), "加载失败时用空列表避免中断流程"),
    (re.compile(r"buildEnemyInfoFromWaves\(waves\)"), "波次配置转为协议 EnemyInfo 列表"),
    (re.compile(r"challengeManager\.nextUid\(\)"), "分配全局唯一 challengeUid"),
    (re.compile(r"new ChallengeRuntime\("), "创建本局挑战内存状态"),
    (re.compile(r"challengeManager\.put\(runtime\)"), "注册运行时供查询与结算"),
    (re.compile(r"int roundsLeft = challengeType == 1"), "类型 1 演示固定剩余回合数"),
    (re.compile(r"long uid = req\.getChallengeUid"), "请求中的挑战实例 UID"),
    (re.compile(r"challengeManager\.get\(uid\)"), "按 UID 查找进行中的挑战"),
    (re.compile(r"boolean win = req\.getIsWin"), "客户端上报是否通关"),
    (re.compile(r"finalScore = \(int\) req\.getFinalScore"), "本局最终得分"),
    (re.compile(r"finalStars = \(int\) req\.getFinalStars"), "本局星级位掩码"),
    (re.compile(r"roundsUsed = \(int\) req\.getRoundsUsed"), "本局已消耗回合数"),
    (re.compile(r"runtime\.markSettled"), "写入结算结果到运行时"),
    (re.compile(r"challengeManager\.remove\(uid\)"), "结算后从内存移除防重复上报"),
    (re.compile(r"isNewRecord = false"), "默认非新纪录，胜利后再比较"),
    (re.compile(r"bestStars = finalStars"), "初始最佳星取本局结果"),
    (re.compile(r"bestScore = Math\.max"), "初始最佳分取本局非负值"),
    (re.compile(r"findByPlayerAndChallenge"), "查库中该关卡历史最佳"),
    (re.compile(r"oldStars = old\.get\(\)\.getStars"), "历史星级位掩码"),
    (re.compile(r"oldScore = old\.get\(\)\.getScore"), "历史最高得分"),
    (re.compile(r"mergedStars = oldStars \| finalStars"), "星级位掩码按位或合并"),
    (re.compile(r"mergedScore = Math\.max"), "得分取历史与本局较大值"),
    (re.compile(r"isNewRecord = mergedStars"), "星或分有提升则视为新纪录"),
    (re.compile(r"isNewRecord = true"), "无历史记录则本局即新纪录"),
    (re.compile(r"upsertBestResult"), "持久化合并后的最佳成绩"),
    (re.compile(r"starCount = \(int\) req\.getStarCount"), "要领奖的星级档位"),
    (re.compile(r"groupId = \(int\) req\.getGroupId"), "挑战组 ID"),
    (re.compile(r"if \(groupId <= 0 \|\| starCount"), "组 ID 或星级档位参数非法"),
    (re.compile(r"computeAvailableStars"), "统计组内可用总星数"),
    (re.compile(r"if \(availableStars < starCount\)"), "可用星数不足无法领取"),
    (re.compile(r"claimMask = \(1 << starCount\) - 1"), "生成待领取档位的位掩码"),
    (re.compile(r"\.map\(ChallengeGroupRewardEntity::getTakenStars\)"), "读取已领取星级位图"),
    (re.compile(r"\.orElse\(0\)"), "无记录则掩码为 0"),
    (re.compile(r"newMask = takenMask \| claimMask"), "合并本次领取位"),
    (re.compile(r"if \(newMask == takenMask\)"), "掩码未变说明该档已领过"),
    (re.compile(r"updateTakenStars"), "写回领取掩码到数据库"),
    (re.compile(r"page = req\.getPage\(\)"), "分页页码"),
    (re.compile(r"pageSize = req\.getPageSize\(\)"), "每页条数"),
    (re.compile(r"pageSize = Math\.min\(100"), "限制单页最多 100 条"),
    (re.compile(r"offset = \(page - 1\)"), "计算分页偏移量"),
    (re.compile(r"countHistory"), "统计历史总条数"),
    (re.compile(r"listHistory"), "分页查询历史列表"),
    (re.compile(r"ChallengeHistoryInfo> infos = new"), "组装协议历史列表"),
    (re.compile(r"for \(ChallengeHistoryEntity h : list\)"), "逐条转换历史实体"),
    (re.compile(r"completeSeconds = h\.getUpdatedAt"), "完成时间转 Unix 秒"),
    (re.compile(r"infos\.add\("), "追加单条历史到响应列表"),
    (re.compile(r"listAllInGroup"), "查询组内全部历史算星"),
    (re.compile(r"Integer\.bitCount\(h\.getStars"), "单条记录达成星数累加"),
    (re.compile(r"return \(challengeId / 1000\)"), "演示规则：千位取整为 groupId"),
    (re.compile(r"return 1000 \+ challengeId"), "演示规则：stageId=1000+challengeId"),
    (re.compile(r"for \(int i = 0; i < waves\.size"), "按波次序号生成 EnemyInfo"),
    (re.compile(r"\.setWaveIndex\(i \+ 1\)"), "协议波次从 1 开始编号"),
    (re.compile(r"addAllRewards\(Collections\.emptyList"), "演示不发具体道具奖励"),
    (re.compile(r"if \(old\.isPresent\(\)\)"), "存在历史记录则合并比较"),
    (re.compile(r"if \(groupId <= 0\)"), "组 ID 必须为正"),
    (re.compile(r"private Integer getPlayerId"), "从 Channel 取 playerId，未登录返回 null"),
    (re.compile(r"private int computeAvailableStars"), "统计组内历史星级总和"),
    (re.compile(r"private static int deriveGroupId"), "challengeId→groupId 演示映射"),
    (re.compile(r"private static int deriveStageId"), "challengeId→stageId 演示映射"),
    (re.compile(r"buildEnemyInfoFromWaves"), "波次表转 EnemyInfo（怪物列表演示为空）"),
]

PARAM_HINTS = {
    "challengeUid": "挑战实例 UID",
    "playerId": "玩家 ID",
    "challengeType": "挑战类型",
    "challengeId": "挑战关卡 ID",
    "groupId": "挑战组 ID",
    "stageId": "波次 stageId",
    "nowSeconds": "开战时间戳（秒）",
    "enemyInfo": "各波敌方信息列表",
    "channel": "客户端 Netty 连接",
    "req": "客户端请求 Protobuf",
    "runtime": "挑战内存运行时",
    "waves": "怪物波次配置",
    "historyRepository": "挑战历史仓储",
    "groupRewardRepository": "组奖励仓储",
    "challengeManager": "挑战运行时管理器",
    "waveRepository": "怪物波次仓储",
}

ALL_RULES = EXTRA_RULES + LINE_RULES


def is_bad_hint(hint: str) -> bool:
    if hint in BAD_HINTS:
        return True
    if hint.startswith(GENERIC_PREFIXES):
        return True
    if hint.startswith("注入 ") and hint.endswith(" 依赖"):
        return True
    return False


def hint_for_code(code: str) -> Optional[str]:
    stripped = code.rstrip()
    if not stripped.strip():
        return None
    m = CONSTRUCTOR_FIELD.search(stripped)
    if m:
        name = m.group(1)
        return f"构造器注入 {name}"
    for pat, hint in ALL_RULES:
        if hint is None:
            continue
        if pat.search(stripped):
            if is_bad_hint(hint):
                continue
            return hint
    s = stripped.strip()
    # 单行仅参数名
    pm = re.match(r"^(\w+),?\s*$", s)
    if pm and pm.group(1) in PARAM_HINTS:
        return PARAM_HINTS[pm.group(1)]
    pm = re.match(r"^Math\.max\(0, waves\.size\(\)\),?\s*$", s)
    if pm:
        return "总波次数（无配置则为 0）"
    if re.search(r"\.setRetcode\(\d+\)\.build\(\)", s):
        m = re.search(r"setRetcode\((\d+)\)", s)
        if m and m.group(1) != "0":
            return None  # 保留 fix_netty_retcode 脚本写入的具体含义
    if ".setRetcode(0)" in s:
        return "成功 retcode=0"
    if s.startswith("return ChallengeSystemProto.") or s.startswith("return "):
        proto = re.search(r"return (\w+Proto)\.", s)
        if proto:
            return f"组装并返回 {proto.group(1)} 响应"
    if ".newBuilder()" in s and "return " in s:
        return None
    if s.endswith(".build()") or s.endswith(".build());"):
        return None
    if s.endswith(".newBuilder()"):
        return None
    return None


def should_keep_comment(code: str, comment: str) -> bool:
    if is_bad_hint(comment):
        return False
    if comment.startswith("设置 Protobuf 字段"):
        return False
    if comment in ("构建不可变 Protobuf 消息", "批量写入 Protobuf 重复字段"):
        return False
    if "retcode" in comment.lower() or "未登录" in comment or "非法" in comment:
        return True
    if "失败" in comment or "不存在" in comment or "禁止" in comment:
        return True
    if comment.startswith("处理") or comment.startswith("从 ") or comment.startswith("记录"):
        return True
    if comment.startswith("组装") or comment.startswith("读取") or comment.startswith("写入"):
        return True
    if comment.startswith("校验") or comment.startswith("查询") or comment.startswith("发布"):
        return True
    if len(comment) >= 8 and "续行" not in comment and "执行语句" not in comment:
        return True
    return False


def refine_line(line: str) -> str:
    if line.strip().startswith("//") or line.strip().startswith("*"):
        return line
    if "//" not in line:
        return line
    code, sep, comment = line.partition("//")
    comment = comment.strip()
    code_stripped = code.rstrip()
    if not code_stripped.strip():
        return line
    if comment in STRIP_EXACT:
        return code_stripped + "\n"
    if comment.startswith("设置 Protobuf 字段 "):
        return code_stripped + "\n"
    if should_keep_comment(code_stripped, comment):
        return line
    hint = hint_for_code(code_stripped)
    if hint and not is_bad_hint(hint):
        return code_stripped + f" // {hint}\n"
    return code_stripped + "\n"


def fix_method_brace_format(content: str) -> str:
    """修复 `) {        Long` 这类缺失换行的格式问题。"""
    return re.sub(
        r"(\))\s*\{\s{4,}(\w)",
        r"\1 {\n        \2",
        content,
    )


def refine_file(path: str) -> bool:
    with open(path, encoding="utf-8") as f:
        content = f.read()
    lines = content.splitlines(keepends=True)
    out = [refine_line(ln) for ln in lines]
    new_content = fix_method_brace_format("".join(out))
    if new_content != content:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(new_content)
        return True
    return False


def main():
    targets = sys.argv[1:] if len(sys.argv) > 1 else [
        "src/main/java/cn/itcast/demo/mylunarcore/challenge/ChallengeNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/scene/SceneNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/rogue/RogueNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/item/ItemNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/gacha/GachaNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/battle/BattleNettyService.java",
    ]
    updated = []
    for rel in targets:
        p = os.path.join(ROOT, rel)
        if os.path.isfile(p) and refine_file(p):
            updated.append(rel)
    print(f"Refined {len(updated)} files")
    for p in updated:
        print(f"  {p}")


if __name__ == "__main__":
    main()
