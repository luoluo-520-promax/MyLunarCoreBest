package cn.itcast.demo.mylunarcore.gacha;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.List;

/**
 * 抽卡概率引擎：从 Netty 门面剥离的领域算法（保底、UP 50/50、列表抽取）。
 */
@Component
public class GachaDrawEngine {

    /**
     * 单次抽卡结果。
     */
    public record DrawResult(int itemId, int pity5After, int pity4After, int failedUpCountAfter) {}

    private final SecureRandom rng = new SecureRandom();

    public DrawResult doOneDraw(int bannerType, GachaBannerConfig banner, GachaBannerConfig fallbackNormal,
                                int pity5, int pity4, int failedUpCount) {
        int nextPity5 = pity5 + 1;
        int nextPity4 = pity4 + 1;
        boolean got5 = nextPity5 >= 90 || rollPercent(0.6);
        boolean got4 = !got5 && (nextPity4 >= 10 || rollPercent(5.1));
        int itemId;
        int nextFailedUp = failedUpCount;
        if (got5) {
            itemId = pickFiveStar(bannerType, banner, fallbackNormal, failedUpCount);
            nextPity5 = 0;
            nextPity4 = nextPity4 + 1;
            if (bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP) {
                boolean isUp = banner.getRateUpItems5() != null && banner.getRateUpItems5().contains(itemId);
                nextFailedUp = isUp ? 0 : Math.min(255, failedUpCount + 1);
            } else {
                nextFailedUp = 0;
            }
        } else if (got4) {
            itemId = pickFromListOrFallback(banner.getRateUpItems4(),
                    fallbackNormal == null ? null : fallbackNormal.getRateUpItems4(), 20001);
            nextPity4 = 0;
        } else {
            itemId = 21000;
        }
        return new DrawResult(itemId, nextPity5, nextPity4, nextFailedUp);
    }

    int pickFiveStar(int bannerType, GachaBannerConfig banner, GachaBannerConfig fallbackNormal, int failedUpCount) {
        List<Integer> up = banner.getRateUpItems5();
        List<Integer> off = fallbackNormal == null ? null : fallbackNormal.getRateUpItems5();
        boolean isUpBanner = bannerType == GachaBannerType.AVATAR_UP || bannerType == GachaBannerType.WEAPON_UP;
        if (!isUpBanner) {
            return pickFromListOrFallback(up, off, 10001);
        }
        boolean guarantee = failedUpCount > 0;
        boolean win = guarantee || rollInt(100) < 50;
        return win ? pickFromListOrFallback(up, off, 10001) : pickFromListOrFallback(off, up, 10001);
    }

    int pickFromListOrFallback(List<Integer> primary, List<Integer> fallback, int defaultItemId) {
        List<Integer> list = (primary == null || primary.isEmpty()) ? fallback : primary;
        if (list == null || list.isEmpty()) {
            return defaultItemId;
        }
        return list.get(rollInt(list.size()));
    }

    boolean rollPercent(double percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        return rng.nextDouble() * 100.0 < percent;
    }

    int rollInt(int bound) {
        return rng.nextInt(Math.max(1, bound));
    }
}
