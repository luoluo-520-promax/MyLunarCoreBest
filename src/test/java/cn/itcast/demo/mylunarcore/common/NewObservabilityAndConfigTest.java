package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新可观测性与配置发布。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code NewObservabilityAndConfigTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("新可观测性与配置发布")
class NewObservabilityAndConfigTest {

    /**
     * 验证点：BusinessMetrics 记录延迟百分位与 Zone 负载。
     * <p>测试方法 {@code metricsLatencyAndZone}：
     * <ul>
     *   <li>{@code assertTrue(login != null && login.count() == 1);}</li>
     *   <li>{@code assertEquals(2, registry.find("lunarcore.zone.count").gauge().value(), 0.01);}</li>
     *   <li>{@code assertEquals(40, registry.find("lunarcore.zone.player_total").gauge().value(), 0.01);}</li>
     *   <li>{@code assertEquals(0.5, registry.find("lunarcore.zone.load_ratio").gauge().value(), 0.01);}</li>
     *   <li>{@code assertTrue(metrics.matchSuccessRate() > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("BusinessMetrics 记录延迟百分位与 Zone 负载")
    void metricsLatencyAndZone() {
        MeterRegistry registry = new SimpleMeterRegistry();
        BusinessMetrics metrics = new BusinessMetrics(registry);

        metrics.recordLoginLatency(12);
        metrics.recordGachaLatency(30);
        metrics.recordBattleTickLatency(5);
        metrics.recordMatchSuccess(80);
        metrics.setZoneStats(2, 40);
        metrics.setZoneLoadRatio(0.5);

        Timer login = registry.find("lunarcore.login.latency").timer();
        assertTrue(login != null && login.count() == 1);
        assertEquals(2, registry.find("lunarcore.zone.count").gauge().value(), 0.01);
        assertEquals(40, registry.find("lunarcore.zone.player_total").gauge().value(), 0.01);
        assertEquals(0.5, registry.find("lunarcore.zone.load_ratio").gauge().value(), 0.01);
        assertTrue(metrics.matchSuccessRate() > 0);
    }

    /**
     * 验证点：SceneRegistry 汇总 Zone 人数 hint。
     * <p>测试方法 {@code sceneRegistryTotals}：
     * <ul>
     *   <li>{@code assertEquals(2, registry.size());}</li>
     *   <li>{@code assertEquals(20, registry.totalPlayerHint());}</li>
     *   <li>{@code assertEquals(2, registry.listAll().size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("SceneRegistry 汇总 Zone 人数 hint")
    void sceneRegistryTotals() {
        SceneRegistry registry = new SceneRegistry();
        registry.register(101, 1, "node-a");
        registry.heartbeat(SceneRegistry.zoneId(101, 1), "node-a", 12);
        registry.register(102, 1, "node-b");
        registry.heartbeat(SceneRegistry.zoneId(102, 1), "node-b", 8);

        assertEquals(2, registry.size());
        assertEquals(20, registry.totalPlayerHint());
        assertEquals(2, registry.listAll().size());
    }

    /**
     * 验证点：ConfigReleaseService 写入发布指纹；表缺失时不抛异常。
     * <p>测试方法 {@code configReleaseRecords}：
     * <ul>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);}</li>
     *   <li>{@code verify(jdbc, times(1)).update(anyString(), any(), any(), any(), any(), any(), any());}</li>
     *   <li>{@code when(broken.update(anyString(), any(), any(), any(), any(), any(), any()))}</li>
     * </ul>
     */
    @Test
    @DisplayName("ConfigReleaseService 写入发布指纹；表缺失时不抛异常")
    void configReleaseRecords() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        ConfigReleaseService svc = new ConfigReleaseService(jdbc);
        svc.record("Banners.json", "abc123", "1.2.0", "admin", "hot reload");
        verify(jdbc, times(1)).update(anyString(), any(), any(), any(), any(), any(), any());

        JdbcTemplate broken = mock(JdbcTemplate.class);
        when(broken.update(anyString(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("no table"));
        new ConfigReleaseService(broken).record("x", "h", "v", "op", "n");
    }

    /**
     * 验证点：ConfigReleaseService 真实 H2 落库。
     * <p>测试方法 {@code configReleasePersists}：
     * <ul>
     *   <li>{@code assertEquals(1, count);}</li>
     *   <li>{@code assertEquals("deadbeef", hash);}</li>
     * </ul>
     */
    @Test
    @DisplayName("ConfigReleaseService 真实 H2 落库")
    void configReleasePersists() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:cfg_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE config_release (
                    release_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    config_name VARCHAR(128) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    game_version VARCHAR(64) NOT NULL DEFAULT '',
                    operator VARCHAR(128) NOT NULL DEFAULT '',
                    note VARCHAR(512) NOT NULL DEFAULT '',
                    created_at VARCHAR(40) NOT NULL
                )
                """);
        ConfigReleaseService svc = new ConfigReleaseService(jdbc);
        svc.record("ShopConfigs.json", "deadbeef", "2.0", "qa", "test");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM config_release", Integer.class);
        assertEquals(1, count);
        String hash = jdbc.queryForObject("SELECT content_hash FROM config_release", String.class);
        assertEquals("deadbeef", hash);
    }

    /**
     * 验证点：SensitiveDataMasker 覆盖 api-key 与 email。
     * <p>测试方法 {@code maskerExtendedRules}：
     * <ul>
     *   <li>{@code assertFalse(masked.contains("sk-live-123"));}</li>
     *   <li>{@code assertFalse(masked.contains("top"));}</li>
     *   <li>{@code assertFalse(masked.contains("user@example.com"));}</li>
     *   <li>{@code assertFalse(masked.contains("p@ss"));}</li>
     *   <li>{@code assertTrue(masked.contains("***"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("SensitiveDataMasker 覆盖 api-key 与 email")
    void maskerExtendedRules() {
        String masked = SensitiveDataMasker.mask(
                "api_key=sk-live-123 secret=top email=user@example.com password=p@ss");
        assertFalse(masked.contains("sk-live-123"));
        assertFalse(masked.contains("top"));
        assertFalse(masked.contains("user@example.com"));
        assertFalse(masked.contains("p@ss"));
        assertTrue(masked.contains("***"));
    }
}
