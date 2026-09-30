package cn.itcast.demo.mylunarcore.assist.remote; // 远程提问响应所在包

import java.util.List; // 使用列表承载提示与引用 ID
/**
 * 旁路 AI 微服务返回的答复结果。 <p> 游戏服拿到这个结果后，还会继续走本地安全清洗和长度控制， 再组装成最终对外的 AssistAnswer。
 */
public record RemoteAssistAskResponse(
        int schemaVersion, // 响应契约版本
        int retcode, // 业务返回码
        String answer, // 面向玩家的答复正文
        String source, // 旁路标记的来源
        List<Hint> relatedHints, // 额外提示列表
        List<String> citedConfigIds // 引用到的配置或知识 ID
) { // 记录/配置对象的紧凑构造体定义开始
    public RemoteAssistAskResponse { // 紧凑构造，统一默认值
        if (schemaVersion <= 0) { // 版本缺失时回退当前契约版本
            schemaVersion = RemoteAssistAskRequest.SCHEMA_VERSION; // 使用请求侧版本常量
        } // schemaVersion 判断结束
        relatedHints = relatedHints == null ? List.of() : List.copyOf(relatedHints); // 冻结提示列表
        citedConfigIds = citedConfigIds == null ? List.of() : List.copyOf(citedConfigIds); // 冻结引用 ID 列表
        answer = answer == null ? "" : answer; // 空正文回退空串
        source = source == null ? "" : source; // 空来源回退空串
    } // 紧凑构造结束
    /**
     * 兼容旧构造
     */
    public RemoteAssistAskResponse(int retcode, String answer, String source, List<Hint> relatedHints, List<String> citedConfigIds) {
        this(RemoteAssistAskRequest.SCHEMA_VERSION, retcode, answer, source, relatedHints, citedConfigIds); // 自动补 schemaVersion
    } // 兼容构造结束
    /**
     * 远程返回的提示项
     */
    public record Hint(String tipId, String category, int priority, String title, String message, String action, int refId) { // 单条提示记录
    } // Hint 结束
}
