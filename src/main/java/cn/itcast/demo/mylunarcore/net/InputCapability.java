package cn.itcast.demo.mylunarcore.net;

/**
 * 多端输入能力：登录握手 {@code input_methods} 掩码，以及推荐 UI 布局。
 * <p>
 * bit0=键鼠，bit1=触屏，bit2=手柄。对应 {@link ClientFeatureFlags} 的 INPUT_* 位一并纳入
 * {@code supported_features} / {@code enabled_features}，并计入 {@link CmdIds#PROTOCOL_WIRE_VERSION} v3。
 */
public final class InputCapability {

    public static final int KEYBOARD_MOUSE = 1;
    public static final int TOUCH = 1 << 1;
    public static final int GAMEPAD = 1 << 2;

    public static final String LAYOUT_PC_KM = "pc_km";
    public static final String LAYOUT_MOBILE_TOUCH = "mobile_touch";
    public static final String LAYOUT_CONSOLE_GAMEPAD = "console_gamepad";

    private InputCapability() {}

    public static int normalize(int inputMethods) {
        return inputMethods & (KEYBOARD_MOUSE | TOUCH | GAMEPAD);
    }

    /** 将设备输入掩码映射到 SupportedFeatures 中的 INPUT_* 位。 */
    public static long toFeatureBits(int inputMethods) {
        int m = normalize(inputMethods);
        long bits = 0L;
        if ((m & KEYBOARD_MOUSE) != 0) {
            bits |= ClientFeatureFlags.INPUT_KEYBOARD_MOUSE;
        }
        if ((m & TOUCH) != 0) {
            bits |= ClientFeatureFlags.INPUT_TOUCH;
        }
        if ((m & GAMEPAD) != 0) {
            bits |= ClientFeatureFlags.INPUT_GAMEPAD;
        }
        return bits;
    }

    /**
     * 根据输入掩码与设备 ID 推荐 UI 皮肤。
     */
    public static String recommendedLayoutId(int inputMethods, String deviceId) {
        int m = normalize(inputMethods);
        String d = deviceId == null ? "" : deviceId.toLowerCase();
        boolean phoneLike = d.contains("android") || d.contains("ios") || d.contains("iphone")
                || d.contains("phone") || d.contains("mobile");
        boolean consoleLike = d.contains("xbox") || d.contains("ps5") || d.contains("playstation")
                || d.contains("switch") || d.contains("console");
        if ((m & TOUCH) != 0 && ((m & KEYBOARD_MOUSE) == 0 || phoneLike)) {
            return LAYOUT_MOBILE_TOUCH;
        }
        if ((m & GAMEPAD) != 0 && ((m & KEYBOARD_MOUSE) == 0 || consoleLike)) {
            return LAYOUT_CONSOLE_GAMEPAD;
        }
        if ((m & TOUCH) != 0 && phoneLike) {
            return LAYOUT_MOBILE_TOUCH;
        }
        return LAYOUT_PC_KM;
    }
}
