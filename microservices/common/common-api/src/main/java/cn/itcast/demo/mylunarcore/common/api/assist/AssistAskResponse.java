// 声明当前包：AI 助手旁路微服务共用的「回答」契约模型
package cn.itcast.demo.mylunarcore.common.api.assist;

// 列表：关联提示与引用的配置 ID
import java.util.List;

/**
 * ai-assist-service 回答契约。
 *
 * <p>旁路服务处理完提问后，将规则引擎结果（或 LLM 结果）统一封装为该 record 返回。
 * 游戏服通过 {@code source} 字段区分回答来源（rule / llm / blocked 等），
 * 用于本地兜底与灰度对比。</p>
 *
 * @param schemaVersion          响应携带的契约版本号，缺省自动补为当前版本
 * @param retcode                业务返回码，0 表示成功
 * @param answer                 最终给玩家的回答文本
 * @param source                 回答来源标识（如 rule、llm、blocked、gateway-fallback）
 * @param relatedHints           关联提示列表（可附带跳转动作），可能为空但不会为 null
 * @param citedConfigIds         回答引用的配置 ID 列表（用于溯源与审计）
 * @param inlineSummary          200 字以内精简攻略，供客户端优先展示
 * @param forbidExternalBrowser  true=客户端必须用站内 WebView，禁止跳出浏览器
 */
public record AssistAskResponse(
        int schemaVersion,
        int retcode,
        String answer,
        String source,
        List<Hint> relatedHints,
        List<String> citedConfigIds,
        String inlineSummary,
        boolean forbidExternalBrowser
) {
    /**
     * 紧凑构造器：对入参做防御性归一化。
     */
    public AssistAskResponse {
        if (schemaVersion <= 0) {
            schemaVersion = AssistSchemaVersions.CURRENT;
        }
        relatedHints = relatedHints == null ? List.of() : List.copyOf(relatedHints);
        citedConfigIds = citedConfigIds == null ? List.of() : List.copyOf(citedConfigIds);
        answer = answer == null ? "" : answer;
        source = source == null ? "" : source;
        if (inlineSummary == null || inlineSummary.isBlank()) {
            inlineSummary = answer.length() <= 200 ? answer : answer.substring(0, 199) + "…";
        } else if (inlineSummary.length() > 200) {
            inlineSummary = inlineSummary.substring(0, 199) + "…";
        }
        forbidExternalBrowser = true;
    }

    /**
     * 兼容旧构造：自动填入当前 schema 版本、摘要与禁止外链。
     */
    public AssistAskResponse(int retcode,
                             String answer,
                             String source,
                             List<Hint> relatedHints,
                             List<String> citedConfigIds) {
        this(AssistSchemaVersions.CURRENT, retcode, answer, source, relatedHints, citedConfigIds, "", true);
    }

    /**
     * 兼容六参构造（含 schemaVersion）。
     */
    public AssistAskResponse(int schemaVersion,
                             int retcode,
                             String answer,
                             String source,
                             List<Hint> relatedHints,
                             List<String> citedConfigIds) {
        this(schemaVersion, retcode, answer, source, relatedHints, citedConfigIds, "", true);
    }

    /**
     * 关联提示：可附加到回答后，驱动客户端展示引导卡片/跳转。
     */
    public record Hint(
            String tipId,
            String category,
            int priority,
            String title,
            String message,
            String action,
            int refId
    ) {
    }
}
