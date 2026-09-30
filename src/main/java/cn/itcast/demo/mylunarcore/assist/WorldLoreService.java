package cn.itcast.demo.mylunarcore.assist; // 世界观百科服务所在包

import org.springframework.stereotype.Service; // 注册为业务服务

import java.util.ArrayList; // 收集 RAG 知识块
import java.util.Comparator; // 按 ID 排序保持稳定
import java.util.List; // 返回列表结果
import java.util.Locale; // 统一小写检索
import java.util.Optional; // 表达可选百科答复

/**
 * 基于本地世界观资料库回答设定、势力、角色与地点相关问题。
 * <p>
 * 命中阈值：条目得分至少 2；正式名 +5、别名 +4；source 固定 lore-local。
 */
@Service
public class WorldLoreService { // 世界观百科服务
    /**
     * 读取 loreEntries 与 pois
     */
    private final AssistFeatureContentRepository contentRepository;
    /**
     * 构造注入内容仓库
     */
    public WorldLoreService(AssistFeatureContentRepository contentRepository) {
        this.contentRepository = contentRepository; // 保存热更内容来源
    } // 构造结束
    /**
     * 世界观问答结果
     */
    public record LoreAnswer(String answer, String source, List<String> citedIds, String persona) { // 百科答复结果
    } // LoreAnswer 结束
    /**
     * 尝试用本地百科回答问题
     */
    public Optional<LoreAnswer> answer(String question) {
        if (question == null || question.isBlank()) { // 空问题无法检索
            return Optional.empty(); // 返回空结果
        } // 空输入判断结束
        String q = question.toLowerCase(Locale.ROOT); // 统一小写以便匹配
        if (!looksLikeLoreQuestion(q)) { // 不是典型百科提问时进一步看别名
            if (!containsAnyAlias(q)) { // 若连词条名/别名都没命中则放弃
                return Optional.empty(); // 不抢答非百科问题
            } // 别名判断结束
        } // 问题类型判断结束
        AssistFeatureContent.LoreEntry best = null; // 记录当前最优词条
        int bestScore = 0; // 记录最高得分
        for (AssistFeatureContent.LoreEntry e : contentRepository.current().loreEntries()) { // 遍历所有百科条目
            if (e == null) { // 跳过空配置项
                continue; // 不参与评分
            } // 空项判断结束
            int score = scoreEntry(e, q); // 计算词条匹配得分
            if (score > bestScore) { // 只在更高分时更新
                bestScore = score; // 记录新的最高分
                best = e; // 保存最佳词条
            } // 最高分判断结束
        } // 词条遍历结束
        if (best == null || bestScore < 2) { // 命中太弱则不返回
            return Optional.empty(); // 避免误答百科问题
        } // 分值门槛判断结束
        String persona = best.persona() == null || best.persona().isBlank() ? "史官" : best.persona(); // 缺省 persona 回退史官
        String body = best.detail() == null || best.detail().isBlank() ? nullToEmpty(best.summary()) : best.detail(); // 优先用 detail，没有则用 summary
        String answer = "【" + persona + "】关于「" + nullToEmpty(best.name()) + "」：" + body + "（类型：" + nullToEmpty(best.type()) + "）"; // 拼装百科答复正文
        return Optional.of(new LoreAnswer(answer, "lore-local", List.of("lore:" + best.id()), persona)); // 返回百科答复与引用 ID
    } // answer 结束
    /**
     * 导出 RAG 知识块
     */
    public List<RagKnowledgeService.KnowledgeChunk> toKnowledgeChunks() {
        List<RagKnowledgeService.KnowledgeChunk> list = new ArrayList<>(); // 收集可检索文本块
        for (AssistFeatureContent.LoreEntry e : contentRepository.current().loreEntries()) { // 将百科词条转为知识块
            if (e == null) { // 跳过空词条
                continue; // 不生成知识块
            } // 空词条判断结束
            String text = String.join(" ", "百科", nullToEmpty(e.type()), nullToEmpty(e.name()), String.join(" ", e.aliases()), nullToEmpty(e.summary()), nullToEmpty(e.detail()), "口吻=" + nullToEmpty(e.persona())); // 拼接词条文本
            list.add(new RagKnowledgeService.KnowledgeChunk("lore:" + e.id(), "lore", text)); // 构造知识块
        } // lore 词条遍历结束
        for (AssistFeatureContent.PoiLore poi : contentRepository.current().pois()) { // 将 POI 转为知识块
            if (poi == null) { // 跳过空 POI
                continue; // 不生成知识块
            } // 空 POI 判断结束
            String text = String.join(" ", "地点", nullToEmpty(poi.title()), String.join(" ", poi.tags()), nullToEmpty(poi.loreShort()), nullToEmpty(poi.puzzleHint())); // 拼接 POI 文本
            list.add(new RagKnowledgeService.KnowledgeChunk("poi:" + poi.poiId(), "lore", text)); // 构造 POI 知识块
        } // POI 遍历结束
        list.sort(Comparator.comparing(chunk -> chunk.id())); // 按 ID 排序保持重建顺序稳定
        return list; // 返回知识块列表
    } // toKnowledgeChunks 结束
    /**
     * 判断是否为百科意图
     */
    static boolean looksLikeLoreQuestion(String q) {
        return q.contains("是谁") || q.contains("什么势力") || q.contains("历史") || q.contains("背景") || q.contains("世界观") || q.contains("故事") || q.contains("百科") || q.contains("介绍一下") || q.contains("讲") || q.contains("faction") || q.contains("lore"); // 常见百科问法
    } // looksLikeLoreQuestion 结束
    /**
     * 识别词条名或别名命中
     */
    private boolean containsAnyAlias(String q) {
        for (AssistFeatureContent.LoreEntry e : contentRepository.current().loreEntries()) { // 遍历词条列表
            if (e == null) { // 空项跳过
                continue; // 不参与匹配
            } // 空项判断结束
            if (e.name() != null && q.contains(e.name().toLowerCase(Locale.ROOT))) { // 正式名命中
                return true; // 直接认为是百科问题
            } // 正式名判断结束
            for (String a : e.aliases()) { // 遍历别名集合
                if (a != null && !a.isBlank() && q.contains(a.toLowerCase(Locale.ROOT))) { // 别名命中
                    return true; // 触发百科路径
                } // 别名判断结束
            } // 别名循环结束
        } // 词条遍历结束
        return false; // 没有命中任何词条
    } // containsAnyAlias 结束
    /**
     * 计算词条匹配分
     */
    private static int scoreEntry(AssistFeatureContent.LoreEntry e, String q) {
        int score = 0; // 初始化得分
        if (e.name() != null && q.contains(e.name().toLowerCase(Locale.ROOT))) { // 正式名命中
            score += 5; // 给较高权重
        } // 正式名判断结束
        for (String a : e.aliases()) { // 遍历别名
            if (a != null && a.length() >= 2 && q.contains(a.toLowerCase(Locale.ROOT))) { // 仅统计足够长的别名
                score += 4; // 别名给予次高权重
            } // 别名判断结束
        } // 别名循环结束
        if (e.type() != null) { // 类型存在时按问法加权
            String t = e.type().toLowerCase(Locale.ROOT); // 统一类型文本
            if (("character".equals(t) || "角色".equals(t)) && (q.contains("角色") || q.contains("是谁"))) { // 角色词条与人物问法匹配
                score += 1; // 轻微加权
            } // 角色类型判断结束
            if (("faction".equals(t) || "势力".equals(t)) && (q.contains("势力") || q.contains("同盟"))) { // 势力词条与势力问法匹配
                score += 2; // 提升势力类权重
            } // 势力类型判断结束
            if (("history".equals(t) || "历史".equals(t)) && q.contains("历史")) { // 历史类词条与历史问法匹配
                score += 2; // 提升历史类权重
            } // 历史类型判断结束
        } // 类型判断结束
        return score; // 返回匹配总分
    } // scoreEntry 结束
    /**
     * 空值转空串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s; // 避免输出 null 字面量
    } // nullToEmpty 结束
}
