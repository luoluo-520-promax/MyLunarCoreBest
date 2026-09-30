// 声明当前包：AI 助手旁路微服务共用的「安全规则默认值」常量
package cn.itcast.demo.mylunarcore.common.api.assist;

// 列表：承载默认正则表达式
import java.util.List;

/**
 * 默认安全正则（与 data/AssistSafetyRules.json 语义对齐，供微服务无热更文件时使用）。
 *
 * <p>旁路微服务在启动时若未加载热更的安全规则文件，则使用本类中的默认正则作为兜底，
 * 保证即使配置缺失也能拦截隐私窥探、诱导充值等高风险提问，避免裸奔上线。</p>
 */
public final class AssistSafetyDefaults {

    /**
     * 硬拦截正则：命中即拒绝回答（涉及他人账号/密码、窥探背包等隐私类请求）。
     */
    public static final List<String> BLOCK_PATTERNS = List.of(
            "(别人账号|他人密码|偷看.*背包|查一下.*uid\\s*\\d{3,})",
            "(他人账号|别人密码|窥探.*背包|查\\s*uid\\s*\\d{3,})",
            "(other\\s*player.*(password|inventory)|peek.*bag)"
    );

    /**
     * 软拦截正则：命中后降低回答优先级或采用保守口径（充值诱导类提问）。
     */
    public static final List<String> SOFT_BLOCK_PATTERNS = List.of(
            "(怎么充才划算|推荐充值|氪多少|冲多少钱|必出吗|稳出吗)"
    );

    /**
     * 出站清洗正则：命中 LLM 输出内容时，替换为充值话术清洗文案。
     */
    public static final List<String> OUTBOUND_BLOCK_PATTERNS = List.of(
            "(必出|必中|稳出|充钱就能|快去充|立即充值|氪金必|不充就亏)"
    );

    /**
     * 回答最大长度上限（字符数），防止 LLM 输出过长内容刷屏。
     */
    public static final int MAX_ANSWER_LENGTH = 800;

    /**
     * 私有构造器：本类仅承载常量，禁止实例化。
     */
    private AssistSafetyDefaults() {
    }
}
