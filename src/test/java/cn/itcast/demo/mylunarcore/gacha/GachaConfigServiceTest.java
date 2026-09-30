package cn.itcast.demo.mylunarcore.gacha;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GachaConfigService 卡池配置服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaConfigServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaConfigService 卡池配置服务测试")
class GachaConfigServiceTest {

    private static final Logger log = LoggerFactory.getLogger(GachaConfigServiceTest.class);

    private static final long NOW = 1_700_000_000L;

    private GachaConfigService service;

    @BeforeEach
    void setUp() {
        service = new GachaConfigService();
        log.info("卡池配置服务初始化: defaultPath=data/Banners.json");
    }

    /**
     * 验证点：reload 应从 Banners.json 加载卡池并按类型索引。
     * <p>测试方法 {@code reloadShouldLoadBannersFromFile}：
     * <ul>
     *   <li>{@code assertTrue(ok);}</li>
     *   <li>{@code assertFalse(normalBanners.isEmpty());}</li>
     *   <li>{@code assertFalse(avatarUpBanners.isEmpty());}</li>
     *   <li>{@code assertEquals(1001, normalBanners.get(0).getId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("reload 应从 Banners.json 加载卡池并按类型索引")
    void reloadShouldLoadBannersFromFile() {
        boolean ok = service.reload();
        List<GachaBannerConfig> normalBanners = service.listByType(GachaBannerType.NORMAL);
        List<GachaBannerConfig> avatarUpBanners = service.listByType(GachaBannerType.AVATAR_UP);

        log.info("配置加载校验: reloadOk={}, normalCount={}, avatarUpCount={}, firstNormalId={}",
                ok, normalBanners.size(), avatarUpBanners.size(),
                normalBanners.isEmpty() ? -1 : normalBanners.get(0).getId());
        assertTrue(ok);
        assertFalse(normalBanners.isEmpty());
        assertFalse(avatarUpBanners.isEmpty());
        assertEquals(1001, normalBanners.get(0).getId());
    }

    /**
     * 验证点：listByType 对未配置类型应返回空列表。
     * <p>测试方法 {@code listByTypeMissingShouldReturnEmptyList}：
     * <ul>
     *   <li>{@code assertTrue(newbieBanners.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("listByType 对未配置类型应返回空列表")
    void listByTypeMissingShouldReturnEmptyList() {
        GachaTestFixtures.injectConfigs(service,
                GachaTestFixtures.singleTypeMap(GachaBannerType.NORMAL, GachaTestFixtures.alwaysOpenNormalBanner()));

        List<GachaBannerConfig> newbieBanners = service.listByType(GachaBannerType.NEWBIE);
        log.info("空类型查询校验: bannerType={}, size={}, isEmpty={}",
                GachaBannerType.NEWBIE, newbieBanners.size(), newbieBanners.isEmpty());
        assertTrue(newbieBanners.isEmpty());
    }

    /**
     * 验证点：pickActiveBanner 应命中当前开放窗口内的 Banner。
     * <p>测试方法 {@code pickActiveBannerShouldReturnOpenBanner}：
     * <ul>
     *   <li>{@code assertNotNull(active);}</li>
     *   <li>{@code assertEquals(1001, active.getId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pickActiveBanner 应命中当前开放窗口内的 Banner")
    void pickActiveBannerShouldReturnOpenBanner() {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        GachaTestFixtures.injectConfigs(service, GachaTestFixtures.singleTypeMap(GachaBannerType.NORMAL, normal));

        GachaBannerConfig active = service.pickActiveBanner(GachaBannerType.NORMAL, NOW);
        assertNotNull(active);
        log.info("开放窗口校验: bannerType={}, nowSeconds={}, bannerId={}, beginTime={}, endTime={}",
                GachaBannerType.NORMAL, NOW, active.getId(), active.getBeginTime(), active.getEndTime());
        assertEquals(1001, active.getId());
    }

    /**
     * 验证点：pickActiveBanner 在窗口外应返回 null。
     * <p>测试方法 {@code pickActiveBannerOutsideWindowShouldReturnNull}：
     * <ul>
     *   <li>{@code assertNull(active);}</li>
     * </ul>
     */
    @Test
    @DisplayName("pickActiveBanner 在窗口外应返回 null")
    void pickActiveBannerOutsideWindowShouldReturnNull() {
        GachaBannerConfig expired = GachaTestFixtures.banner(
                9001, "AvatarUp", 1_600_000_000L, 1_650_000_000L, List.of(1102), List.of(1105));
        GachaTestFixtures.injectConfigs(service,
                GachaTestFixtures.singleTypeMap(GachaBannerType.AVATAR_UP, expired));

        GachaBannerConfig active = service.pickActiveBanner(GachaBannerType.AVATAR_UP, NOW);
        log.info("过期窗口校验: bannerType={}, nowSeconds={}, beginTime={}, endTime={}, active={}",
                GachaBannerType.AVATAR_UP, NOW, expired.getBeginTime(), expired.getEndTime(), active);
        assertNull(active);
    }

    /**
     * 验证点：pickNormalBannerForFallback 应返回当前开放的常驻池。
     * <p>测试方法 {@code pickNormalBannerForFallbackShouldReturnActiveNormalBanner}：
     * <ul>
     *   <li>{@code assertNotNull(fallback);}</li>
     *   <li>{@code assertEquals(1001, fallback.getId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pickNormalBannerForFallback 应返回当前开放的常驻池")
    void pickNormalBannerForFallbackShouldReturnActiveNormalBanner() {
        GachaBannerConfig normal = GachaTestFixtures.alwaysOpenNormalBanner();
        GachaTestFixtures.injectConfigs(service, GachaTestFixtures.singleTypeMap(GachaBannerType.NORMAL, normal));

        GachaBannerConfig fallback = service.pickNormalBannerForFallback(NOW);
        assertNotNull(fallback);
        log.info("常驻池回退校验: nowSeconds={}, fallbackId={}, rateUp5Count={}, rateUp4Count={}",
                NOW, fallback.getId(),
                fallback.getRateUpItems5() == null ? 0 : fallback.getRateUpItems5().size(),
                fallback.getRateUpItems4() == null ? 0 : fallback.getRateUpItems4().size());
        assertEquals(1001, fallback.getId());
    }

    /**
     * 验证点：pickActiveBanner 同类型多条时应返回第一个开放中的 Banner。
     * <p>测试方法 {@code pickActiveBannerShouldReturnFirstOpenBanner}：
     * <ul>
     *   <li>{@code assertNotNull(active);}</li>
     *   <li>{@code assertEquals(2001, active.getId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pickActiveBanner 同类型多条时应返回第一个开放中的 Banner")
    void pickActiveBannerShouldReturnFirstOpenBanner() {
        GachaBannerConfig first = GachaTestFixtures.avatarUpBanner(2001, 0, 1_800_000_000L);
        GachaBannerConfig second = GachaTestFixtures.avatarUpBanner(2002, 0, 1_900_000_000L);
        GachaTestFixtures.injectConfigs(service, GachaTestFixtures.singleTypeMap(
                GachaBannerType.AVATAR_UP, first, second));

        GachaBannerConfig active = service.pickActiveBanner(GachaBannerType.AVATAR_UP, NOW);
        assertNotNull(active);
        log.info("多 Banner 选取校验: bannerType={}, nowSeconds={}, activeId={}, secondId={}",
                GachaBannerType.AVATAR_UP, NOW, active.getId(), second.getId());
        assertEquals(2001, active.getId());
    }
}
