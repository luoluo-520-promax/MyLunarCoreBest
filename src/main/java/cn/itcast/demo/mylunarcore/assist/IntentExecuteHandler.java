package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 将自然语言意图转为游戏内指令：路径标记 / 自动寻路 / UI 深链，而不是纯文本外链。
 */
@Service
public class IntentExecuteHandler {

    public enum IntentKind {
        NAVIGATE_QUEST,
        PATHFIND_MATERIAL,
        UI_INTERACTION,
        NONE
    }

    public record ExecuteResult(IntentKind kind, String answer, String routeId, String title,
                                List<float[]> waypoints, String itemHint,
                                int targetPlaneId, int targetEntryId, float tx, float ty, float tz,
                                String uiAction, int uiRefId, int uiDelayMs) {
        public static ExecuteResult none() {
            return new ExecuteResult(IntentKind.NONE, "", "", "", List.of(), "", 0, 0, 0, 0, 0, "", 0, 0);
        }
    }

    private final ExplorePathAdvisor explorePathAdvisor;
    private final QuestGuidanceService questGuidanceService;

    public IntentExecuteHandler(ExplorePathAdvisor explorePathAdvisor,
                                QuestGuidanceService questGuidanceService) {
        this.explorePathAdvisor = explorePathAdvisor;
        this.questGuidanceService = questGuidanceService;
    }

    public Optional<ExecuteResult> tryExecute(long uid, String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String q = question.toLowerCase(Locale.ROOT);
        Optional<ExecuteResult> ui = tryUiInteraction(q);
        if (ui.isPresent()) {
            return ui;
        }
        if (looksLikeMaterial(q)) {
            return Optional.of(materialPath(q));
        }
        if (looksLikeQuestNav(q) || questGuidanceService.looksLost(question)) {
            return Optional.of(questNavigate(uid, question));
        }
        return Optional.empty();
    }

    /** 向客户端下发路径/寻路/UI 深链 Notify。 */
    public void pushToChannel(Channel channel, ExecuteResult result) {
        if (channel == null || !channel.isActive() || result == null || result.kind() == IntentKind.NONE) {
            return;
        }
        if (result.kind() == IntentKind.NAVIGATE_QUEST || result.kind() == IntentKind.UI_INTERACTION) {
            if (!result.waypoints().isEmpty() || (result.routeId() != null && !result.routeId().isBlank())) {
                SceneSystemProto.SceneNavigateScNotify.Builder b =
                        SceneSystemProto.SceneNavigateScNotify.newBuilder()
                                .setRouteId(nullToEmpty(result.routeId()))
                                .setTitle(nullToEmpty(result.title()))
                                .setLineStyle("glow_path")
                                .setDurationMs(120_000)
                                .setSource("ai_assist");
                for (float[] wp : result.waypoints()) {
                    if (wp == null || wp.length < 3) {
                        continue;
                    }
                    b.addWaypoints(SceneSystemProto.SceneVec3.newBuilder()
                            .setX(wp[0]).setY(wp[1]).setZ(wp[2]).build());
                }
                channel.writeAndFlush(new GamePacket(CmdIds.SCENE_NAVIGATE_SC_NOTIFY, b.build().toByteArray()));
            }
            if (result.kind() == IntentKind.UI_INTERACTION && result.uiAction() != null
                    && !result.uiAction().isBlank()) {
                AssistSystemProto.AssistDeepLinkScNotify deep =
                        AssistSystemProto.AssistDeepLinkScNotify.newBuilder()
                                .setAction(result.uiAction())
                                .setRefId(Math.max(0, result.uiRefId()))
                                .setTitle(nullToEmpty(result.title()))
                                .setSource("ai_assist_ui")
                                .setDelayMs(result.uiDelayMs() > 0 ? result.uiDelayMs() : 2500)
                                .setRouteId(nullToEmpty(result.routeId()))
                                .build();
                channel.writeAndFlush(new GamePacket(CmdIds.ASSIST_DEEP_LINK_SC_NOTIFY, deep.toByteArray()));
            }
            return;
        }
        if (result.kind() == IntentKind.PATHFIND_MATERIAL) {
            SceneSystemProto.AutoPathFindPushScNotify notify =
                    SceneSystemProto.AutoPathFindPushScNotify.newBuilder()
                            .setTargetLabel(nullToEmpty(result.title()))
                            .setTargetPlaneId(result.targetPlaneId())
                            .setTargetEntryId(result.targetEntryId())
                            .setTargetPos(SceneSystemProto.SceneVec3.newBuilder()
                                    .setX(result.tx()).setY(result.ty()).setZ(result.tz()).build())
                            .setItemHint(nullToEmpty(result.itemHint()))
                            .setSource("ai_assist")
                            .build();
            channel.writeAndFlush(new GamePacket(CmdIds.AUTO_PATH_FIND_PUSH_SC_NOTIFY, notify.toByteArray()));
        }
    }

    private Optional<ExecuteResult> tryUiInteraction(String q) {
        UiTarget target = resolveUiTarget(q);
        if (target == null) {
            return Optional.empty();
        }
        ExplorePathAdvisor.PathAdvice advice = explorePathAdvisor.advise(0, 1);
        List<float[]> wps = new ArrayList<>();
        String routeId = "ui-" + target.action().toLowerCase(Locale.ROOT);
        String title = target.title();
        if (advice.waypoints() != null) {
            for (AssistFeatureContent.Waypoint wp : advice.waypoints()) {
                if (wp != null) {
                    wps.add(new float[]{wp.x(), wp.y(), wp.z()});
                }
            }
            if (advice.routeId() != null && !advice.routeId().isBlank()) {
                routeId = advice.routeId() + "-" + target.action();
            }
            if (advice.title() != null && !advice.title().isBlank()) {
                title = advice.title() + " → " + target.title();
            }
        }
        if (wps.isEmpty()) {
            wps.add(new float[]{target.x(), 0f, target.z()});
        }
        String answer = "已为你标记前往「" + target.title() + "」的路线，到达后将自动打开界面。";
        return Optional.of(new ExecuteResult(IntentKind.UI_INTERACTION, answer, routeId, title,
                wps, "", 1, 1, target.x(), 0f, target.z(),
                target.action(), target.refId(), 2500));
    }

    private static UiTarget resolveUiTarget(String q) {
        if (q.contains("合成") || q.contains("合成台")) {
            return new UiTarget("OPEN_SYNTH", "合成台", 0, 32f, 20f);
        }
        if (q.contains("强化光锥") || q.contains("光锥强化") || (q.contains("强化") && q.contains("光锥"))) {
            return new UiTarget("OPEN_LIGHT_CONE", "光锥强化", 0, 30f, 18f);
        }
        if (q.contains("强化遗器") || q.contains("遗器") || (q.contains("强化") && !q.contains("光锥"))) {
            return new UiTarget("OPEN_RELIC_UPGRADE", "遗器强化", 0, 28f, 16f);
        }
        if (q.contains("抽卡") || q.contains("跃迁") || q.contains("扭蛋")) {
            return new UiTarget("OPEN_GACHA", "跃迁", 0, 22f, 12f);
        }
        if (q.contains("领取") || q.contains("领奖") || q.contains("邮件奖励")) {
            return new UiTarget("OPEN_CLAIM", "奖励领取", 0, 18f, 10f);
        }
        return null;
    }

    private ExecuteResult questNavigate(long uid, String question) {
        ExplorePathAdvisor.PathAdvice advice = explorePathAdvisor.advise(0, 1);
        List<float[]> wps = new ArrayList<>();
        if (advice.waypoints() != null) {
            for (AssistFeatureContent.Waypoint wp : advice.waypoints()) {
                if (wp != null) {
                    wps.add(new float[]{wp.x(), wp.y(), wp.z()});
                }
            }
        }
        if (wps.isEmpty()) {
            QuestGuidanceService.Guidance g = questGuidanceService.guide(uid, question);
            wps.add(new float[]{10f, 0f, 10f});
            return new ExecuteResult(IntentKind.NAVIGATE_QUEST,
                    "已在地图标记任务目标引路线：" + g.title(),
                    "quest-" + g.questId(), g.title(), wps, "", 0, 0, 0, 0, 0, "", 0, 0);
        }
        return new ExecuteResult(IntentKind.NAVIGATE_QUEST, advice.summary(),
                advice.routeId(), advice.title(), wps, "", 0, 0, 0, 0, 0, "", 0, 0);
    }

    private ExecuteResult materialPath(String q) {
        String item = extractMaterialName(q);
        float x = 24f + (Math.floorMod(item.hashCode(), 40));
        float z = 12f + (Math.floorMod(item.hashCode() / 7, 40));
        return new ExecuteResult(IntentKind.PATHFIND_MATERIAL,
                "已为你标记「" + item + "」掉落点入口，自动寻路已开启。",
                "mat-" + Math.floorMod(item.hashCode(), 10000),
                item + "掉落点",
                List.of(),
                item,
                1, 1, x, 0f, z, "", 0, 0);
    }

    private static boolean looksLikeQuestNav(String q) {
        return q.contains("任务目标") || q.contains("去哪") || q.contains("在哪")
                || q.contains("怎么走") || q.contains("导航") || q.contains("引路")
                || q.contains("当前任务");
    }

    private static boolean looksLikeMaterial(String q) {
        return (q.contains("材料") || q.contains("缺") || q.contains("刷"))
                && (q.contains("在哪") || q.contains("哪里") || q.contains("掉落") || q.contains("获取"));
    }

    private static String extractMaterialName(String q) {
        String cleaned = q.replaceAll("[？?！!。.\\s]", "");
        int idx = cleaned.indexOf("材料");
        if (idx > 0) {
            String before = cleaned.substring(Math.max(0, idx - 6), idx);
            if (!before.isBlank()) {
                return before;
            }
        }
        return "养成材料";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record UiTarget(String action, String title, int refId, float x, float z) {
    }
}
