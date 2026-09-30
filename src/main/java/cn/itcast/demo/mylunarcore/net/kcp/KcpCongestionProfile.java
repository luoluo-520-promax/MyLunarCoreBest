package cn.itcast.demo.mylunarcore.net.kcp;

/**
 * 按 RTT 分段的 KCP 拥塞画像：强网降重传倍数提吞吐，弱网强制 nodelay 换实时性。
 */
public record KcpCongestionProfile(
        boolean nodelay,
        int intervalMs,
        int resend,
        boolean nc,
        int rtoMultiplierBp,
        boolean fastAck
) {
    /** 强网 RTT&lt;50ms：降重传倍数、提高吞吐 */
    public static KcpCongestionProfile strong() {
        return new KcpCongestionProfile(true, 10, 2, true, 80, true);
    }

    /** 常态 */
    public static KcpCongestionProfile normal() {
        return new KcpCongestionProfile(true, 20, 2, true, 100, true);
    }

    /** 弱网 RTT&gt;200ms：nodelay=1，牺牲约 10% 可靠性换实时性 */
    public static KcpCongestionProfile weak() {
        return new KcpCongestionProfile(true, 10, 2, true, 150, true);
    }

    public static KcpCongestionProfile forRtt(long rttMs) {
        if (rttMs < 50L) {
            return strong();
        }
        if (rttMs > 200L) {
            return weak();
        }
        return normal();
    }
}
