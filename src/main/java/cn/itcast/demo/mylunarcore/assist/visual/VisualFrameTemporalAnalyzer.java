package cn.itcast.demo.mylunarcore.assist.visual;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VLM 时序帧对比：缓存玩家上一帧截图指纹，对比前后约 2 秒差异以识别动态机关（浮台移动等）。
 */
@Component
public class VisualFrameTemporalAnalyzer {

    private static final long FRAME_WINDOW_MS = 2_000L;
    private static final double MOTION_THRESHOLD = 0.08;

    private final ConcurrentHashMap<Long, FrameSnapshot> lastByUid = new ConcurrentHashMap<>();

    public record MotionHint(boolean motionDetected, double deltaScore, String hint) {
        public static MotionHint none() {
            return new MotionHint(false, 0.0, "");
        }
    }

    public record FrameSnapshot(long capturedAtMs, int jpegSize, String fingerprint) {
    }

    /**
     * 记录当前帧并返回与上一帧（2 秒窗口内）的运动差异提示。
     */
    public MotionHint analyzeAndStore(long uid, byte[] jpeg, float posX, float posY, float posZ) {
        if (uid <= 0 || jpeg == null || jpeg.length == 0) {
            return MotionHint.none();
        }
        long now = System.currentTimeMillis();
        String fp = fingerprint(jpeg, posX, posY, posZ);
        FrameSnapshot prev = lastByUid.get(uid);
        lastByUid.put(uid, new FrameSnapshot(now, jpeg.length, fp));

        if (prev == null || now - prev.capturedAtMs() > FRAME_WINDOW_MS) {
            return MotionHint.none();
        }
        double sizeDelta = prev.jpegSize() <= 0 ? 0
                : Math.abs(jpeg.length - prev.jpegSize()) * 1.0 / prev.jpegSize();
        double fpDelta = fingerprintDelta(prev.fingerprint(), fp);
        double score = Math.max(sizeDelta, fpDelta);
        if (score < MOTION_THRESHOLD) {
            return MotionHint.none();
        }
        String hint = score >= 0.25
                ? "画面中有明显位移（可能是移动浮台或旋转机关），请按标号顺序踩点。"
                : "画面边缘有轻微变化，留意可互动机关是否在循环移动。";
        return new MotionHint(true, score, hint);
    }

    private static String fingerprint(byte[] jpeg, float x, float y, float z) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int step = Math.max(1, jpeg.length / 64);
            for (int i = 0; i < jpeg.length; i += step) {
                md.update((byte) jpeg[i]);
            }
            md.update(String.valueOf((int) x).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf((int) y).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf((int) z).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return Integer.toHexString(jpeg.length);
        }
    }

    private static double fingerprintDelta(String a, String b) {
        if (a == null || b == null || a.length() != b.length() || a.isEmpty()) {
            return a != null && b != null && !a.equals(b) ? 0.5 : 0.0;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                diff++;
            }
        }
        return diff * 1.0 / a.length();
    }
}
