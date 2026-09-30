package cn.itcast.demo.mylunarcore.assist; // 任务引导服务所在包

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取任务引导阶段上限
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity; // 读取玩家任务进度
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository; // 解析任务标题
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService; // 查询任务进度列表
import org.springframework.stereotype.Service; // 注册为服务组件

import java.util.List; // 返回任务列表
import java.util.Locale; // 处理求助文本小写
import java.util.Map; // 导出询问次数快照
import java.util.concurrent.ConcurrentHashMap; // 保存 uid+questId 的询问次数
import java.util.concurrent.atomic.AtomicInteger; // 原子计数推进提示阶段

/**
 * 为当前进行中的任务提供阶梯提示、兜底文案和“迷路”意图识别。
 * <p>
 * 同一 uid+questId 每问一次 stage+1，上限由 {@code questGuidanceMaxStage} 钳制在 1~5。
 */
@Service
public class QuestGuidanceService { // 任务引导服务
    /**
     * 读取任务提示梯子
     */
    private final AssistFeatureContentRepository contentRepository;
    /**
     * 读取玩家任务进度
     */
    private final QuestProgressApplicationService questProgressApplicationService;
    /**
     * 解析任务标题
     */
    private final QuestConfigRepository questConfigRepository;
    /**
     * 读取最大 stage 配置
     */
    private final LunarCoreProperties properties;
    /**
     * 记录每个任务被问到的次数
     */
    private final ConcurrentHashMap<String, AtomicInteger> askCounts = new ConcurrentHashMap<>();
    /**
     * 构造注入依赖
     */
    public QuestGuidanceService(AssistFeatureContentRepository contentRepository, QuestProgressApplicationService questProgressApplicationService, QuestConfigRepository questConfigRepository, LunarCoreProperties properties) {
        this.contentRepository = contentRepository; // 保存任务提示配置仓库
        this.questProgressApplicationService = questProgressApplicationService; // 保存任务进度服务
        this.questConfigRepository = questConfigRepository; // 保存任务配置仓库
        this.properties = properties; // 保存运行时配置
    } // 构造结束
    /**
     * 任务引导结果
     */
    public record Guidance(int retcode, int questId, int stage, String title, String message, String source) { // 任务引导结果
    } // Guidance 结束
    /**
     * 针对当前活跃任务给出引导
     */
    public Guidance guide(long uid, String question) {
        if (uid <= 0) { // 非法 uid 视作未登录
            return new Guidance(1, 0, 0, "", "", "error"); // 返回未登录错误结果
        } // uid 判断结束
        int playerId = (int) (uid & 0xffffffffL); // 按存档约定取低 32 位作为 playerId
        List<QuestProgressEntity> quests = questProgressApplicationService.list(playerId); // 查询玩家任务进度
        QuestProgressEntity active = pickActive(quests); // 选出当前活跃任务
        if (active == null) { // 没有活跃任务时给出兜底文案
            return new Guidance(0, 0, 0, "暂无进行中任务", "当前没有进行中的任务。可打开任务界面接取或查看已完成进度。", "quest-guide"); // 返回引导去任务界面
        } // 活跃任务判断结束
        int questId = active.getQuestId(); // 取出当前任务 ID
        int stage = nextStage(uid, questId); // 按询问次数推进阶段
        AssistFeatureContent.HintStage configured = findStage(questId, stage); // 查找配置的提示阶段
        String questTitle = resolveTitle(questId); // 解析任务标题
        if (configured != null) { // 命中配置阶梯时直接用配置文案
            return new Guidance(0, questId, stage, nullToEmpty(configured.title()), "【任务：" + questTitle + "】" + nullToEmpty(configured.message()), "quest-guide"); // 返回配置引导
        } // 配置阶梯判断结束
        return new Guidance(0, questId, stage, "进度提示 Lv." + stage, buildFallback(questTitle, active, stage), "quest-guide"); // 使用兜底提示
    } // guide 结束
    /**
     * 识别迷路或卡关意图
     */
    public boolean looksLost(String question) {
        if (question == null || question.isBlank()) { // 空问题不算求助
            return false; // 返回不匹配
        } // 空问题判断结束
        String q = question.toLowerCase(Locale.ROOT); // 统一小写匹配
        return q.contains("迷路") || q.contains("找不到") || q.contains("不知道做什么") || q.contains("卡关") || q.contains("卡住") || q.contains("提示") || q.contains("怎么做") || q.contains("下一步") || q.contains("帮助"); // 常见卡关问法
    } // looksLost 结束
    /**
     * 计算下一阶段编号
     */
    private int nextStage(long uid, int questId) {
        int max = Math.max(1, Math.min(5, properties.getAiAssist().getQuestGuidanceMaxStage())); // 将 stage 上限钳制在 1~5
        AtomicInteger counter = askCounts.computeIfAbsent(uid + "|" + questId, k -> new AtomicInteger(0)); // 为该任务懒创建计数器
        int n = counter.incrementAndGet(); // 询问次数自增
        return Math.min(max, n); // 阶段号不超过配置上限
    } // nextStage 结束
    /**
     * 在配置中查找目标阶段
     */
    private AssistFeatureContent.HintStage findStage(int questId, int stage) {
        for (AssistFeatureContent.QuestHintLadder ladder : contentRepository.current().questHintLadders()) { // 遍历所有任务提示梯子
            if (ladder == null || ladder.questId() != questId) { // 跳过空梯子和非目标任务
                continue; // 继续查找下一个梯子
            } // 梯子过滤结束
            for (AssistFeatureContent.HintStage s : ladder.stages()) { // 遍历每个阶段
                if (s != null && s.stage() == stage) { // 找到目标 stage
                    return s; // 返回配置提示
                } // 阶段判断结束
            } // 阶段循环结束
        } // 梯子循环结束
        return null; // 未找到配置阶段
    } // findStage 结束
    /**
     * 选取活跃任务
     */
    private static QuestProgressEntity pickActive(List<QuestProgressEntity> quests) {
        if (quests == null) { // 空列表无法选取
            return null; // 返回空结果
        } // 空列表判断结束
        QuestProgressEntity best = null; // 记录最佳任务
        for (QuestProgressEntity q : quests) { // 遍历任务进度
            if (q == null) { // 跳过空项
                continue; // 不参与选择
            } // 空项判断结束
            int st = q.getStatus(); // 读取任务状态
            if (st != CoachRuleEngine.QUEST_STATUS_IN_PROGRESS && st != CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) { // 只关注进行中与可提交
                continue; // 其余状态跳过
            } // 状态判断结束
            if (best == null || st == CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) { // 首个命中或可提交任务优先
                best = q; // 更新候选任务
            } // 最佳任务判断结束
        } // 任务循环结束
        return best; // 返回当前活跃任务
    } // pickActive 结束
    /**
     * 从任务配置中解析标题
     */
    private String resolveTitle(int questId) {
        QuestConfigRepository.QuestConfig cfg = questConfigRepository.find(questId); // 查找任务配置
        if (cfg == null || cfg.title() == null || cfg.title().isBlank()) { // 没有标题时使用兜底格式
            return "任务#" + questId; // 返回任务编号占位名
        } // 标题判断结束
        return cfg.title(); // 返回正式标题
    } // resolveTitle 结束
    /**
     * 生成无配置时的兜底文案
     */
    private static String buildFallback(String questTitle, QuestProgressEntity active, int stage) {
        if (active.getStatus() == CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) { // 可提交任务优先提示提交
            return "【任务：" + questTitle + "】目标已完成，去任务界面提交即可。"; // 返回提交提示
        } // 可提交判断结束
        return switch (stage) { // 按询问阶段分档输出
            case 1 -> "【任务：" + questTitle + "】先确认任务面板上的当前目标描述，朝标注区域前进。"; // 第一阶段给方向提示
            case 2 -> "【任务：" + questTitle + "】聚焦未完成的目标条目，优先完成交互类条目。"; // 第二阶段强调目标条目
            default -> "【任务：" + questTitle + "】打开任务追踪，按提示与目标 NPC/物件交互直至进度满格。"; // 更高阶段给更具体的操作
        }; // switch 结束
    } // buildFallback 结束
    /**
     * 导出询问次数快照
     */
    public Map<String, Integer> askCountSnapshot() {
        Map<String, Integer> snap = new ConcurrentHashMap<>(); // 准备普通数值快照
        askCounts.forEach((k, v) -> snap.put(k, v.get())); // 将原子计数展开为整数
        return Map.copyOf(snap); // 返回只读快照
    } // askCountSnapshot 结束
    /**
     * 空值转空串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s; // 避免返回 null
    } // nullToEmpty 结束
}
