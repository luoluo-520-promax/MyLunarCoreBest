package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * GachaRepository 抽卡仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaRepository 抽卡仓储测试")
class GachaRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(GachaRepositoryTest.class);

    private JdbcTemplate jdbcTemplate;
    private GachaRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new GachaRepository(jdbcTemplate);
        log.info("仓储初始化: repository={}", repository.getClass().getSimpleName());
    }

    /**
     * 验证点：loadOrCreateGachaInfo 应确保行存在并返回全局抽卡信息。
     * <p>测试方法 {@code loadOrCreateGachaInfoShouldReturnEntity}：
     * <ul>
     *   <li>{@code assertEquals(77, info.getPlayerId());}</li>
     *   <li>{@code assertEquals(120, info.getCeilingNum());}</li>
     *   <li>{@code assertFalse(info.isCeilingClaimed());}</li>
     * </ul>
     */
    @Test
    @DisplayName("loadOrCreateGachaInfo 应确保行存在并返回全局抽卡信息")
    void loadOrCreateGachaInfoShouldReturnEntity() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .intCol("id", 1).intCol("player_id", 77)
                        .intCol("ceiling_num", 120).intCol("ceiling_claimed", 0)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        PlayerGachaInfoEntity info = repository.loadOrCreateGachaInfo(77);

        assertEquals(77, info.getPlayerId());
        log.info("全局抽卡信息: playerId=77, ceilingNum={}, ceilingClaimed={}",
                info.getCeilingNum(), info.isCeilingClaimed());
        assertEquals(120, info.getCeilingNum());
        assertFalse(info.isCeilingClaimed());
    }

    /**
     * 验证点：loadOrCreateBannerInfo 应返回 Banner 保底计数。
     * <p>测试方法 {@code loadOrCreateBannerInfoShouldReturnBannerEntity}：
     * <ul>
     *   <li>{@code assertEquals(1, banner.getBannerType());}</li>
     *   <li>{@code assertEquals(62, banner.getPity5());}</li>
     *   <li>{@code assertEquals(8, banner.getPity4());}</li>
     *   <li>{@code assertEquals(1, banner.getFailedUpCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("loadOrCreateBannerInfo 应返回 Banner 保底计数")
    void loadOrCreateBannerInfoShouldReturnBannerEntity() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);
        RepoTestFixtures.stubQueryRows(jdbcTemplate, List.of(
                RepoTestFixtures.mockResultSet(b -> b
                        .longCol("id", 10L).intCol("player_id", 77)
                        .intCol("banner_type", 1).intCol("pity_5", 62)
                        .intCol("pity_4", 8).intCol("failed_up_count", 1)
                        .timestampCol("created_at", RepoTestFixtures.ts("2026-06-01 10:00:00"))
                        .timestampCol("updated_at", RepoTestFixtures.ts("2026-06-06 10:00:00")))
        ));

        PlayerGachaBannerInfoEntity banner = repository.loadOrCreateBannerInfo(77, 1);

        assertEquals(1, banner.getBannerType());
        log.info("Banner 保底信息: playerId=77, bannerType=1, pity5={}, pity4={}, failedUpCount={}",
                banner.getPity5(), banner.getPity4(), banner.getFailedUpCount());
        assertEquals(62, banner.getPity5());
        assertEquals(8, banner.getPity4());
        assertEquals(1, banner.getFailedUpCount());
    }

    /**
     * 验证点：updateBannerPity 应更新保底计数。
     * <p>测试方法 {@code updateBannerPityShouldUpdateRow}：
     * <ul>
     *   <li>{@code assertEquals(1, affected);}</li>
     * </ul>
     */
    @Test
    @DisplayName("updateBannerPity 应更新保底计数")
    void updateBannerPityShouldUpdateRow() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.updateBannerPity(77, 1, 0, 3, 0);

        log.info("保底计数更新: playerId=77, bannerType=1, pity5=0, pity4=3, failedUpCount=0, affectedRows={}",
                affected);
        assertEquals(1, affected);
    }

    /**
     * 验证点：incrementCeilingNum 应累加 300 抽里程碑。
     * <p>测试方法 {@code incrementCeilingNumShouldAddCeiling}：
     * <ul>
     *   <li>{@code assertEquals(1, affected);}</li>
     * </ul>
     */
    @Test
    @DisplayName("incrementCeilingNum 应累加 300 抽里程碑")
    void incrementCeilingNumShouldAddCeiling() {
        RepoTestFixtures.stubUpdate(jdbcTemplate, 1);

        int affected = repository.incrementCeilingNum(77, 10);

        log.info("里程碑累加: playerId=77, add=10, affectedRows={}", affected);
        assertEquals(1, affected);
    }
}
