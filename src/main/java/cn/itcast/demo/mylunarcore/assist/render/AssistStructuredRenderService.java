package cn.itcast.demo.mylunarcore.assist.render;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 结构化渲染：TEAM_CARD / MATERIAL_TREE 等 JSON 载荷，由客户端本地渲染精美卡牌。
 */
@Component
public class AssistStructuredRenderService {

    private static final Pattern TEAM_QUESTION = Pattern.compile(
            "能组吗|配队|阵容|组队|三个角色|四人|怎么配");
    private static final Pattern SIM_UNIVERSE = Pattern.compile("模拟宇宙|祝福|肉鸽");
    private static final Pattern MATERIAL_TREE = Pattern.compile("材料树|养成路线|升级路线");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record RenderPayload(String renderType, String payloadJson, String textFallback) {
        public boolean hasRender() {
            return renderType != null && !renderType.isBlank()
                    && payloadJson != null && !payloadJson.isBlank();
        }
    }

    public OptionalRender tryRender(String question, String scene, PlayerData playerData) {
        String q = question == null ? "" : question;
        if (TEAM_QUESTION.matcher(q).find()) {
            return buildTeamCard(playerData, q);
        }
        if (SIM_UNIVERSE.matcher(q).find()) {
            return buildSimUniverseBlessing(playerData);
        }
        if (MATERIAL_TREE.matcher(q).find() || "growth".equalsIgnoreCase(scene)) {
            return buildMaterialTree(playerData);
        }
        return OptionalRender.none();
    }

    private OptionalRender buildTeamCard(PlayerData playerData, String question) {
        List<Map<String, Object>> slots = new ArrayList<>();
        if (playerData != null && playerData.getAvatars() != null) {
            int i = 0;
            for (AvatarEntity a : playerData.getAvatars()) {
                if (a == null || a.getAvatarId() <= 0) {
                    continue;
                }
                if (i >= 4) {
                    break;
                }
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("avatarId", a.getAvatarId());
                slot.put("level", a.getLevel());
                slot.put("rank", a.getRank());
                slot.put("lightConeId", suggestLightCone(a.getAvatarId()));
                slot.put("relicSetHint", suggestRelicSet(a.getAvatarId()));
                slots.add(slot);
                i++;
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", "推荐阵容");
        root.put("layout", "vertical_poster");
        root.put("slots", slots);
        root.put("synergyNote", "根据已拥有角色生成的专属配队建议");
        String json = toJson(root);
        String text = slots.isEmpty()
                ? "请先拥有角色后再生成阵容卡牌。"
                : "已为你生成竖版阵容海报数据（" + slots.size() + " 人），含光锥/遗器建议。";
        return new OptionalRender(new RenderPayload("TEAM_CARD", json, text));
    }

    private OptionalRender buildSimUniverseBlessing(PlayerData playerData) {
        List<String> elements = inferTeamElements(playerData);
        List<Map<String, Object>> tiers = new ArrayList<>();
        tiers.add(tier(1, "存护·回响", "生存向，适合首轮拿"));
        tiers.add(tier(2, elements.contains("quantum") ? "虚无·蔓延" : "毁灭·迸发",
                "契合当前队伍元素，第二优先"));
        tiers.add(tier(3, "巡猎·猎杀", "输出补强，第三轮补强"));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", "模拟宇宙祝福优先级");
        root.put("teamElements", elements);
        root.put("priorityTiers", tiers);
        String json = toJson(root);
        return new OptionalRender(new RenderPayload("SIM_BLESSING", json,
                "模拟宇宙祝福推荐：1 存护 → 2 元素契合 → 3 巡猎补强。"));
    }

    private OptionalRender buildMaterialTree(PlayerData playerData) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", "养成材料树");
        root.put("nodes", List.of(
                node("角色等级", "经验书 / 信用点"),
                node("行迹", "行迹材料"),
                node("光锥", "光锥经验 / 叠影"),
                node("遗器", "遗器经验 / 位面饰品")));
        String json = toJson(root);
        return new OptionalRender(new RenderPayload("MATERIAL_TREE", json,
                "已生成养成材料树结构，可按节点展开刷取路线。"));
    }

    private static Map<String, Object> tier(int level, String name, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tier", level);
        m.put("blessingName", name);
        m.put("reason", reason);
        return m;
    }

    private static Map<String, Object> node(String name, String materials) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("materials", materials);
        return m;
    }

    private static List<String> inferTeamElements(PlayerData playerData) {
        List<String> els = new ArrayList<>();
        if (playerData == null || playerData.getAvatars() == null) {
            return els;
        }
        for (AvatarEntity a : playerData.getAvatars()) {
            if (a == null) {
                continue;
            }
            int id = a.getAvatarId();
            if (id % 4 == 0) {
                els.add("quantum");
            } else if (id % 4 == 1) {
                els.add("imaginary");
            } else if (id % 4 == 2) {
                els.add("ice");
            } else {
                els.add("physical");
            }
            if (els.size() >= 3) {
                break;
            }
        }
        return els;
    }

    private static int suggestLightCone(int avatarId) {
        return 300_000 + (avatarId % 100) * 10;
    }

    private static String suggestRelicSet(int avatarId) {
        return avatarId % 2 == 0 ? "虚数套/暴击套" : "速度套/击破套";
    }

    private String toJson(Map<String, Object> root) {
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public record OptionalRender(RenderPayload payload) {
        public static OptionalRender none() {
            return new OptionalRender(null);
        }

        public boolean present() {
            return payload != null && payload.hasRender();
        }
    }
}
