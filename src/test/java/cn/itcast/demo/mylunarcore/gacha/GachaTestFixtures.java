package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽卡模块测试用公共数据构造工具。
 */
final class GachaTestFixtures {

    private GachaTestFixtures() {
    }

    static GachaBannerConfig banner(int id, String gachaType, long beginTime, long endTime,
                                      List<Integer> rateUp5, List<Integer> rateUp4) {
        GachaBannerConfig config = new GachaBannerConfig();
        config.setId(id);
        config.setGachaType(gachaType);
        config.setBeginTime(beginTime);
        config.setEndTime(endTime);
        config.setRateUpItems5(rateUp5);
        config.setRateUpItems4(rateUp4);
        return config;
    }

    static GachaBannerConfig alwaysOpenNormalBanner() {
        return banner(1001, "Normal", 0, 1_924_992_000L, List.of(1001, 1002), List.of(23002, 1003));
    }

    static GachaBannerConfig avatarUpBanner(int id, long begin, long end) {
        return banner(id, "AvatarUp", begin, end, List.of(1102), List.of(1105, 1106));
    }

    static PlayerGachaInfoEntity gachaInfo(int playerId, int ceilingNum, boolean ceilingClaimed) {
        PlayerGachaInfoEntity entity = new PlayerGachaInfoEntity();
        entity.setId(1);
        entity.setPlayerId(playerId);
        entity.setCeilingNum(ceilingNum);
        entity.setCeilingClaimed(ceilingClaimed);
        return entity;
    }

    static PlayerGachaBannerInfoEntity bannerInfo(int playerId, int bannerType,
                                                int pity5, int pity4, int failedUpCount) {
        PlayerGachaBannerInfoEntity entity = new PlayerGachaBannerInfoEntity();
        entity.setId(1L);
        entity.setPlayerId(playerId);
        entity.setBannerType(bannerType);
        entity.setPity5(pity5);
        entity.setPity4(pity4);
        entity.setFailedUpCount(failedUpCount);
        return entity;
    }

    static void injectConfigs(GachaConfigService service, Map<Integer, List<GachaBannerConfig>> configs) {
        try {
            Field field = GachaConfigService.class.getDeclaredField("configsByType");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Integer, List<GachaBannerConfig>> map =
                    (Map<Integer, List<GachaBannerConfig>>) field.get(service);
            map.clear();
            map.putAll(configs);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to inject gacha configs", e);
        }
    }

    static Map<Integer, List<GachaBannerConfig>> singleTypeMap(int bannerType, GachaBannerConfig... banners) {
        Map<Integer, List<GachaBannerConfig>> map = new ConcurrentHashMap<>();
        List<GachaBannerConfig> list = new ArrayList<>();
        for (GachaBannerConfig banner : banners) {
            list.add(banner);
        }
        map.put(bannerType, list);
        return map;
    }
}
