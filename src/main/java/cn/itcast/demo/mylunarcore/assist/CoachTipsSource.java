package cn.itcast.demo.mylunarcore.assist;

/**
 * CoachTips 配置的只读来源接口。
 * <p>
 * 规则引擎和测试代码都通过这个接口读取当前热更新快照，
 * 不需要关心底层数据来自文件、内存还是替身实现。
 */
public interface CoachTipsSource {

    /**
     * 获取当前正在生效的 CoachTips 配置快照。
     *
     * @return 当前提示配置，通常不会是 {@code null}
     */
    CoachTipsConfig current();
}
