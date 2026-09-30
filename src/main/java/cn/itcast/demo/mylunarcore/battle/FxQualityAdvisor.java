package cn.itcast.demo.mylunarcore.battle;

/**
 * 按 RTT / 丢包率动态给出特效档位：差网自动降粒子。
 * <p>设备热管理见 {@link cn.itcast.demo.mylunarcore.scene.DevicePerfProbeService}（CmdId 1080+），
 * 二者独立：网络差≠性能差。
 */
public final class FxQualityAdvisor {

    private FxQualityAdvisor() {}

    /**
     * @return 0低 1中 2高
     */
    public static int resolve(int rttMs, int packetLossBp) {
        int rtt = Math.max(0, rttMs);
        int loss = Math.max(0, packetLossBp);
        if (rtt >= 180 || loss >= 800) {
            return 0;
        }
        if (rtt >= 90 || loss >= 300) {
            return 1;
        }
        return 2;
    }

    /**
     * 综合网络档与设备探针档，取更保守者。
     */
    public static int resolveWithDevice(int rttMs, int packetLossBp, Integer deviceRenderTier) {
        int net = resolve(rttMs, packetLossBp);
        if (deviceRenderTier == null) {
            return net;
        }
        return Math.min(net, Math.max(0, deviceRenderTier));
    }
}
