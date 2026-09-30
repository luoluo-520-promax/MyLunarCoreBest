package cn.itcast.demo.mylunarcore.net.kcp;

/**
 * KCP 重传 / 拥塞控制可插拔策略接口。
 * <p>
 * kcp-netty 底层已内置 nodelay 等参数；本接口在应用层根据观测到的 RTT（往返时延）
 * 与丢包率动态调整「发送节流」与「重传激进度」，防止国内弱网环境下出现 UDP 风暴
 * （发送过快导致丢包加剧、重传风暴、服务端带宽被打满）。
 * <p>
 * 策略通过配置键 {@code lunarcore.kcp.retransmit.algo} 选择实现：
 * <ul>
 *   <li>{@code fixed}（默认）：固定发送间隔，便于联调与压测基准；</li>
 *   <li>{@code adaptive}：按 RTT/丢包率自适应退避，对抗弱网。</li>
 * </ul>
 */
public interface KcpRetransmitAlgo {

    /**
     * 可插拔策略的类型枚举，用于标识当前使用的实现。
     */
    enum Profile {
        /** 固定间隔（联调友好）：发送间隔恒定为配置值，不做退避。 */
        FIXED,
        /** 按 RTT 自适应退避（弱网友好）：RTT/丢包升高时自动拉长发送间隔。 */
        ADAPTIVE
    }

    /**
     * 根据观测到的网络质量计算下一次允许的发送间隔。
     *
     * @param rttMs        当前观测的往返时延（毫秒）
     * @param lossPermille 当前观测的丢包率（千分比，如 50 表示 5% 丢包）
     * @return 下一次允许发送的时间间隔（毫秒）
     */
    int nextSendIntervalMs(long rttMs, int lossPermille);

    /**
     * 判断当前是否应暂缓发送。
     *
     * @param rttMs          当前观测的往返时延（毫秒）
     * @param inflightPackets 正在网络中未被确认的包数量（拥塞窗口概念）
     * @return true 表示应暂缓/停止发送（避免进一步加剧拥塞）
     */
    boolean shouldThrottle(long rttMs, int inflightPackets);

    /** @return 当前实现的策略类型标识。 */
    Profile profile();
}
