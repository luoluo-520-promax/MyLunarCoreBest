package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import com.google.protobuf.ByteString;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景感知主动教练：连败、主线卡关、体力满溢、BOSS 门口徘徊等事件驱动推送。
 */
@Service
public class AssistProactiveCoachService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistProactiveCoachService.class);

    private final LunarCoreProperties properties;
    private final GameSessionManager sessionManager;
    private final PlayerCoachApplicationService coachApplicationService;
    private final QuestProgressApplicationService questProgressApplicationService;
    private final AssistFeatureContentRepository featureContentRepository;
    private final ObjectProvider<AssistPersonaService> personaProvider;
    private final ObjectProvider<AssistTtsService> ttsProvider;
    private final ObjectProvider<AssistLongTermMemoryService> longTermMemoryProvider;
    private final ConcurrentHashMap<Long, AtomicInteger> failStreak = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPushMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LingerState> lingerByUid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastMoveAtMs = new ConcurrentHashMap<>();

    public AssistProactiveCoachService(LunarCoreProperties properties,
                                       GameSessionManager sessionManager,
                                       PlayerCoachApplicationService coachApplicationService,
                                       QuestProgressApplicationService questProgressApplicationService,
                                       AssistFeatureContentRepository featureContentRepository,
                                       ObjectProvider<AssistPersonaService> personaProvider,
                                       ObjectProvider<AssistTtsService> ttsProvider,
                                       ObjectProvider<AssistLongTermMemoryService> longTermMemoryProvider) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.coachApplicationService = coachApplicationService;
        this.questProgressApplicationService = questProgressApplicationService;
        this.featureContentRepository = featureContentRepository;
        this.personaProvider = personaProvider;
        this.ttsProvider = ttsProvider;
        this.longTermMemoryProvider = longTermMemoryProvider;
    }

    @EventListener
    public void onBattleEnded(BattleEndedEvent event) {
        if (!enabled() || event == null || event.playerId() <= 0) {
            return;
        }
        long uid = event.playerId();
        if (event.endStatus() == 1) {
            failStreak.remove(uid);
            return;
        }
        int streak = failStreak.computeIfAbsent(uid, k -> new AtomicInteger()).incrementAndGet();
        int need = Math.max(2, properties.getAiAssist().getProactiveFailStreak());
        if (streak < need) {
            return;
        }
        failStreak.put(uid, new AtomicInteger(0));
        AssistLongTermMemoryService ltm = longTermMemoryProvider == null ? null : longTermMemoryProvider.getIfAvailable();
        if (ltm != null) {
            ltm.record(uid, "battle_fail", "连败愤怒",
                    "连续战斗失败 " + streak + " 次，可能需要配队或战术调整", 0.85);
        }
        double interrupt = interruptScore(uid, false);
        if (interrupt > properties.getAiAssist().getProactiveInterruptScoreMax()) {
            return;
        }
        boolean healing = ltm != null && ltm.shouldUseHealingPersona(uid);
        String message = healing
                ? "别灰心，我注意到你最近打得不太顺——要不要试试我上次建议的那套配队？"
                : "检测到你连续战斗受挫，需要我帮你配队吗？";
        pushIfAllowed(uid, "battle_fail", message,
                List.of(new CoachHint("proactive_fail", "battle", 95,
                        "连败辅导", "建议打开阵容推荐或任务引导", "OPEN_LINEUP", 0)),
                "proactive_battle_fail");
    }

    /** 登录/心跳侧可调用：检查主线卡关与体力满。 */
    public void evaluateIdleTriggers(long uid) {
        if (!enabled() || uid <= 0) {
            return;
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getPlayerData() == null || session.getPlayerData().getPlayer() == null) {
            return;
        }
        if (interruptScore(uid, true) > properties.getAiAssist().getProactiveInterruptScoreMax()) {
            return;
        }
        int playerId = (int) (uid & 0xffffffffL);
        List<QuestProgressEntity> quests = questProgressApplicationService.list(playerId);
        long inProgress = quests == null ? 0 : quests.stream()
                .filter(q -> q != null && q.getStatus() == CoachRuleEngine.QUEST_STATUS_IN_PROGRESS)
                .count();
        if (inProgress > 0) {
            pushIfAllowed(uid, "quest_stuck",
                    "主线/任务仍在进行中，需要我帮你拆解下一步吗？",
                    List.of(new CoachHint("proactive_quest", "quest", 90,
                            "任务引导", "打开渐进任务提示", "OPEN_QUEST", 0)),
                    "proactive_quest");
        }
        if (properties.getAiAssist().isProactiveStaminaFullEnabled()) {
            int stamina = session.getPlayerData().getPlayer().getStamina();
            if (stamina >= 240) {
                pushIfAllowed(uid, "stamina_full",
                        "体力接近满溢，建议尽快消耗以免浪费回复。",
                        List.of(new CoachHint("proactive_stamina", "growth", 80,
                                "体力提醒", "打开日常/副本界面", "OPEN_ACTIVITY", 0)),
                        "proactive_stamina");
            }
        }
    }

    /**
     * 场景移动钩子：在 BOSS/目标门口来回徘徊超过阈值时主动推送配队建议。
     */
    public void onPlayerMove(long uid, int planeId, float x, float y, float z) {
        if (!enabled() || uid <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        lastMoveAtMs.put(uid, now);
        AssistFeatureContent.PoiLore bossPoi = findBossishPoi(planeId, x, y, z);
        if (bossPoi == null) {
            lingerByUid.remove(uid);
            return;
        }
        LingerState state = lingerByUid.compute(uid, (k, prev) -> {
            if (prev == null || !bossPoi.poiId().equals(prev.poiId)) {
                return new LingerState(bossPoi.poiId(), now, x, z, 0);
            }
            float dx = x - prev.lastX;
            float dz = z - prev.lastZ;
            float moved = (float) Math.sqrt(dx * dx + dz * dz);
            int oscillate = prev.oscillateCount;
            if (moved > 1.5f && moved < 18f) {
                oscillate++;
            }
            return new LingerState(prev.poiId, prev.sinceMs, x, z, oscillate);
        });
        long lingerSec = Math.max(1, (now - state.sinceMs) / 1000L);
        int needSec = Math.max(10, properties.getAiAssist().getProactiveBossLingerSeconds());
        if (lingerSec < needSec || state.oscillateCount < 2) {
            return;
        }
        if (interruptScore(uid, false) > properties.getAiAssist().getProactiveInterruptScoreMax()) {
            return;
        }
        lingerByUid.remove(uid);
        pushIfAllowed(uid, "boss_linger",
                "你在「" + bossPoi.title() + "」门口犹豫了一会儿，需要我帮你配队吗？",
                List.of(new CoachHint("proactive_boss", "battle", 96,
                        "开战前辅导", "打开阵容推荐", "OPEN_LINEUP", 0)),
                "proactive_scene_stay");
    }

    /**
     * 打扰系数：近期高频移动 / 战斗中 → 高打扰，避免挡视线。
     */
    public double interruptScore(long uid, boolean idleContext) {
        long now = System.currentTimeMillis();
        Long lastMove = lastMoveAtMs.get(uid);
        double score = 0.15;
        if (lastMove != null) {
            long idleMs = now - lastMove;
            if (idleMs < 3_000L) {
                score += 0.45;
            } else if (idleMs < 10_000L) {
                score += 0.20;
            }
        }
        AssistLongTermMemoryService ltm = longTermMemoryProvider == null ? null : longTermMemoryProvider.getIfAvailable();
        if (ltm != null && ltm.shouldUseHealingPersona(uid)) {
            score *= 0.70;
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session != null && session.getSessionState() == PlayerSessionState.BATTLE) {
            score += 0.40;
        }
        if (idleContext) {
            score *= 0.85;
        }
        return Math.min(1.0, score);
    }

    private AssistFeatureContent.PoiLore findBossishPoi(int planeId, float x, float y, float z) {
        AssistFeatureContent content = featureContentRepository == null ? null : featureContentRepository.current();
        if (content == null || content.pois() == null) {
            return null;
        }
        AssistFeatureContent.PoiLore best = null;
        float bestDist = Float.MAX_VALUE;
        for (AssistFeatureContent.PoiLore poi : content.pois()) {
            if (poi == null) {
                continue;
            }
            if (poi.sceneId() > 0 && planeId > 0 && poi.sceneId() != planeId) {
                continue;
            }
            boolean bossish = false;
            if (poi.tags() != null) {
                for (String t : poi.tags()) {
                    if (t == null) {
                        continue;
                    }
                    String low = t.toLowerCase(Locale.ROOT);
                    if (low.contains("boss") || low.contains("elite") || low.contains("objective")
                            || low.contains("ruins") || "objective".equals(poi.poiId())) {
                        bossish = true;
                        break;
                    }
                }
            }
            String title = poi.title() == null ? "" : poi.title();
            if (title.contains("目标") || title.contains("BOSS") || title.contains("Boss") || title.contains("遗迹")) {
                bossish = true;
            }
            if (!bossish) {
                continue;
            }
            float dx = poi.centerX() - x;
            float dz = poi.centerZ() - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float radius = Math.max(poi.radius(), 8f) * 1.8f;
            if (d <= radius && d < bestDist) {
                bestDist = d;
                best = poi;
            }
        }
        return best;
    }

    private void pushIfAllowed(long uid, String category, String message, List<CoachHint> hints, String hintType) {
        String coolKey = uid + ":" + category;
        long now = System.currentTimeMillis();
        long coolMs = Math.max(60, properties.getAiAssist().getProactiveCooldownSeconds()) * 1000L;
        Long last = lastPushMs.get(coolKey);
        if (last != null && now - last < coolMs) {
            return;
        }
        lastPushMs.put(coolKey, now);
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null || session.getChannel() == null || !session.getChannel().isActive()) {
            return;
        }
        List<CoachHint> related = hints;
        if (related == null || related.isEmpty()) {
            related = coachApplicationService.suggest(uid, 1);
        }
        Channel channel = session.getChannel();
        String answer = message;
        AssistPersonaService persona = personaProvider == null ? null : personaProvider.getIfAvailable();
        if (persona != null) {
            answer = persona.wrapAnswer(uid, answer);
        } else if (properties.getAiAssist().isComplianceDisclaimerEnabled()) {
            answer = answer + " " + properties.getAiAssist().getComplianceDisclaimer();
        }
        AssistSystemProto.AiHintNotify.Builder builder = AssistSystemProto.AiHintNotify.newBuilder()
                .setRequestId("proactive-" + UUID.randomUUID())
                .setAnswer(answer)
                .setSource("proactive-" + category)
                .setHintType(hintType == null ? "coach" : hintType)
                .setForbidExternalBrowser(true);
        if (persona != null) {
            builder.setVoicePersonaId(persona.personaId(uid));
        }
        AssistTtsService tts = ttsProvider == null ? null : ttsProvider.getIfAvailable();
        if (tts != null && persona != null) {
            AssistTtsService.TtsClip clip = tts.synthesize(uid, answer, persona.voiceId(uid));
            if (clip.hasAudio()) {
                builder.setAudioChunk(ByteString.copyFrom(clip.audioChunk())).setAudioFormat(clip.format());
            }
        }
        for (CoachHint h : related) {
            builder.addRelatedHints(AssistSystemProto.CoachHint.newBuilder()
                    .setTipId(nullToEmpty(h.tipId()))
                    .setCategory(nullToEmpty(h.category()))
                    .setPriority(h.priority())
                    .setTitle(nullToEmpty(h.title()))
                    .setMessage(nullToEmpty(h.message()))
                    .setAction(nullToEmpty(h.action()))
                    .setRefId(h.refId())
                    .build());
        }
        channel.writeAndFlush(new GamePacket(CmdIds.AI_HINT_SC_NOTIFY, builder.build().toByteArray()));
        log.info("proactive coach pushed uidHash={} category={} interrupt={}",
                Integer.toHexString(Long.hashCode(uid)), category, interruptScore(uid, false));
    }

    private boolean enabled() {
        return properties.getAiAssist().isEnabled() && properties.getAiAssist().isProactiveCoachEnabled();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static final class LingerState {
        final String poiId;
        final long sinceMs;
        final float lastX;
        final float lastZ;
        final int oscillateCount;

        LingerState(String poiId, long sinceMs, float lastX, float lastZ, int oscillateCount) {
            this.poiId = poiId;
            this.sinceMs = sinceMs;
            this.lastX = lastX;
            this.lastZ = lastZ;
            this.oscillateCount = oscillateCount;
        }
    }
}
