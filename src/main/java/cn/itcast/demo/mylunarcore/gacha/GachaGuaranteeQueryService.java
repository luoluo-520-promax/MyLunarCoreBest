package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.character.ConstellationService;
import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 保底查询：硬保底 90，软保底（大保底）在 UP 池 failedUpCount&gt;0 时生效；
 * 并附带 UP 角色当前命座层，便于客户端展示「再几金到关键命」。
 */
@Service
public class GachaGuaranteeQueryService {

    public static final int HARD_PITY_5 = 90;

    public record GuaranteeInfo(int bannerType, String bannerName, int pity5, int pity4,
                                int remainToHard5, int failedUpCount, boolean softGuarantee5,
                                Map<Integer, Integer> upConstellationLayers) {
        public GuaranteeInfo {
            upConstellationLayers = upConstellationLayers == null
                    ? Map.of() : Map.copyOf(upConstellationLayers);
        }
    }

    private final GachaRepository gachaRepository;
    private final GachaConfigService configService;
    private final ConstellationService constellationService;

    public GachaGuaranteeQueryService(GachaRepository gachaRepository) {
        this(gachaRepository, null, null);
    }

    public GachaGuaranteeQueryService(GachaRepository gachaRepository,
                                      ObjectProvider<GachaConfigService> configProvider,
                                      ObjectProvider<ConstellationService> constellationProvider) {
        this.gachaRepository = gachaRepository;
        this.configService = configProvider == null ? null : configProvider.getIfAvailable();
        this.constellationService = constellationProvider == null
                ? null : constellationProvider.getIfAvailable();
    }

    public GuaranteeInfo getGuaranteeInfo(int playerId, int bannerType) {
        if (playerId <= 0) {
            return empty(bannerType);
        }
        int type = bannerType <= 0 ? GachaBannerType.NORMAL : bannerType;
        PlayerGachaBannerInfoEntity e = gachaRepository.loadOrCreateBannerInfo(playerId, type);
        return toInfo(playerId, type, e);
    }

    /** bannerType=0 时返回常见卡池汇总。 */
    public List<GuaranteeInfo> listGuaranteeInfo(int playerId, int bannerType) {
        if (playerId <= 0) {
            return List.of();
        }
        if (bannerType > 0) {
            return List.of(getGuaranteeInfo(playerId, bannerType));
        }
        List<GuaranteeInfo> out = new ArrayList<>();
        for (int t : List.of(GachaBannerType.NEWBIE, GachaBannerType.NORMAL,
                GachaBannerType.AVATAR_UP, GachaBannerType.WEAPON_UP)) {
            out.add(getGuaranteeInfo(playerId, t));
        }
        return out;
    }

    private GuaranteeInfo toInfo(int playerId, int bannerType, PlayerGachaBannerInfoEntity e) {
        int pity5 = e == null ? 0 : Math.max(0, e.getPity5());
        int pity4 = e == null ? 0 : Math.max(0, e.getPity4());
        int failed = e == null ? 0 : Math.max(0, e.getFailedUpCount());
        int remain = Math.max(0, HARD_PITY_5 - pity5);
        boolean soft = (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP)
                && failed > 0;
        return new GuaranteeInfo(bannerType, bannerName(bannerType), pity5, pity4, remain, failed, soft,
                resolveUpLayers(playerId, bannerType));
    }

    private Map<Integer, Integer> resolveUpLayers(int playerId, int bannerType) {
        if (constellationService == null || configService == null
                || bannerType != GachaBannerType.AVATAR_UP) {
            return Map.of();
        }
        try {
            long now = System.currentTimeMillis() / 1000L;
            GachaBannerConfig banner = configService.pickActiveBanner(bannerType, now);
            if (banner == null || banner.getRateUpItems5() == null) {
                return Map.of();
            }
            Map<Integer, Integer> layers = new LinkedHashMap<>();
            for (Integer id : banner.getRateUpItems5()) {
                if (id != null && id > 0) {
                    layers.put(id, constellationService.currentLayer(playerId, id));
                }
            }
            return layers;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static GuaranteeInfo empty(int bannerType) {
        int t = bannerType <= 0 ? GachaBannerType.NORMAL : bannerType;
        return new GuaranteeInfo(t, bannerName(t), 0, 0, HARD_PITY_5, 0, false, Map.of());
    }

    private static String bannerName(int bannerType) {
        return switch (bannerType) {
            case GachaBannerType.NEWBIE -> "新手跃迁";
            case GachaBannerType.NORMAL -> "常驻跃迁";
            case GachaBannerType.AVATAR_UP -> "角色跃迁";
            case GachaBannerType.WEAPON_UP -> "光锥跃迁";
            default -> "卡池" + bannerType;
        };
    }
}
