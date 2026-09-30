package cn.itcast.demo.mylunarcore.social;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动作/表情包仓库：预设动画与静态贴纸；默认发放基础套，可扩展解锁。
 * 自定义表情（id≥100000）经 {@link CustomEmoteService} 校验拥有与审核状态。
 */
@Service
public class EmoteInventoryService {

    public record EmoteDef(int emoteId, String name, String animId, boolean sticker, boolean duo) {}

    public record PlayResult(boolean ok, int retcode, EmoteDef emote, boolean duo) {
        static PlayResult fail(int retcode) {
            return new PlayResult(false, retcode, null, false);
        }
    }

    private static final Map<Integer, EmoteDef> CATALOG = new LinkedHashMap<>();

    static {
        CATALOG.put(1, new EmoteDef(1, "挥手", "emote_wave", false, false));
        CATALOG.put(2, new EmoteDef(2, "跳舞", "emote_dance", false, false));
        CATALOG.put(3, new EmoteDef(3, "击掌", "emote_highfive", false, true));
        CATALOG.put(4, new EmoteDef(4, "点赞贴纸", "sticker_thumb", true, false));
        CATALOG.put(5, new EmoteDef(5, "庆祝贴纸", "sticker_party", true, false));
    }

    private final Map<Integer, Set<Integer>> owned = new ConcurrentHashMap<>();
    private final ObjectProvider<CustomEmoteService> customEmoteProvider;

    public EmoteInventoryService() {
        this(null);
    }

    public EmoteInventoryService(ObjectProvider<CustomEmoteService> customEmoteProvider) {
        this.customEmoteProvider = customEmoteProvider;
    }

    public List<EmoteDef> catalog() {
        return List.copyOf(CATALOG.values());
    }

    public List<EmoteDef> ownedOrDefault(int playerId) {
        Set<Integer> set = owned.computeIfAbsent(playerId, id -> {
            Set<Integer> starter = ConcurrentHashMap.newKeySet();
            starter.add(1);
            starter.add(2);
            starter.add(3);
            starter.add(4);
            return starter;
        });
        List<EmoteDef> out = new ArrayList<>();
        for (EmoteDef def : CATALOG.values()) {
            if (set.contains(def.emoteId())) {
                out.add(def);
            }
        }
        return out;
    }

    public boolean owns(int playerId, int emoteId) {
        if (emoteId >= CustomEmoteService.CUSTOM_ID_FLOOR) {
            return ownsCustom(playerId, emoteId);
        }
        return ownedOrDefault(playerId).stream().anyMatch(e -> e.emoteId() == emoteId);
    }

    /** 供 Netty/自定义表情链路直接校验，避免循环依赖时也可单独调用。 */
    public boolean ownsCustom(int playerId, int emoteId) {
        if (emoteId < CustomEmoteService.CUSTOM_ID_FLOOR) {
            return false;
        }
        CustomEmoteService custom = customEmoteProvider == null
                ? null : customEmoteProvider.getIfAvailable();
        return custom != null && custom.owns(playerId, emoteId);
    }

    public EmoteDef find(int emoteId) {
        if (emoteId >= CustomEmoteService.CUSTOM_ID_FLOOR) {
            CustomEmoteService custom = customEmoteProvider == null
                    ? null : customEmoteProvider.getIfAvailable();
            if (custom != null) {
                return custom.findApproved(emoteId)
                        .map(e -> new EmoteDef(e.customEmoteId(), e.name(), "custom_emote", true, false))
                        .orElse(null);
            }
            return null;
        }
        return CATALOG.get(emoteId);
    }

    public PlayResult play(int playerId, int emoteId, boolean wantDuo) {
        if (playerId <= 0) {
            return PlayResult.fail(1);
        }
        if (emoteId >= CustomEmoteService.CUSTOM_ID_FLOOR) {
            if (!ownsCustom(playerId, emoteId)) {
                return PlayResult.fail(3);
            }
            CustomEmoteService custom = customEmoteProvider == null
                    ? null : customEmoteProvider.getIfAvailable();
            if (custom == null) {
                return PlayResult.fail(3);
            }
            return custom.findApproved(emoteId)
                    .map(e -> new PlayResult(true, 0,
                            new EmoteDef(e.customEmoteId(), e.name(), "custom_emote", true, false),
                            false))
                    .orElseGet(() -> PlayResult.fail(3));
        }
        EmoteDef def = CATALOG.get(emoteId);
        if (def == null) {
            return PlayResult.fail(3);
        }
        if (!owns(playerId, emoteId)) {
            return PlayResult.fail(3);
        }
        boolean duo = wantDuo && def.duo();
        return new PlayResult(true, 0, def, duo);
    }
}
