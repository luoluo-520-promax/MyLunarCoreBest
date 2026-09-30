package cn.itcast.demo.mylunarcore.assist.remote; // 远程提问请求所在包

import java.util.List; // 使用列表承载任务与活动摘要
import java.util.Map; // 使用映射承载只读玩家上下文
/**
 * 发送给旁路 AI 微服务的提问请求。 <p> 这个 DTO 只承载跨服务所需的只读上下文和问题本身， 不包含任何写库语义。
 */
public record RemoteAssistAskRequest(
        int schemaVersion, // 契约版本，便于跨服务兼容判断
        long uid, // 玩家 uid，仅用于审计和日志关联
        String question, // 玩家输入的自然语言问题
        String scene, // 当前业务场景标识
        Map<String, Object> playerContext, // 由上下文构建器生成的只读投影
        List<QuestSnapshot> quests, // 当前任务摘要列表
        List<ActivitySnapshot> activities, // 当前活动摘要列表
        List<KnowledgeHint> knowledgeHints // 本地召回的知识提示
) { // 记录/配置对象的紧凑构造体定义开始
    /**
     * 当前请求契约版本
     */
    public static final int SCHEMA_VERSION = 1;

    public RemoteAssistAskRequest { // 紧凑构造，补齐 schemaVersion
        if (schemaVersion <= 0) { // schemaVersion 非法时回填当前版本
            schemaVersion = SCHEMA_VERSION; // 使用当前契约版本
        } // schemaVersion 判断结束
    } // 紧凑构造结束
    /**
     * 兼容简化构造
     */
    public RemoteAssistAskRequest(long uid, String question, String scene, Map<String, Object> playerContext, List<QuestSnapshot> quests, List<ActivitySnapshot> activities, List<KnowledgeHint> knowledgeHints) {
        this(SCHEMA_VERSION, uid, question, scene, playerContext, quests, activities, knowledgeHints); // 自动补当前版本号
    } // 兼容构造结束
    /**
     * 任务快照
     */
    public record QuestSnapshot(int questId, int status, String title) { // 任务摘要结构
    } // QuestSnapshot 结束
    /**
     * 活动快照
     */
    public record ActivitySnapshot(int activityId, String name, long endTime, String description) { // 活动摘要结构
    } // ActivitySnapshot 结束
    /**
     * 知识提示
     */
    public record KnowledgeHint(String id, String type, String text) { // 知识提示结构
    } // KnowledgeHint 结束
}
