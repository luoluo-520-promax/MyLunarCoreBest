package cn.itcast.demo.mylunarcore.assist; // 探索路线顾问所在包

import org.springframework.stereotype.Service; // 作为业务服务注册

import java.util.ArrayList; // 收集资源提示
import java.util.Comparator; // 按得分比较路线
import java.util.List; // 返回只读列表
import java.util.Locale; // 统一问句小写处理
import java.util.Optional; // 表示可选建议结果

/**
 * 根据任务或场景，为玩家挑选更合适的探索路线和随身资源提示。
 * <p>
 * 路线数据来自 {@link AssistFeatureContentRepository} 热更内容；
 * 匹配优先级：任务 ID 精确匹配（+10）高于场景 ID 匹配（+5）。
 */
@Service
public class ExplorePathAdvisor { // 探索路线顾问
    /**
     * 读取探索路线配置
     */
    private final AssistFeatureContentRepository contentRepository;
    /**
     * 构造注入内容仓库
     */
    public ExplorePathAdvisor(AssistFeatureContentRepository contentRepository) {
        this.contentRepository = contentRepository; // 保存热更内容来源
    } // 构造结束
    /**
     * 探索路线建议结果
     */
    public record PathAdvice(int retcode, String routeId, String title, List<AssistFeatureContent.Waypoint> waypoints, List<String> resourceHints, String summary) { // 探索建议结果
    } // PathAdvice 结束
    /**
     * 根据任务或场景给出路线建议
     */
    public PathAdvice advise(int questId, int sceneId) {
        AssistFeatureContent content = contentRepository.current(); // 读取当前生效的功能内容
        Optional<AssistFeatureContent.ExploreRoute> match = content.exploreRoutes().stream() // 遍历所有路线配置
                .filter(r -> r != null) // 跳过空路线项
                .filter(r -> (questId > 0 && r.questId() == questId) || (sceneId > 0 && r.sceneId() == sceneId)) // 任务或场景命中即候选
                .max(Comparator.comparingInt(r -> scoreRoute(r, questId, sceneId))); // 取得分最高的路线
        if (match.isEmpty()) { // 没有找到可用路线
            return new PathAdvice(4, "", "", List.of(), List.of(), "暂无匹配的路线，请打开任务界面查看目标区域。"); // 返回无匹配提示
        } // 无匹配判断结束
        AssistFeatureContent.ExploreRoute route = match.get(); // 取出命中的路线
        List<String> resources = new ArrayList<>(); // 收集资源提示摘要
        if (route.resourceHints() != null) { // 只有配置了资源提示才遍历
            for (AssistFeatureContent.ResourceHint h : route.resourceHints()) { // 逐条资源提示扫描
                if (h != null && h.itemHint() != null && !h.itemHint().isBlank()) { // 仅收集非空提示文本
                    resources.add(h.itemHint()); // 把资源提示加入摘要列表
                } // 资源提示过滤结束
            } // 资源提示循环结束
        } // 资源提示集合判断结束
        String summary = "已标记最优路线：" + nullToEmpty(route.title()) + "，共" + route.waypoints().size() + " 个埋点" + (resources.isEmpty() ? "。" : "；沿途可收集：" + String.join("，", resources)); // 拼装给玩家的摘要
        return new PathAdvice(0, nullToEmpty(route.routeId()), nullToEmpty(route.title()), route.waypoints(), List.copyOf(resources), summary); // 返回成功建议
    } // advise 结束
    /**
     * 从自然语言中识别问路意图
     */
    public Optional<PathAdvice> adviseFromQuestion(String question) {
        if (question == null || question.isBlank()) { // 空问题没有路由意图
            return Optional.empty(); // 返回空建议
        } // 空问题判断结束
        String q = question.toLowerCase(Locale.ROOT); // 统一小写便于包含判断
        if (!(q.contains("怎么走") || q.contains("路线") || q.contains("导航") || q.contains("路径") || q.contains("去哪") || q.contains("标记") || q.contains("捷径"))) { // 未命中问路关键词
            return Optional.empty(); // 不抢答，交给其他路径
        } // 关键词判断结束
        return Optional.of(advise(0, 1)); // 默认按场景 1 给出通用路线
    } // adviseFromQuestion 结束
    /**
     * 计算路线得分
     */
    private static int scoreRoute(AssistFeatureContent.ExploreRoute r, int questId, int sceneId) {
        int score = 0; // 初始化得分
        if (questId > 0 && r.questId() == questId) { // 任务 ID 精确匹配
            score += 10; // 任务命中权重较高
        } // 任务匹配结束
        if (sceneId > 0 && r.sceneId() == sceneId) { // 场景 ID 匹配
            score += 5; // 场景命中给次级权重
        } // 场景匹配结束
        return score; // 返回最终得分
    } // scoreRoute 结束
    /**
     * 将空值转为空串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s; // 防止摘要出现 null
    } // nullToEmpty 结束
}
