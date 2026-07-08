# -*- coding: utf-8 -*-
"""为 Netty 协议服务方法体内的可执行语句补充具体含义的行尾注释。"""
import os
import re
import sys
from typing import List, Optional, Tuple

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

METHOD_RE = re.compile(r"^\s+(?:public|protected|private)\s+")
SKIP_LINE = re.compile(
    r"^\s*(?:/\*\*| \*| \*/|//|@|\}|package |import |$|\{)\s*$|^\s*\}\s*$"
)

# 按优先级排列：越靠前越具体
LINE_RULES: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"Long uid = channel\.attr\(UID_KEY\)\.get\(\)"), "从 Channel 属性读取登录 uid"),
    (re.compile(r"Integer currentPlayerId = getCurrentPlayerId\(channel\)"), "解析当前连接绑定的 playerId"),
    (re.compile(r"Integer playerId = getPlayerId\(channel\)"), "解析当前连接绑定的 playerId"),
    (re.compile(r"int playerId = getPlayerId\(channel\)"), "解析当前连接绑定的 playerId"),
    (re.compile(r"if \(uid == null\)"), "uid 为空表示未登录"),
    (re.compile(r"if \(currentPlayerId == null\)"), "playerId 为空表示未登录"),
    (re.compile(r"if \(playerId == null\)"), "playerId 为空表示未登录"),
    (re.compile(r"if \(playerId <= 0\)"), "playerId 非法表示未登录"),
    (re.compile(r"if \(context == null\)"), "内存中找不到对应战局"),
    (re.compile(r"if \(!isBattleOwner"), "校验请求者是否为战局拥有者"),
    (re.compile(r"if \(context\.isEnded\(\)\)"), "战局已结束则拒绝继续操作"),
    (re.compile(r"if \(req\.getBattleStageId\(\) <= 0 \|\| req\.getLineupId\(\) <= 0\)"), "关卡 ID 或阵容 ID 必须大于 0"),
    (re.compile(r"if \(skillId <= 0\)"), "技能 ID 必须大于 0"),
    (re.compile(r"if \(skillRepository\.findById"), "校验技能是否在配置表中存在"),
    (re.compile(r"if \(req\.getTargetIdsCount\(\) == 0\)"), "技能释放必须指定至少一个目标"),
    (re.compile(r"if \(entity == null\)"), "目标实体不在当前战局中"),
    (re.compile(r"if \(delta != 0 && !entity\.isDead\(\)\)"), "仅对存活实体且变化量非零时修改 HP"),
    (re.compile(r"if \(after == 0\)"), "HP 归零则标记死亡"),
    (re.compile(r"if \(entity\.isDead\(\)\)"), "已死亡实体跳过 Buff 叠加"),
    (re.compile(r"if \(oldStacks < maxStack\)"), "未达叠层上限时才继续叠加"),
    (re.compile(r"if \(oldStacks == 0\)"), "首次获得该 Buff 时记录到 addedBuffs"),
    (re.compile(r"if \(action\.tryParseKillTrue\(\)\)"), "配置要求强制击杀"),
    (re.compile(r"if \(!entity\.isDead\(\)\)"), "仅对存活实体执行击杀"),
    (re.compile(r"if \(allDead && context\.getCurrentWave\(\)"), "当前波怪物全灭且仍有后续波次"),
    (re.compile(r"if \(actionType == 1\)"), "actionType=1 表示释放技能"),
    (re.compile(r"if \(rogueId <= 0 \|\| difficulty <= 0\)"), "rogueId 与 difficulty 必须为正"),
    (re.compile(r"if \(rt == null\)"), "无进行中的 Rogue 运行时"),
    (re.compile(r"if \(nextRoomId <= 0\)"), "目标房间 ID 非法"),
    (re.compile(r"if \(blessingId <= 0\)"), "blessingId 非法"),
    (re.compile(r"if \(miracleId <= 0\)"), "miracleId 非法"),
    (re.compile(r"if \(req\.getRogueId\(\) != rt\.getRogueId\(\)\)"), "请求 rogueId 与当前局不一致"),
    (re.compile(r"if \(win\)"), "战斗胜利分支：发放奖励"),
    (re.compile(r"if \(roomId <= 0 \|\| optionId <= 0\)"), "房间或选项 ID 非法"),
    (re.compile(r"if \(row == null\)"), "场景配置行不存在"),
    (re.compile(r"if \(ctx == null"), "玩家尚未进入场景或上下文缺失"),
    (re.compile(r"if \(npc == null\)"), "场景内找不到该 NPC 实体"),
    (re.compile(r"if \(prop == null\)"), "场景内找不到该道具实体"),
    (re.compile(r"if \(item == null \|\| item\.isDiscarded\(\)\)"), "道具不存在或已丢弃"),
    (re.compile(r"if \(item\.isLocked\(\)\)"), "道具已锁定，禁止变更"),
    (re.compile(r"if \(item\.getType\(\) != 1 && item\.getType\(\) != 2\)"), "仅光锥/遗器类型可装备"),
    (re.compile(r"if \(updated <= 0\)"), "数据库未更新到任何行"),
    (re.compile(r"if \(challengeType <= 0"), "挑战类型/ID/阵容参数非法"),
    (re.compile(r"if \(runtime == null \|\| runtime\.getPlayerId\(\)"), "挑战不存在或不属于当前玩家"),
    (re.compile(r"if \(talentId <= 0\)"), "talentId 非法"),
    (re.compile(r"if \(upgradeToLevel <= 0 \|\| upgradeToLevel > 3\)"), "天赋升级等级超出 1~3 范围"),
    (re.compile(r"if \(updated == null\)"), "天赋升级未产生有效记录"),
    (re.compile(r"if \(pathId <= 0\)"), "pathId 非法"),
    (re.compile(r"if \(groupsJson == null"), "场景 groupsJson 为空则无需解析"),
    (re.compile(r"if \(e\.getBuffStacks\(\)\.getOrDefault\(buffId, 0\) > 0\)"), "仅输出层数大于 0 的 Buff"),
    (re.compile(r"synchronized \(context\.getLock\(\)\)"), "加战局锁，串行化本回合状态变更"),
    (re.compile(r"try \{"), "尝试执行可能失败的 I/O 或数据库操作"),
    (re.compile(r"\} catch \(Exception e\) \{"), "捕获异常并记录日志后返回错误码"),
    (re.compile(r"long nowSeconds = System\.currentTimeMillis\(\) / 1000L"), "记录开战时间戳（秒）"),
    (re.compile(r"int playerId = \(int\) \(uid\.longValue\(\) & 0xffffffffL\)"), "uid 低 32 位映射为 playerId"),
    (re.compile(r"return \(int\) \(uid\.longValue\(\) & 0xffffffffL\)"), "uid 低 32 位映射为 playerId 并返回"),
    (re.compile(r"battleId = battleRepository\.insertBattle"), "向 battle 表插入新战斗记录"),
    (re.compile(r"waves = waveRepository\.loadWavesByStageId"), "按关卡 ID 加载怪物波次配置"),
    (re.compile(r"BattleContext context = battleSceneFactory\.createBattleScene"), "根据波次配置装配内存战局"),
    (re.compile(r"battleManager\.put\(context\)"), "注册战局到内存，供后续协议查找"),
    (re.compile(r"gameEventPublisher\.publish\(new BattleStartedEvent"), "发布战斗开始领域事件"),
    (re.compile(r"gameEventPublisher\.publish\(new BattleEndedEvent"), "发布战斗结束领域事件"),
    (re.compile(r"battleManager\.remove\(battleId\)"), "从内存移除已结束的战局"),
    (re.compile(r"battleRepository\.updateBattleResult"), "持久化战斗结果到数据库"),
    (re.compile(r"context\.setEnded\(true\)"), "标记战局为已结束"),
    (re.compile(r"context\.incrementTurn\(\)"), "推进回合计数器"),
    (re.compile(r"context\.switchToWave\(nextWave\)"), "切换到下一波怪物"),
    (re.compile(r"channel\.writeAndFlush\(new GamePacket"), "主动推送战斗状态变更通知给客户端"),
    (re.compile(r"String statsJson = BattleStatisticsUtil\.toJson"), "将统计 Protobuf 序列化为 JSON 字符串"),
    (re.compile(r"int endStatus = \(int\) req\.getEndStatus\(\)"), "读取客户端上报的结束状态码"),
    (re.compile(r"int playerExp = 0"), "演示环境暂不结算经验值"),
    (re.compile(r"long battleId = req\.getBattleId\(\)"), "从请求中读取战斗 ID"),
    (re.compile(r"BattleContext context = battleManager\.get\(battleId\)"), "按 battleId 查找内存战局"),
    (re.compile(r"int actionType = \(int\) req\.getActionType\(\)"), "读取行动类型（1=技能）"),
    (re.compile(r"int skillId = \(int\) req\.getSkillId\(\)"), "读取技能 ID"),
    (re.compile(r"List<BattleSystemProto\.ActionResult> results = new ArrayList<>"), "收集各目标的行动结算结果"),
    (re.compile(r"List<BattleContext\.MazeSkillActionRuntime> actions ="), "解析技能对应的行为链配置"),
    (re.compile(r"for \(Integer targetId : req\.getTargetIdsList\(\)\)"), "逐个目标结算技能效果"),
    (re.compile(r"EntityState entity = context\.getEntity\(targetId\)"), "在战局实体表中查找目标"),
    (re.compile(r"int hpChangeTotal = 0"), "累计本目标 HP 变化量"),
    (re.compile(r"List<Integer> addedBuffs = new ArrayList<>"), "记录本目标新增的 Buff ID"),
    (re.compile(r"List<Integer> removedBuffs = new ArrayList<>"), "记录本目标移除的 Buff ID"),
    (re.compile(r"for \(BattleContext\.MazeSkillActionRuntime action : actions\)"), "按顺序执行技能行为链中的每个 action"),
    (re.compile(r"switch \(action\.getActionType\(\)\)"), "按 action_type 分发到不同效果处理器"),
    (re.compile(r"int delta = action\.tryParseHpDelta\(\)"), "从配置 JSON 解析 HP 变化量"),
    (re.compile(r"int before = entity\.getHp\(\)"), "记录修改前的 HP"),
    (re.compile(r"int after = Math\.max\(0, before \+ delta\)"), "计算新 HP，下限为 0"),
    (re.compile(r"entity\.setHp\(after\)"), "写回实体当前 HP"),
    (re.compile(r"entity\.setDead\(true\)"), "标记实体为死亡状态"),
    (re.compile(r"hpChangeTotal \+= \(after - before\)"), "累加实际 HP 变化到结算结果"),
    (re.compile(r"hpChangeTotal \+= -before"), "击杀时 HP 变化为负的全部当前 HP"),
    (re.compile(r"List<Integer> buffIds = action\.tryParseBuffIds\(\)"), "从配置 JSON 解析要添加的 Buff 列表"),
    (re.compile(r"for \(Integer buffId : buffIds\)"), "逐个 Buff 尝试叠加到目标"),
    (re.compile(r"int maxStack = buffRepository\.findMaxStack\(buffId\)"), "查询该 Buff 的最大叠层数"),
    (re.compile(r"int oldStacks = entity\.getBuffStacks\(\)\.getOrDefault"), "读取目标当前 Buff 层数"),
    (re.compile(r"entity\.addBuffStack\(buffId, 1, maxStack\)"), "层数 +1，不超过 maxStack"),
    (re.compile(r"addedBuffs\.add\(buffId\)"), "记录新增 Buff 供下行协议通知客户端"),
    (re.compile(r"entity\.setHp\(0\)"), "强制将 HP 置零"),
    (re.compile(r"break;"), "结束当前 action_type 分支"),
    (re.compile(r"continue;"), "跳过当前循环迭代"),
    (re.compile(r"results\.add\("), "将单目标结算结果加入响应列表"),
    (re.compile(r"BattleSystemProto\.ActionResult\.Builder r ="), "构建单目标行动结果 Protobuf"),
    (re.compile(r"for \(Integer b : addedBuffs\)"), "将新增 Buff 写入 ActionResult"),
    (re.compile(r"for \(Integer b : removedBuffs\)"), "将移除 Buff 写入 ActionResult"),
    (re.compile(r"results\.add\(r\.build\(\)\)"), "完成单目标 ActionResult 并加入列表"),
    (re.compile(r"boolean allDead = context\.isAllMonstersDeadInWave"), "判断当前波次怪物是否已全部死亡"),
    (re.compile(r"int nextWave = context\.getCurrentWave\(\) \+ 1"), "计算下一波次序号"),
    (re.compile(r"BattleSystemProto\.CurrentState snapshot = buildCurrentState"), "将内存战局转换为协议快照"),
    (re.compile(r"List<BattleSystemProto\.EnemyInfo> enemyInfo = new ArrayList<>"), "组装各波敌方信息列表"),
    (re.compile(r"for \(int i = 0; i < context\.getWaves\(\)\.size\(\); i\+\+\)"), "遍历每一波怪物"),
    (re.compile(r"WaveRuntime wave = context\.getWaves\(\)\.get\(i\)"), "取第 i 波运行时数据"),
    (re.compile(r"BattleSystemProto\.EnemyInfo\.Builder waveBuilder ="), "构建单波 EnemyInfo 消息"),
    (re.compile(r"for \(MonsterRuntime monster : wave\.getMonsters\(\)\)"), "遍历该波中的每只怪物"),
    (re.compile(r"waveBuilder\.addMonsters\("), "将怪物信息追加到该波列表"),
    (re.compile(r"enemyInfo\.add\(waveBuilder\.build\(\)\)"), "完成单波 EnemyInfo 并加入总列表"),
    (re.compile(r"BattleSystemProto\.StageInfo stageInfo ="), "构建关卡摘要信息"),
    (re.compile(r"List<EntityState> entities = new ArrayList<>"), "拷贝战局实体集以便排序"),
    (re.compile(r"entities\.sort\(Comparator\.comparingInt"), "按实体 id 排序，保证快照顺序稳定"),
    (re.compile(r"for \(EntityState e : entities\)"), "逐个实体写入快照"),
    (re.compile(r"BattleSystemProto\.EntityState\.Builder eb ="), "构建单实体状态 Protobuf"),
    (re.compile(r"for \(Integer buffId : e\.getBuffStacks\(\)\.keySet\(\)\)"), "遍历实体持有的 Buff"),
    (re.compile(r"eb\.addBuffs\(buffId\)"), "将有效 Buff 写入实体快照"),
    (re.compile(r"b\.addEntities\(eb\.build\(\)\)"), "将实体快照追加到 CurrentState"),
    (re.compile(r"return b\.build\(\)"), "返回完整 CurrentState 快照"),
    (re.compile(r"return null"), "未登录时返回 null"),
    (re.compile(r"return context\.getPlayerId\(\) == currentPlayerId"), "比较战局 playerId 与请求者"),
    (re.compile(r"this\.(\w+) = \1;"), None),  # handled separately
    (re.compile(r"\.setRetcode\(0\)"), "设置成功返回码 0"),
    (re.compile(r"\.setRetcode\(1\)"), "设置错误返回码 1"),
    (re.compile(r"\.setRetcode\(2\)"), "设置错误返回码 2"),
    (re.compile(r"\.setRetcode\(3\)"), "设置错误返回码 3"),
    (re.compile(r"\.setRetcode\(4\)"), "设置错误返回码 4"),
    (re.compile(r"\.setRetcode\(5\)"), "设置错误返回码 5"),
    (re.compile(r"\.setRetcode\(6\)"), "设置错误返回码 6"),
    (re.compile(r"\.setRetcode\(7\)"), "设置错误返回码 7"),
    (re.compile(r"\.setBattleId\("), "回填战斗 ID 供客户端关联"),
    (re.compile(r"\.setBattleId\(battleId\)"), "回填战斗 ID"),
    (re.compile(r"\.setBattleId\(req\.getBattleId\(\)\)"), "回填请求中的战斗 ID"),
    (re.compile(r"\.setStageInfo\("), "写入关卡摘要"),
    (re.compile(r"\.addAllEnemyInfo\("), "写入各波敌方列表"),
    (re.compile(r"\.setStartTime\("), "写入开战时间戳"),
    (re.compile(r"\.addAllActionResults\("), "写入各目标行动结算结果"),
    (re.compile(r"\.setCurrentState\("), "写入战局当前快照"),
    (re.compile(r"\.setUpdateType\(3\)"), "updateType=3 表示波次推进"),
    (re.compile(r"\.setStateSnapshot\("), "写入状态快照到推送通知"),
    (re.compile(r"\.setExtraDataJson\("), "附加扩展 JSON（当前为空对象）"),
    (re.compile(r"\.setPlayerExp\("), "写入经验奖励（演示为 0）"),
    (re.compile(r"\.setTeleportSceneId\(0\)"), "0 表示退出后不切换场景"),
    (re.compile(r"\.setTeleportPos\("), "写入退出后的传送坐标"),
    (re.compile(r"\.setX\(0f\)"), "传送 X 坐标占位 0"),
    (re.compile(r"\.setY\(0f\)"), "传送 Y 坐标占位 0"),
    (re.compile(r"\.setZ\(0f\)"), "传送 Z 坐标占位 0"),
    (re.compile(r"\.setId\("), "写入关卡/实体 ID"),
    (re.compile(r"\.setWaveCount\("), "写入总波次数"),
    (re.compile(r"\.setWaveIndex\("), "写入波次序号（协议从 1 开始）"),
    (re.compile(r"\.setLevel\("), "写入怪物等级"),
    (re.compile(r"\.setHp\("), "写入当前 HP"),
    (re.compile(r"\.setMaxHp\("), "写入最大 HP"),
    (re.compile(r"\.addAllBuffs\(Collections\.emptyList\(\)\)"), "开战初始无 Buff"),
    (re.compile(r"\.setTargetId\("), "写入行动目标实体 ID"),
    (re.compile(r"\.setHpChange\(0\)"), "HP 变化为 0（无效果或目标不存在）"),
    (re.compile(r"\.setMpChange\(0\)"), "MP 变化为 0"),
    (re.compile(r"\.setTurn\("), "写入当前回合数"),
    (re.compile(r"\.setCurrentWave\("), "写入当前波次"),
    (re.compile(r"\.setDead\("), "写入死亡标记"),
    (re.compile(r"\.build\(\)"), "构建不可变 Protobuf 消息"),
    (re.compile(r"\.newBuilder\(\)"), "创建 Protobuf Builder"),
    (re.compile(r"return BattleSystemProto\."), "组装并返回战斗协议响应"),
    (re.compile(r"return RogueSystemProto\."), "组装并返回 Rogue 协议响应"),
    (re.compile(r"return SceneSystemProto\."), "组装并返回场景协议响应"),
    (re.compile(r"return ItemSystemProto\."), "组装并返回道具协议响应"),
    (re.compile(r"return GachaSystemProto\."), "组装并返回抽卡协议响应"),
    (re.compile(r"return ChallengeSystemProto\."), "组装并返回挑战协议响应"),
    (re.compile(r"log\.warn\("), "记录业务异常日志便于排查"),
    (re.compile(r"long battleId;"), "声明 battleId，在 try 块内由数据库赋值"),
    (re.compile(r"List<BattleMonsterWaveRepository\.WaveConfig> waves;"), "声明波次配置列表，在 try 块内加载"),
]

CONSTRUCTOR_FIELD = re.compile(r"this\.(\w+) = (\1);")


def hint_for(line: str) -> Optional[str]:
    stripped = line.rstrip()
    if "//" in stripped:
        return None
    m = CONSTRUCTOR_FIELD.search(stripped)
    if m:
        return f"注入 {m.group(1)} 依赖"
    for pat, hint in LINE_RULES:
        if hint is None:
            continue
        if pat.search(stripped):
            return hint
    s = stripped.strip()
    if not s or s in ("{", "}", "};", "break;", "continue;"):
        return None
    if s.startswith("return "):
        return "返回协议响应"
    if s.startswith("if ("):
        return "条件校验"
    if s.startswith("for ("):
        return "循环处理"
    if s.startswith("case "):
        return "匹配 action_type 分支"
    if s.startswith("default:"):
        return "未实现的 action_type 走默认分支"
    if s.endswith(";"):
        return "执行语句"
    return None


def in_method_body(line: str) -> bool:
    """处理方法体、构造器、字段、方法签名等类内代码行。"""
    if not line.strip():
        return False
    indent = len(line) - len(line.lstrip())
    if indent >= 8:
        return True
    if indent >= 4:
        s = line.strip()
        if CONSTRUCTOR_FIELD.search(line):
            return True
        if s.startswith("private static final Logger") or "AttributeKey.valueOf" in line:
            return True
        if s.startswith("private final ") or s.startswith("private static final "):
            return True
        if re.match(r"(public|private|protected)\s+\S+\s+\w+\s*\(", s):
            return True
        if s.startswith("} else {"):
            return True
    return False


def annotate_file(path: str) -> bool:
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    out = []
    changed = False
    for line in lines:
        if SKIP_LINE.match(line) or not in_method_body(line):
            out.append(line)
            continue
        hint = hint_for(line)
        if hint and "//" not in line.rstrip():
            out.append(line.rstrip() + f" // {hint}\n")
            changed = True
        else:
            out.append(line)

    # 第二遍：补齐方法签名、字段声明、多行参数及 else 分支等遗漏行
    out2 = []
    method_hints = {
        "handleEnterScene": "处理进入场景请求",
        "handleGetCurSceneInfo": "处理查询当前场景信息",
        "handleInteractNpc": "处理 NPC 交互",
        "handlePickupProp": "处理拾取场景道具",
        "handleTriggerSceneEvent": "处理触发场景事件",
        "handleUseHealingSpring": "处理使用治疗泉",
        "handleStartRogue": "处理 Rogue 开局",
        "handleGetRogueInfo": "处理查询 Rogue 局信息",
        "handleMove": "处理 Rogue 房间移动",
        "handleSelectBlessing": "处理选择祝福",
        "handleSelectMiracle": "处理选择奇物",
        "handleBattleResult": "处理 Rogue 战斗结果",
        "handleEvent": "处理 Rogue 房间事件",
        "handleQuit": "处理 Rogue 退出",
        "handleGetGlobalInfo": "处理查询 Rogue 全局数据",
        "handleUpgradeTalent": "处理 Rogue 天赋升级",
        "handleSelectPath": "处理选择 Rogue 路径",
        "handleGetBag": "处理查询背包",
        "handleUseItem": "处理使用道具",
        "handleEquipItem": "处理装备道具",
        "handleUnequipItem": "处理卸下装备",
        "handleEnhanceItem": "处理强化道具",
        "handlePromoteItem": "处理突破道具",
        "handleRankUpItem": "处理叠影道具",
        "handleLockItem": "处理锁定/解锁道具",
        "handleDiscardItem": "处理丢弃道具",
        "handleGetGachaInfo": "处理查询抽卡信息",
        "handleDoGacha": "处理执行抽卡",
        "handleExchangeCeiling": "处理保底兑换",
        "handleGetHistory": "处理查询抽卡历史",
        "handleStartChallenge": "处理开始挑战",
        "handleGetChallengeInfo": "处理查询挑战详情",
        "handleReportResult": "处理上报挑战结果",
        "handleClaimGroupReward": "处理领取挑战组奖励",
        "handleGetGroupRewardState": "处理查询挑战组奖励状态",
    }
    arg_hints = {
        "playerId,": "传入玩家 ID",
        "battleId,": "传入战斗 ID",
        "endStatus,": "传入结束状态码",
        "context.getPlayerId(),": "传入战局所属玩家 ID",
        "nowSeconds": "传入开战/事件时间戳",
        "waves);": "传入波次配置列表",
        "waves,": "传入波次配置",
        "\"RESULT\",": "事件来源：正常结算",
        "\"QUIT\",": "事件来源：主动退出",
        "3,": "end_status=3 表示退出",
    }

    for line in out:
        stripped = line.rstrip()
        if "//" in stripped:
            out2.append(line)
            continue
        s = stripped.strip()
        if not s or s in ("{", "}"):
            out2.append(line)
            continue

        hint = None
        for mname, mh in method_hints.items():
            if re.search(rf"\b{mname}\s*\(", stripped):
                hint = mh
                break
        if hint is None and re.search(r"\} else \{", stripped):
            hint = "不满足前置条件时走备用分支"
        if hint is None:
            for frag, ah in arg_hints.items():
                if frag in stripped:
                    hint = ah
                    break
        if hint is None and re.match(r"^\s+private (?:static )?final ", stripped):
            hint = "声明类成员依赖或常量"
        if hint is None and re.match(r"^\s+public \S+ \w+\(", stripped):
            hint = "协议处理方法入口"
        if hint is None and re.match(r"^\s+private \S+ \w+\(", stripped):
            hint = "内部辅助方法"
        if hint is None and stripped.rstrip(",").strip().endswith(")"):
            pass
        elif hint is None and ("," in s or s.endswith(");")) and len(stripped) - len(stripped.lstrip()) >= 12:
            hint = "多行调用的续行参数"
        if hint is None and len(stripped) - len(stripped.lstrip()) >= 8 and s.endswith(";"):
            hint = "执行语句"
        if hint:
            out2.append(stripped + f" // {hint}\n")
            changed = True
        else:
            out2.append(line)

    # 第三遍已禁用：避免生成「执行语句」等占位注释；请使用 tools/refine_netty_line_comments.py
    if changed:
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.writelines(out2)
        return True
    return False


def main():
    targets = sys.argv[1:] if len(sys.argv) > 1 else [
        "src/main/java/cn/itcast/demo/mylunarcore/battle/BattleNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/scene/SceneNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/rogue/RogueNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/item/ItemNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/gacha/GachaNettyService.java",
        "src/main/java/cn/itcast/demo/mylunarcore/challenge/ChallengeNettyService.java",
    ]
    updated = []
    for rel in targets:
        p = os.path.join(ROOT, rel)
        if os.path.isfile(p) and annotate_file(p):
            updated.append(rel)
    print(f"Annotated {len(updated)} files")
    for p in updated:
        print(f"  {p}")


if __name__ == "__main__":
    main()
