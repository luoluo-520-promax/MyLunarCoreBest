package cn.itcast.demo.mylunarcore.assist.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualFrameTemporalAnalyzerTest {

    @Test
    void detectsMotionBetweenTwoFramesWithinWindow() throws InterruptedException {
        VisualFrameTemporalAnalyzer analyzer = new VisualFrameTemporalAnalyzer();
        byte[] frame1 = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5};
        byte[] frame2 = new byte[]{(byte) 0xFF, (byte) 0xD8, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        analyzer.analyzeAndStore(1L, frame1, 0f, 0f, 0f);
        Thread.sleep(50);
        VisualFrameTemporalAnalyzer.MotionHint hint = analyzer.analyzeAndStore(1L, frame2, 5f, 0f, 5f);
        assertTrue(hint.motionDetected());
    }

    @Test
    void noMotionOnFirstFrame() {
        VisualFrameTemporalAnalyzer analyzer = new VisualFrameTemporalAnalyzer();
        VisualFrameTemporalAnalyzer.MotionHint hint = analyzer.analyzeAndStore(2L,
                new byte[]{(byte) 0xFF, (byte) 0xD8, 1}, 0f, 0f, 0f);
        assertFalse(hint.motionDetected());
    }
}
