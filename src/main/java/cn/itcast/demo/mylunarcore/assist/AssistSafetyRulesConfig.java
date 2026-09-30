package cn.itcast.demo.mylunarcore.assist; // 助手安全规则配置所在包

import com.fasterxml.jackson.annotation.JsonIgnoreProperties; // 忽略热更配置里多余字段

import java.util.List; // 使用列表存放正则规则
/**
 * 助手安全规则配置根结构。 <p> 这份配置负责控制入站拦截、出站清洗、长度限制和默认回复文案， 用来保证助手回答符合隐私和充值合规要求。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistSafetyRulesConfig(
        int version, // 规则版本号，便于热更和回滚
        int maxAnswerLength, // 出站答复允许的最大字符数
        String blockedQuestionReply, // 命中硬拦截时返回的固定文案
        String softBlockReply, // 命中软拦截时返回的引导文案
        String rechargeSanitizeReply, // 出站检测到充值诱导时使用的替换文案
        String gachaDisclaimer, // 抽卡场景附加的概率免责声明
        List<String> blockPatterns, // 入站硬拦正则列表
        List<String> softBlockPatterns, // 入站软拦正则列表
        List<String> outboundBlockPatterns, // 出站整段命中规则
        List<String> outboundSentenceKillPatterns // 出站按句剔除规则
) { // 记录/配置对象的紧凑构造体定义开始
    public AssistSafetyRulesConfig { // 紧凑构造，补默认值并冻结集合
        maxAnswerLength = maxAnswerLength <= 0 ? 800 : maxAnswerLength; // 默认限制长度为 800
        blockedQuestionReply = blockedQuestionReply == null || blockedQuestionReply.isBlank() ? "该问题涉及其他玩家隐私或不当请求，助手无法回答。" : blockedQuestionReply; // 硬拦文案兜底
        softBlockReply = softBlockReply == null || softBlockReply.isBlank() ? "充值与概率相关问题请以游戏内官方说明为准，助手可帮你查看活动、任务与养成进度。" : softBlockReply; // 软拦文案兜底
        rechargeSanitizeReply = rechargeSanitizeReply == null || rechargeSanitizeReply.isBlank() ? "概率与获取方式请以游戏内官方说明为准，助手不会给出充值诱导或必出承诺。" : rechargeSanitizeReply; // 充值清洗文案兜底
        gachaDisclaimer = gachaDisclaimer == null || gachaDisclaimer.isBlank() ? "抽卡概率与保底规则以游戏内官方说明为准。" : gachaDisclaimer; // 抽卡免责声明兜底
        blockPatterns = blockPatterns == null ? List.of() : List.copyOf(blockPatterns); // 冻结硬拦规则
        softBlockPatterns = softBlockPatterns == null ? List.of() : List.copyOf(softBlockPatterns); // 冻结软拦规则
        outboundBlockPatterns = outboundBlockPatterns == null ? List.of() : List.copyOf(outboundBlockPatterns); // 冻结整段替换规则
        outboundSentenceKillPatterns = outboundSentenceKillPatterns == null ? List.of() : List.copyOf(outboundSentenceKillPatterns); // 冻结按句剔除规则
    } // 紧凑构造结束
    /**
     * 默认内置安全规则
     */
    public static AssistSafetyRulesConfig defaults() {
        return new AssistSafetyRulesConfig(0, 800, "该问题涉及其他玩家隐私或不当请求，助手无法回答。", "充值与概率相关问题请以游戏内官方说明为准，助手可帮你查看活动、任务与养成进度。", "概率与获取方式请以游戏内官方说明为准，助手不会给出充值诱导或必出承诺。", "抽卡概率与保底规则以游戏内官方说明为准。", List.of("(别人账号|他人密码|偷看.*背包|查一下.*uid\\s*\\d{3,})", "(他人账号|别人密码|窥探.*背包|查\\s*uid\\s*\\d{3,})", "(other\\s*player.*(password|inventory)|peek.*bag)"), List.of("(怎么充才划算|推荐充值|氪多少|冲多少钱|必出吗|稳出吗)"), List.of("(必出|必中|稳出|充钱就能|快去充|立即充值|氪金必|不充就亏)"), List.of(".*(必出|必中|稳出).*")); // 返回默认安全规则
    } // defaults 结束
}
