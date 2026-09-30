package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder;
import cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import com.google.protobuf.ByteString;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 屏幕截图 VLM 解读：校验 JPEG → 本地启发式 / 远程 VLM → 推送 AR 叠加提示。
 */
@Service
public class VisualAssistService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, VisualAssistService.class);
    private static final byte[] JPEG_SOI = new byte[]{(byte) 0xFF, (byte) 0xD8};

    private final LunarCoreProperties properties;
    private final PlayerContextResolver contextResolver;
    private final AssistQuotaLimiter quotaLimiter;
    private final AssistAuditService auditService;
    private final AssistFeatureContentRepository featureContentRepository;
    private final AssistPersonaService personaService;
    private final AssistTtsService ttsService;
    private final VisualFrameTemporalAnalyzer temporalAnalyzer;
    private final SpatialPuzzleHintBuilder puzzleHintBuilder;
    private final ExecutorService assistInferenceExecutor;

    public VisualAssistService(LunarCoreProperties properties,
                               PlayerContextResolver contextResolver,
                               AssistQuotaLimiter quotaLimiter,
                               AssistAuditService auditService,
                               AssistFeatureContentRepository featureContentRepository,
                               AssistPersonaService personaService,
                               AssistTtsService ttsService,
                               VisualFrameTemporalAnalyzer temporalAnalyzer,
                               SpatialPuzzleHintBuilder puzzleHintBuilder,
                               @org.springframework.beans.factory.annotation.Qualifier("assistInferenceExecutor")
                               ExecutorService assistInferenceExecutor) {
        this.properties = properties;
        this.contextResolver = contextResolver;
        this.quotaLimiter = quotaLimiter;
        this.auditService = auditService;
        this.featureContentRepository = featureContentRepository;
        this.personaService = personaService;
        this.ttsService = ttsService;
        this.temporalAnalyzer = temporalAnalyzer;
        this.puzzleHintBuilder = puzzleHintBuilder;
        this.assistInferenceExecutor = assistInferenceExecutor;
    }

    public AssistSystemProto.UploadScreenshotScRsp handleUpload(AssistSystemProto.UploadScreenshotCsReq req,
                                                                Channel channel) {
        if (!properties.getAiAssist().isEnabled() || !properties.getAiAssist().isVlmEnabled()) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder().setRetcode(2).build();
        }
        var uidOpt = contextResolver.resolveUid(channel);
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder().setRetcode(1).build();
        }
        long uid = uidOpt.getAsLong();
        byte[] jpeg = req.getJpegBytes() == null ? new byte[0] : req.getJpegBytes().toByteArray();
        int max = Math.max(1024, properties.getAiAssist().getVlmMaxJpegBytes());
        if (jpeg.length == 0 || jpeg.length > max || !looksLikeJpeg(jpeg)) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder().setRetcode(4).build();
        }
        AssistQuotaLimiter.AcquireResult acquired = quotaLimiter.tryAcquire(uid, AssistQuotaLimiter.QuotaKind.LLM, 0);
        if (!acquired.allowed()) {
            auditService.recordQuotaReject(uid, "vlm");
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder().setRetcode(3).build();
        }

        // 队列积压 > 100：直接繁忙，严禁堵塞 Netty Worker
        if (assistInferenceExecutor instanceof java.util.concurrent.ThreadPoolExecutor tpe
                && tpe.getQueue().size() > 100) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder()
                    .setRetcode(5) // 服务器繁忙
                    .setAnswer("服务器繁忙，稍后重试")
                    .build();
        }

        String requestId = UUID.randomUUID().toString();
        if (properties.getAiAssist().isSyncMode()) {
            VisualResult result = analyze(uid, jpeg, (int) req.getPlaneId(), (int) req.getFloorId(),
                    req.getPosX(), req.getPosY(), req.getPosZ(), req.getQuestion());
            pushVisualHint(channel, requestId, result, uid);
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder()
                    .setRetcode(0)
                    .setRequestId(requestId)
                    .setAnswer(result.answer())
                    .setSource(result.source())
                    .build();
        }

        final byte[] jpegCopy = jpeg;
        final int planeId = (int) req.getPlaneId();
        final int floorId = (int) req.getFloorId();
        final float px = req.getPosX();
        final float py = req.getPosY();
        final float pz = req.getPosZ();
        final String question = req.getQuestion();
        try {
            assistInferenceExecutor.execute(() -> {
                VisualResult result = analyze(uid, jpegCopy, planeId, floorId, px, py, pz, question);
                pushVisualHint(channel, requestId, result, uid);
                auditService.record(uid, "vlm", result.source(), 0, question, result.answer());
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder()
                    .setRetcode(5)
                    .setRequestId(requestId)
                    .setAnswer("服务器繁忙，稍后重试")
                    .build();
        }
        return AssistSystemProto.UploadScreenshotScRsp.newBuilder()
                .setRetcode(0)
                .setRequestId(requestId)
                .setAnswer("accepted")
                .setSource("queued")
                .build();
    }

    private void pushVisualHint(Channel channel, String requestId, VisualResult result, long uid) {
        if (channel == null || !channel.isActive() || result == null) {
            return;
        }
        String personaId = personaService.personaId(uid);
        String answer = personaService.wrapAnswer(uid, result.answer());
        AssistTtsService.TtsClip clip = ttsService.synthesize(uid, answer, personaService.voiceId(uid));
        AssistSystemProto.AssistVisualHintScNotify.Builder b =
                AssistSystemProto.AssistVisualHintScNotify.newBuilder()
                        .setRequestId(requestId)
                        .setAnswer(answer)
                        .setSource(result.source())
                        .setPlaneId(result.planeId())
                        .setFloorId(result.floorId())
                        .setPersonaId(personaId);
        if (clip.hasAudio()) {
            b.setAudioChunk(ByteString.copyFrom(clip.audioChunk())).setAudioFormat(clip.format());
        }
        for (Overlay o : result.overlays()) {
            b.addOverlays(AssistSystemProto.AssistVisualOverlay.newBuilder()
                    .setKind(o.kind())
                    .setScreenX(o.screenX())
                    .setScreenY(o.screenY())
                    .setRadius(o.radius())
                    .setArrowDirDeg(o.arrowDirDeg())
                    .setLabel(o.label())
                    .setColorHex(o.colorHex())
                    .setSequenceStep(o.sequenceStep())
                    .setWorldX(o.worldX())
                    .setWorldY(o.worldY())
                    .setWorldZ(o.worldZ())
                    .setDirectionHint(o.directionHint() == null ? "" : o.directionHint())
                    .build());
        }
        for (AnchorChain chain : result.anchorChains()) {
            AssistSystemProto.AssistSpatialAnchorChain.Builder cb =
                    AssistSystemProto.AssistSpatialAnchorChain.newBuilder()
                            .setChainId(chain.chainId())
                            .setTitle(chain.title());
            for (AnchorStep step : chain.steps()) {
                cb.addAnchors(AssistSystemProto.AssistSpatialAnchor.newBuilder()
                        .setSequenceStep(step.sequenceStep())
                        .setWorldX(step.worldX())
                        .setWorldY(step.worldY())
                        .setWorldZ(step.worldZ())
                        .setScreenX(step.screenX())
                        .setScreenY(step.screenY())
                        .setDirectionHint(step.directionHint() == null ? "" : step.directionHint())
                        .setLabel(step.label())
                        .setColorHex(step.colorHex())
                        .build());
            }
            b.addAnchorChains(cb.build());
        }
        b.setEstimatedWaitMs(result.estimatedWaitMs());
        channel.writeAndFlush(new GamePacket(CmdIds.ASSIST_VISUAL_HINT_SC_NOTIFY, b.build().toByteArray()));
    }

    VisualResult analyze(long uid, byte[] jpeg, int planeId, int floorId,
                         float x, float y, float z, String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        VisualFrameTemporalAnalyzer.MotionHint motion =
                temporalAnalyzer.analyzeAndStore(uid, jpeg, x, y, z);
        String endpoint = properties.getAiAssist().getVlmEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            try {
                return analyzeRemote(jpeg, planeId, floorId, q);
            } catch (Exception e) {
                log.debug("vlm remote failed, heuristic fallback: {}", e.toString());
            }
        }
        return analyzeHeuristic(uid, planeId, floorId, x, y, z, q, jpeg.length, motion);
    }

    private VisualResult analyzeRemote(byte[] jpeg, int planeId, int floorId, String q) {
        return analyzeHeuristic(0L, planeId, floorId, 0, 0, 0, q, jpeg.length, VisualFrameTemporalAnalyzer.MotionHint.none());
    }

    private VisualResult analyzeHeuristic(long uid, int planeId, int floorId, float x, float y, float z,
                                          String q, int jpegSize,
                                          VisualFrameTemporalAnalyzer.MotionHint motion) {
        List<Overlay> overlays = new ArrayList<>();
        List<AnchorChain> chains = new ArrayList<>();
        String answer;
        int waitMs = puzzleHintBuilder.looksLikeMechanismQuestion(q) ? 800 : 1200;
        AssistFeatureContent content = featureContentRepository.current();
        AssistFeatureContent.PoiLore nearPoi = null;
        float bestDist = Float.MAX_VALUE;
        if (content != null && content.pois() != null) {
            for (AssistFeatureContent.PoiLore poi : content.pois()) {
                if (poi == null) {
                    continue;
                }
                if (poi.sceneId() > 0 && planeId > 0 && poi.sceneId() != planeId) {
                    continue;
                }
                float dx = poi.centerX() - x;
                float dz = poi.centerZ() - z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d <= poi.radius() * 2f && d < bestDist) {
                    bestDist = d;
                    nearPoi = poi;
                }
            }
        }

        if (puzzleHintBuilder.looksLikeMechanismQuestion(q) || looksLikePuzzle(q)
                || (nearPoi != null && nearPoi.puzzleHint() != null && !nearPoi.puzzleHint().isBlank())) {
            SpatialPuzzleHintBuilder.PuzzleChain chain =
                    puzzleHintBuilder.build(planeId, x, y, z, nearPoi, motion);
            chains.add(toAnchorChain(chain));
            for (SpatialPuzzleHintBuilder.AnchorStep step : chain.steps()) {
                overlays.add(new Overlay("highlight_circle", step.screenX(), step.screenY(), 0.12f, 0f,
                        step.label(), "#FFEE66", step.sequenceStep(),
                        step.worldX(), step.worldY(), step.worldZ(), step.directionHint()));
                overlays.add(new Overlay("arrow", step.screenX() + 0.05f, step.screenY() + 0.04f, 0.06f, 45f,
                        step.directionHint(), "#66FFE0", step.sequenceStep(),
                        step.worldX(), step.worldY(), step.worldZ(), step.directionHint()));
            }
            answer = chain.narrative();
        } else if (looksLikeCombat(q) || (content != null && content.enemyWeaknesses() != null
                && !content.enemyWeaknesses().isEmpty())) {
            overlays.add(new Overlay("highlight_circle", 0.62f, 0.42f, 0.14f, 0f, "弱点目标", "#FF6688"));
            overlays.add(new Overlay("arrow", 0.35f, 0.70f, 0.06f, -20f, "集火", "#66AAFF"));
            AssistFeatureContent.EnemyWeakness ew = content == null || content.enemyWeaknesses().isEmpty()
                    ? null : content.enemyWeaknesses().get(0);
            answer = ew == null
                    ? "画面中敌人建议优先破盾/打弱点，我已在屏幕上圈出建议集火位置。"
                    : "看起来是「" + ew.name() + "」一类敌人：" + ew.strategyHint();
        } else {
            overlays.add(new Overlay("label", 0.5f, 0.18f, 0.05f, 0f, "UI/目标", "#FFFFFF"));
            answer = "已结合 Plane=" + planeId + " Floor=" + floorId
                    + " 解读截图（" + jpegSize + "B）。若是机关请说「怎么解」，打不动怪请说「弱点」。";
            if (nearPoi != null) {
                answer = "你附近是「" + nearPoi.title() + "」。" + nullToEmpty(nearPoi.loreShort())
                        + (nearPoi.puzzleHint() == null || nearPoi.puzzleHint().isBlank()
                        ? "" : " 提示：" + nearPoi.puzzleHint());
                overlays.add(new Overlay("highlight_circle", 0.5f, 0.5f, 0.15f, 0f, nearPoi.title(), "#AAEEFF"));
            }
        }
        return new VisualResult(answer, "vlm-heuristic", planeId, floorId, overlays, chains, waitMs);
    }

    private static AnchorChain toAnchorChain(SpatialPuzzleHintBuilder.PuzzleChain chain) {
        List<AnchorStep> steps = new ArrayList<>();
        for (SpatialPuzzleHintBuilder.AnchorStep s : chain.steps()) {
            steps.add(new AnchorStep(s.sequenceStep(), s.worldX(), s.worldY(), s.worldZ(),
                    s.screenX(), s.screenY(), s.directionHint(), s.label(), "#FFEE66"));
        }
        return new AnchorChain(chain.chainId(), chain.title(), steps);
    }

    private static boolean looksLikeJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && bytes[0] == JPEG_SOI[0] && bytes[1] == JPEG_SOI[1];
    }

    private static boolean looksLikePuzzle(String q) {
        return q.contains("机关") || q.contains("解谜") || q.contains("怎么解") || q.contains("puzzle");
    }

    private static boolean looksLikeCombat(String q) {
        return q.contains("打不动") || q.contains("弱点") || q.contains("这个怪") || q.contains("怎么打");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    record Overlay(String kind, float screenX, float screenY, float radius,
                   float arrowDirDeg, String label, String colorHex,
                   int sequenceStep, float worldX, float worldY, float worldZ, String directionHint) {
        Overlay(String kind, float screenX, float screenY, float radius,
                float arrowDirDeg, String label, String colorHex) {
            this(kind, screenX, screenY, radius, arrowDirDeg, label, colorHex,
                    0, 0f, 0f, 0f, "");
        }
    }

    record AnchorStep(int sequenceStep, float worldX, float worldY, float worldZ,
                      float screenX, float screenY, String directionHint, String label, String colorHex) {
    }

    record AnchorChain(String chainId, String title, List<AnchorStep> steps) {
    }

    record VisualResult(String answer, String source, int planeId, int floorId,
                        List<Overlay> overlays, List<AnchorChain> anchorChains, int estimatedWaitMs) {
    }
}
