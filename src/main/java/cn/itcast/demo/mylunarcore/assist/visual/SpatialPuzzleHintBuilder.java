package cn.itcast.demo.mylunarcore.assist.visual;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 箱庭机关多步骤空间锚点链：为解谜场景生成 1→2→3 顺序的世界坐标与方位描述。
 */
@Component
public class SpatialPuzzleHintBuilder {

    public record AnchorStep(int sequenceStep, float worldX, float worldY, float worldZ,
                             float screenX, float screenY, String directionHint, String label) {
    }

    public record PuzzleChain(String chainId, String title, List<AnchorStep> steps, String narrative) {
    }

    public PuzzleChain build(int planeId, float x, float y, float z,
                             AssistFeatureContent.PoiLore poi, VisualFrameTemporalAnalyzer.MotionHint motion) {
        String baseHint = poi != null && poi.puzzleHint() != null && !poi.puzzleHint().isBlank()
                ? poi.puzzleHint()
                : "按顺序激活三个机关元素。";
        String title = poi != null && poi.title() != null ? poi.title() + "解谜" : "机关解谜";
        float cx = poi != null ? poi.centerX() : x;
        float cy = poi != null ? poi.centerY() : y;
        float cz = poi != null ? poi.centerZ() : z;

        List<AnchorStep> steps = new ArrayList<>();
        steps.add(new AnchorStep(1, cx - 4f, cy, cz - 3f, 0.32f, 0.58f,
                "第一个在左边花坛后", "① 花坛机关"));
        steps.add(new AnchorStep(2, cx, cy + 1.5f, cz, 0.50f, 0.42f,
                "第二个在中央石柱顶部", "② 石柱开关"));
        steps.add(new AnchorStep(3, cx + 5f, cy, cz + 4f, 0.68f, 0.55f,
                motion != null && motion.motionDetected() ? "第三个在移动的浮台上" : "第三个在右侧平台",
                "③ 终点平台"));

        String narrative = "需要按顺序激活 " + steps.size() + " 个元素：" + baseHint;
        if (motion != null && motion.motionDetected() && motion.hint() != null && !motion.hint().isBlank()) {
            narrative = narrative + " " + motion.hint();
        }
        String chainId = "puzzle-" + planeId + "-" + (poi != null ? poi.poiId() : "generic");
        return new PuzzleChain(chainId, title, steps, narrative);
    }

    public boolean looksLikeMechanismQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("机关") || q.contains("解谜") || q.contains("怎么开")
                || q.contains("怎么解") || q.contains("顺序") || q.contains("puzzle");
    }
}
