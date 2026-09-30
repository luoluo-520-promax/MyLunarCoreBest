// 声明当前包：AI 助手旁路微服务共用的「提问」契约模型
package cn.itcast.demo.mylunarcore.common.api.assist;

// 列表：任务/活动/知识片段的快照集合
import java.util.List;
// 键值对：玩家上下文中的原始属性（等级、背包摘要等）
import java.util.Map;

/**
 * 游戏服 → ai-assist-service 的提问契约（只读上下文，不含写权威状态）。
 *
 * <p>该 record 定义了游戏服调用旁路 AI 服务时提交的完整上下文：除了玩家直接输入的
 * 问题文本外，还携带玩家当前的任务/活动/知识库命中片段等只读快照，供旁路服务在
 * 不读取数据库的前提下生成建议。</p>
 *
 * @param schemaVersion   契约 schema 版本号，必须与 {@link AssistSchemaVersions#CURRENT} 一致，
 *                        版本不一致时旁路服务会告警并按兼容策略处理
 * @param uid             玩家唯一数字 ID（用于日志脱敏、限流与后续审计，不用于查询私有数据）
 * @param question        玩家提出的问题原文，可为空
 * @param scene           当前所在场景/界面标识（如 quest、activity、coach），用于定制回答口径
 * @param playerContext   玩家上下文键值对（等级、体力、队伍、背包摘要等），全部为只读快照
 * @param quests          玩家当前任务快照列表，用于生成任务类建议
 * @param activities      玩家当前可参与的活动快照列表，用于生成活动类建议
 * @param knowledgeHints  知识库检索命中的片段列表（含配置 ID 与文本），用于回答世界观/配置类问题
 */
public record AssistAskRequest(
        int schemaVersion,
        long uid,
        String question,
        String scene,
        Map<String, Object> playerContext,
        List<QuestSnapshot> quests,
        List<ActivitySnapshot> activities,
        List<KnowledgeHint> knowledgeHints
) {
    /**
     * 紧凑构造器：对入参做防御性归一化。
     * <p>当调用方未显式传入合法版本号（小于等于 0）时，自动补齐为当前最新 schema 版本，
     * 避免旧调用方因缺字段而被版本校验拦截。</p>
     */
    public AssistAskRequest {
        if (schemaVersion <= 0) {
            schemaVersion = AssistSchemaVersions.CURRENT;
        }
    }

    /**
     * 兼容旧调用方：自动填入当前 schema 版本。
     *
     * @param uid           玩家 ID
     * @param question      问题原文
     * @param scene         场景标识
     * @param playerContext 玩家只读上下文
     * @param quests        任务快照
     * @param activities    活动快照
     * @param knowledgeHints 知识片段
     */
    public AssistAskRequest(long uid,
                            String question,
                            String scene,
                            Map<String, Object> playerContext,
                            List<QuestSnapshot> quests,
                            List<ActivitySnapshot> activities,
                            List<KnowledgeHint> knowledgeHints) {
        this(AssistSchemaVersions.CURRENT, uid, question, scene, playerContext, quests, activities, knowledgeHints);
    }

    /**
     * 任务快照：用于向旁路服务描述玩家任务进度。
     *
     * @param questId 任务配置 ID
     * @param status  任务状态（与游戏服任务状态约定一致，如 1=进行中、2=可提交）
     * @param title   任务标题（用于生成可读文案，可缺省）
     */
    public record QuestSnapshot(int questId, int status, String title) {
    }

    /**
     * 活动快照：用于向旁路服务描述玩家可参与的活动。
     *
     * @param activityId  活动配置 ID
     * @param name        活动名称
     * @param endTime     活动结束时间（Unix 秒级时间戳）
     * @param description 活动描述
     */
    public record ActivitySnapshot(int activityId, String name, long endTime, String description) {
    }

    /**
     * 知识库片段：旁路服务 RAG 检索命中后回传的内容。
     *
     * @param id   知识条目唯一 ID（回答时用于溯源引用）
     * @param type 知识类型（如 lore、enemy、world）
     * @param text 知识正文
     */
    public record KnowledgeHint(String id, String type, String text) {
    }
}
