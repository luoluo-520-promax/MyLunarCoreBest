// 声明当前包：AI 旁路微服务的规则引擎实现
package cn.itcast.demo.mylunarcore.assist;

// 提问契约
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskRequest;
// 回答契约
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskResponse;
// 提示词模板常量
import cn.itcast.demo.mylunarcore.common.api.assist.AssistPromptTemplates;
// 默认安全正则
import cn.itcast.demo.mylunarcore.common.api.assist.AssistSafetyDefaults;
// 契约版本
import cn.itcast.demo.mylunarcore.common.api.assist.AssistSchemaVersions;
// Spring 组件
import org.springframework.stereotype.Component;

// 时间戳：用于活动到期判断
import java.time.Instant;
// 动态数组
import java.util.ArrayList;
// 比较器
import java.util.Comparator;
// 不可变列表
import java.util.List;
// 区域无关大小写
import java.util.Locale;
// 正则表达式
import java.util.regex.Pattern;

/**
 * 旁路服务内规则教练 + 安全过滤（共享 common-api Safety/Prompt 约定）。
 *
 * <p>该引擎不依赖外部 LLM 即可输出基础建议：根据玩家任务、活动、知识片段等只读
 * 上下文生成简洁回答，同时执行隐私拦截与充值诱导清洗。若上层开启外部 LLM，
 * 规则引擎产出的结果仍可作为兜底模板与安全约束。</p>
 */
@Component
public class RemoteAssistEngine {

    /**
     * 硬拦截模式：命中后直接返回隐私拦截文案。
     */
    private final List<Pattern> blockPatterns;

    /**
     * 出站清洗模式：命中后将 LLM 输出替换为保守口径。
     */
    private final List<Pattern> outboundPatterns;

    /**
     * 构造器：从 common-api 读取默认正则并编译。
     */
    public RemoteAssistEngine() {
        this.blockPatterns = compile(AssistSafetyDefaults.BLOCK_PATTERNS);
        this.outboundPatterns = compile(AssistSafetyDefaults.OUTBOUND_BLOCK_PATTERNS);
    }

    /**
     * 根据请求上下文生成回答。
     *
     * @param request 提问请求（仅只读上下文）
     * @return 回答契约；retcode=0 表示成功，5 表示空问题或引擎异常兜底
     */
    public AssistAskResponse evaluate(AssistAskRequest request) {
        // 版本不一致时仅作兼容性记录；当前实现仍尽量返回结果
        if (request.schemaVersion() > 0 && request.schemaVersion() != AssistSchemaVersions.CURRENT) {
            // 版本漂移：仍尽量作答，但 source 标记便于灰度对比
        }
        String question = request.question() == null ? "" : request.question().trim();
        if (question.isEmpty()) {
            // 空问题：直接返回失败码，避免生成无意义文本
            return new AssistAskResponse(5, "", "", List.of(), List.of());
        }
        if (matchesAny(question, blockPatterns)) {
            // 命中隐私/不当请求：直接拦截，不再读取上下文
            return new AssistAskResponse(0, AssistPromptTemplates.PRIVACY_BLOCK, "blocked", List.of(), List.of());
        }

        // 关联提示：任务、活动、兜底建议等
        List<AssistAskResponse.Hint> hints = new ArrayList<>();
        if (request.quests() != null) {
            request.quests().stream()
                    // 只关注进行中或可提交的任务
                    .filter(q -> q != null && (q.status() == 1 || q.status() == 2))
                    // 选 ID 最小的一条作为优先建议，避免一次性塞太多内容
                    .min(Comparator.comparingInt(AssistAskRequest.QuestSnapshot::questId))
                    .ifPresent(q -> {
                        boolean claim = q.status() == 2;
                        hints.add(new AssistAskResponse.Hint(
                                claim ? "quest_claim" : "quest_next",
                                "quest",
                                claim ? 110 : 100,
                                claim ? "任务可提交" : "继续任务",
                                "任务「" + safeTitle(q.title(), q.questId()) + "」" + (claim ? "已可提交领奖。" : "建议优先推进。"),
                                "OPEN_QUEST",
                                q.questId()
                        ));
                    });
        }
        if (request.activities() != null) {
            long now = Instant.now().getEpochSecond();
            long deadline = now + 72L * 3600;
            request.activities().stream()
                    // 仅建议 72 小时内结束的活动
                    .filter(a -> a != null && a.endTime() >= now && a.endTime() <= deadline)
                    .min(Comparator.comparingLong(AssistAskRequest.ActivitySnapshot::endTime))
                    .ifPresent(a -> hints.add(new AssistAskResponse.Hint(
                            "activity_ending",
                            "activity",
                            90,
                            "活动即将结束",
                            "活动「" + safeTitle(a.name(), a.activityId()) + "」即将结束，建议尽快参与或领奖。",
                            "OPEN_ACTIVITY",
                            a.activityId()
                    )));
        }
        hints.sort(Comparator.comparingInt(AssistAskResponse.Hint::priority).reversed());
        if (hints.isEmpty()) {
            // 没有任何明确线索时给一条通用建议，避免空答复
            hints.add(new AssistAskResponse.Hint(
                    "fallback_today", "fallback", 10, "今日建议",
                    "今天可以先推进主线任务，再查看限时活动。", "OPEN_HOME", 0));
        }

        // 收集知识库引用片段，供回答中展示配置 ID 溯源
        List<String> cited = new ArrayList<>();
        StringBuilder knowledgeText = new StringBuilder();
        if (request.knowledgeHints() != null) {
            for (AssistAskRequest.KnowledgeHint k : request.knowledgeHints()) {
                if (k == null) {
                    continue;
                }
                cited.add(k.id());
                knowledgeText.append('[').append(k.id()).append("] ").append(k.text()).append('\n');
                if (cited.size() >= 5) {
                    break;
                }
            }
        }

        // 依据问题和提示生成回答并做安全清洗
        String answer = buildRuleAnswer(question, hints, knowledgeText.toString());
        answer = sanitize(answer);
        return new AssistAskResponse(0, answer, "rule", List.copyOf(hints.subList(0, Math.min(3, hints.size()))), List.copyOf(cited));
    }

    /**
     * 对回答做二次安全清洗。
     *
     * @param answer 原始回答
     * @return 清洗后的回答
     */
    public String sanitize(String answer) {
        if (answer == null || answer.isBlank()) {
            return "暂时无法给出建议，请打开对应界面查看官方说明。";
        }
        if (matchesAny(answer, outboundPatterns)) {
            return AssistPromptTemplates.RECHARGE_SANITIZE;
        }
        int max = AssistSafetyDefaults.MAX_ANSWER_LENGTH;
        return answer.length() > max ? answer.substring(0, max) + "…" : answer.trim();
    }

    /**
     * 根据问题、提示和知识片段拼接一段简洁回答。
     */
    private static String buildRuleAnswer(String question, List<AssistAskResponse.Hint> hints, String knowledge) {
        StringBuilder sb = new StringBuilder("根据当前进度，建议优先：");
        for (int i = 0; i < Math.min(3, hints.size()); i++) {
            if (i > 0) {
                sb.append('；');
            }
            AssistAskResponse.Hint h = hints.get(i);
            sb.append(h.title()).append(" — ").append(h.message());
        }
        if (knowledge != null && !knowledge.isBlank()) {
            sb.append(" 相关配置：").append(knowledge.replace('\n', ' ').trim());
        }
        if (question.toLowerCase(Locale.ROOT).contains("保底") || question.contains("抽卡")) {
            sb.append(' ').append(AssistPromptTemplates.GACHA_DISCLAIMER);
        }
        return sb.toString();
    }

    /**
     * 将原始字符串正则编译成不可变列表。
     */
    private static List<Pattern> compile(List<String> raw) {
        List<Pattern> out = new ArrayList<>();
        for (String p : raw) {
            out.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
        }
        return List.copyOf(out);
    }

    /**
     * 判断文本是否命中任一正则。
     */
    private static boolean matchesAny(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 安全地显示标题：为空时回退为 ID。
     */
    private static String safeTitle(String title, int id) {
        return title == null || title.isBlank() ? String.valueOf(id) : title;
    }
}
