package cn.itcast.demo.mylunarcore.handbook;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 收集图鉴。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code HandbookServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("收集图鉴")
class HandbookServiceTest {

    /**
     * 验证点：解锁应计入进度。
     * <p>测试方法 {@code unlockIncreasesSummary}：
     * <ul>
     *   <li>{@code when(provider.getIfAvailable()).thenReturn(null);}</li>
     *   <li>{@code assertTrue(svc.unlock(1, HandbookService.EntryType.AVATAR, 1001));}</li>
     *   <li>{@code assertEquals(1, s.unlocked());}</li>
     *   <li>{@code assertTrue(s.total() >= 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("解锁应计入进度")
    void unlockIncreasesSummary() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AchievementService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        HandbookService svc = new HandbookService(mock(JdbcTemplate.class), provider);
        assertTrue(svc.unlock(1, HandbookService.EntryType.AVATAR, 1001));
        HandbookService.Summary s = svc.summary(1, HandbookService.EntryType.AVATAR);
        assertEquals(1, s.unlocked());
        assertTrue(s.total() >= 1);
    }
}
