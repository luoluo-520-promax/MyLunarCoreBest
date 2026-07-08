# -*- coding: utf-8 -*-
"""为 Netty 协议服务中的错误返回补充具体 retcode 含义注释。"""
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGETS = [
    "src/main/java/cn/itcast/demo/mylunarcore/scene/SceneNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/rogue/RogueNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/item/ItemNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/gacha/GachaNettyService.java",
    "src/main/java/cn/itcast/demo/mylunarcore/challenge/ChallengeNettyService.java",
]

# (method_name, retcode, comment) — 按方法内出现顺序匹配 setRetcode 行
RULES = {
    "SceneNettyService.java": [
        ("handleEnterScene", 1, "未登录，无法进入场景"),
        ("handleEnterScene", 2, "planeId/floorId 对应场景配置不存在"),
        ("handleGetCurSceneInfo", 1, "未登录"),
        ("handleGetCurSceneInfo", 2, "尚未进入场景或场景未初始化"),
        ("handleInteractNpc", 1, "未登录"),
        ("handleInteractNpc", 2, "玩家不在任何场景中"),
        ("handleInteractNpc", 3, "目标 NPC 实体不存在"),
        ("handlePickupProp", 1, "未登录"),
        ("handlePickupProp", 2, "玩家不在任何场景中"),
        ("handlePickupProp", 3, "目标道具实体不存在"),
        ("handleTriggerSceneEvent", 1, "未登录"),
    ],
    "RogueNettyService.java": [
        ("handleStartRogue", 1, "未登录"),
        ("handleStartRogue", 2, "rogueId 或 difficulty 非法"),
        ("handleGetRogueInfo", 1, "未登录"),
        ("handleGetRogueInfo", 2, "无进行中的 Rogue 局"),
        ("handleMove", 1, "未登录"),
        ("handleMove", 2, "无进行中的 Rogue 局"),
        ("handleMove", 3, "nextRoomId 非法"),
        ("handleSelectBlessing", 1, "未登录"),
        ("handleSelectBlessing", 2, "无进行中的 Rogue 局"),
        ("handleSelectBlessing", 3, "blessingId 非法"),
        ("handleSelectMiracle", 1, "未登录"),
        ("handleSelectMiracle", 2, "无进行中的 Rogue 局"),
        ("handleSelectMiracle", 3, "miracleId 非法"),
        ("handleBattleResult", 1, "未登录"),
        ("handleBattleResult", 2, "无进行中的 Rogue 局"),
        ("handleBattleResult", 3, "请求 rogueId 与当前局不匹配"),
        ("handleEvent", 1, "未登录"),
        ("handleEvent", 2, "无进行中的 Rogue 局"),
        ("handleEvent", 3, "roomId 或 optionId 非法"),
        ("handleQuit", 1, "未登录"),
        ("handleQuit", 2, "无进行中的 Rogue 局"),
        ("handleGetGlobalInfo", 1, "未登录"),
        ("handleGetGlobalInfo", 2, "加载玩家 Rogue 数据失败"),
        ("handleUpgradeTalent", 1, "未登录"),
        ("handleUpgradeTalent", 2, "talentId 非法"),
        ("handleUpgradeTalent", 3, "upgradeToLevel 超出允许范围 1~3"),
        ("handleUpgradeTalent", 4, "天赋记录不存在或无法升级"),
        ("handleUpgradeTalent", 5, "数据库升级天赋失败"),
        ("handleSelectPath", 1, "未登录"),
        ("handleSelectPath", 2, "pathId 非法"),
        ("handleSelectPath", 3, "持久化所选路径失败"),
    ],
    "ItemNettyService.java": [
        ("handleEquipItem", 1, "未登录或 playerId 非法"),
        ("handleEquipItem", 2, "道具不存在或已丢弃"),
        ("handleEquipItem", 5, "道具类型不可装备（仅 type=1/2）"),
        ("handleEquipItem", 3, "道具已锁定，禁止装备"),
        ("handleUnequipItem", 1, "未登录或 playerId 非法"),
        ("handleUnequipItem", 2, "道具不存在或已丢弃"),
        ("handleUnequipItem", 3, "道具已锁定，禁止卸下"),
        ("handleEnhanceItem", 1, "未登录或 playerId 非法"),
        ("handleEnhanceItem", 5, "目标 uid 或材料列表参数非法"),
        ("handleEnhanceItem", 2, "目标道具不存在或已丢弃"),
        ("handleEnhanceItem", 3, "目标道具已锁定，禁止强化"),
        ("handlePromoteItem", 1, "未登录或 playerId 非法"),
        ("handlePromoteItem", 2, "道具不存在或已丢弃"),
        ("handlePromoteItem", 3, "道具已锁定，禁止突破"),
        ("handleRankUpItem", 1, "未登录或 playerId 非法"),
        ("handleRankUpItem", 2, "基底或材料道具不存在"),
        ("handleRankUpItem", 3, "基底或材料道具已锁定"),
        ("handleLockItem", 1, "未登录或 playerId 非法"),
        ("handleLockItem", 2, "道具不存在，锁定状态未变更"),
        ("handleDiscardItem", 1, "未登录或 playerId 非法"),
        ("handleDiscardItem", 2, "道具不存在或已丢弃"),
        ("handleDiscardItem", 3, "道具已锁定，禁止丢弃"),
    ],
    "GachaNettyService.java": [
        ("handleGetGachaInfo", "RET_SESSION_INVALID", "会话无效或未登录"),
        ("handleDoGacha", "RET_SESSION_INVALID", "会话无效或未登录"),
        ("handleDoGacha", "RET_BAD_REQUEST", "卡池类型或抽卡次数参数非法"),
        ("handleDoGacha", "RET_CONFIG_NOT_FOUND", "卡池配置不存在"),
        ("handleExchangeCeiling", "RET_SESSION_INVALID", "会话无效或未登录"),
        ("handleExchangeCeiling", "RET_BAD_REQUEST", "兑换参数非法"),
        ("handleExchangeCeiling", "RET_CEILING_NOT_REACHED", "保底进度未达成"),
        ("handleExchangeCeiling", "RET_CEILING_ALREADY_CLAIMED", "本期保底已领取"),
        ("handleGetHistory", "RET_SESSION_INVALID", "会话无效或未登录"),
    ],
    "ChallengeNettyService.java": [
        ("handleStartChallenge", 1, "未登录"),
        ("handleStartChallenge", 2, "challengeType/challengeId/lineupId 非法"),
        ("handleGetChallengeInfo", 1, "未登录"),
        ("handleGetChallengeInfo", 2, "挑战不存在或不属于当前玩家"),
        ("handleReportResult", 1, "未登录"),
        ("handleClaimGroupReward", 1, "未登录"),
        ("handleGetHistory", 1, "未登录"),
        ("handleGetHistory", 2, "挑战历史查询失败"),
        ("handleGetGroupRewardState", 1, "未登录"),
        ("handleGetGroupRewardState", 2, "奖励组 ID 非法"),
        ("handleGetGroupRewardState", 3, "奖励组配置不存在"),
    ],
}

METHOD_START = re.compile(r"^\s+public\s+\S+\s+(\w+)\s*\(")
RETCODE = re.compile(r"\.setRetcode\(([^)]+)\)")


def annotate_file(rel_path: str) -> bool:
    rules = RULES.get(os.path.basename(rel_path), [])
    if not rules:
        return False

    path = os.path.join(ROOT, rel_path)
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    out = []
    method = None
    rule_idx = 0
    changed = False

    def advance_to_method(name: str) -> None:
        nonlocal rule_idx
        rule_idx = 0
        while rule_idx < len(rules) and rules[rule_idx][0] != name:
            rule_idx += 1

    for line in lines:
        m = METHOD_START.match(line)
        if m:
            method = m.group(1)
            advance_to_method(method)

        if method and rule_idx < len(rules):
            rule_method, code, comment = rules[rule_idx]
            if rule_method == method and RETCODE.search(line):
                if f"setRetcode({code})" in line:
                    stripped = re.sub(r"\s//.*$", "", line.rstrip())
                    line = stripped + f" // {comment}\n"
                    changed = True
                    rule_idx += 1

        out.append(line)

    if changed:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(out)
    return changed


def main():
    updated = []
    for rel in TARGETS:
        if annotate_file(rel):
            updated.append(rel)
    print(f"Annotated {len(updated)} files")
    for p in updated:
        print(f"  {p}")


if __name__ == "__main__":
    main()
