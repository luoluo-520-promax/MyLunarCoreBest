package cn.itcast.demo.mylunarcore.assist;

/**
 * AssistSafetyRules 配置的只读来源接口。
 * <p>
 * 安全过滤器只依赖这个接口读取当前规则快照，
 * 不直接耦合具体仓储实现，便于热更和测试替换。
 */
public interface AssistSafetyRulesSource {

    /**
     * 获取当前正在生效的安全规则快照。
     *
     * @return 当前安全规则配置
     */
    AssistSafetyRulesConfig current();
}
