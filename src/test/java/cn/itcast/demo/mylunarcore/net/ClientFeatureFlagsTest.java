package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClientFeatureFlagsTest。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ClientFeatureFlagsTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class ClientFeatureFlagsTest {

    /**
     * 验证点：normalizeZeroUsesLegacyBaselineWithoutGuildWar。
     * <p>测试方法 {@code normalizeZeroUsesLegacyBaselineWithoutGuildWar}：
     * <ul>
     *   <li>{@code assertTrue(ClientFeatureFlags.supports(mask, ClientFeatureFlags.GUILD));}</li>
     *   <li>{@code assertFalse(ClientFeatureFlags.supports(mask, ClientFeatureFlags.GUILD_WAR));}</li>
     * </ul>
     */
    @Test
    void normalizeZeroUsesLegacyBaselineWithoutGuildWar() {
        long mask = ClientFeatureFlags.normalizeClientMask(0L);
        assertTrue(ClientFeatureFlags.supports(mask, ClientFeatureFlags.GUILD));
        assertFalse(ClientFeatureFlags.supports(mask, ClientFeatureFlags.GUILD_WAR));
    }

    /**
     * 验证点：serverAllIncludesGuildWar。
     * <p>测试方法 {@code serverAllIncludesGuildWar}：
     * <ul>
     *   <li>{@code assertTrue(ClientFeatureFlags.supports(ClientFeatureFlags.SERVER_ALL, ClientFeatureFlags.GUILD_WAR));}</li>
     *   <li>{@code assertEquals(ClientFeatureFlags.GUILD_WAR,}</li>
     * </ul>
     */
    @Test
    void serverAllIncludesGuildWar() {
        assertTrue(ClientFeatureFlags.supports(ClientFeatureFlags.SERVER_ALL, ClientFeatureFlags.GUILD_WAR));
        assertEquals(ClientFeatureFlags.GUILD_WAR,
                ClientFeatureFlags.SERVER_ALL & ClientFeatureFlags.GUILD_WAR);
    }
}
