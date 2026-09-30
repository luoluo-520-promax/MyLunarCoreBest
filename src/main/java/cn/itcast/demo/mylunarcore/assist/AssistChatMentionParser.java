package cn.itcast.demo.mylunarcore.assist; // 聊天提及解析器所在包

/**
 * 聊天频道中“@助手”提及内容的解析器。
 * <p>
 * 这个类只负责从原始聊天文本里提取玩家真正想问的问题，
 * 不负责路由、检索或回答生成。
 */
public final class AssistChatMentionParser { // 工具类禁止实例化
    /**
     * 阻止外部创建实例
     */
    private AssistChatMentionParser() {
    } // 私有构造结束
    /**
     * 提取提问正文
     */
    public static String extractQuestion(String content, String prefix) {
        if (content == null || content.isBlank()) { // 空消息没有有效问题
            return null; // 返回空表示不触发助手
        } // 空消息判断结束
        String p = (prefix == null || prefix.isBlank()) ? "@助手" : prefix; // 触发前缀空值回退默认值
        String trimmed = content.trim(); // 去掉首尾空白便于判断前缀
        if (!trimmed.startsWith(p)) { // 未以触发前缀开头时不算提问
            return null; // 返回空结果
        } // 前缀判断结束
        String question = trimmed.substring(p.length()).trim(); // 去掉前缀后得到正文
        if (question.startsWith("：") || question.startsWith(":")) { // 兼容中文冒号与英文冒号
            question = question.substring(1).trim(); // 再次去掉冒号与空白
        } // 冒号处理结束
        return question.isEmpty() ? null : question; // 只有正文非空才返回
    } // extractQuestion 结束
}
