package cn.itcast.demo.mylunarcore.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 玩家名片/头像框。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerCardServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("玩家名片/头像框")
class PlayerCardServiceTest {

    /**
     * 验证点：授予并装备头像框。
     * <p>测试方法 {@code grantAndEquipFrame}：
     * <ul>
     *   <li>{@code assertTrue(svc.grantFrame(1001, 1001));}</li>
     *   <li>{@code assertTrue(r.success());}</li>
     *   <li>{@code assertEquals(1001, r.card().frameId());}</li>
     *   <li>{@code assertTrue(svc.updateShowcase(1001, List.of(1001, 1002), "开拓者", "愿此行无悔").success());}</li>
     * </ul>
     */
    @Test
    @DisplayName("授予并装备头像框")
    void grantAndEquipFrame() {
        PlayerCardService svc = new PlayerCardService(mock(JdbcTemplate.class));
        assertTrue(svc.grantFrame(1001, 1001));
        PlayerCardService.OpResult r = svc.equipFrame(1001, 1001);
        assertTrue(r.success());
        assertEquals(1001, r.card().frameId());
        assertTrue(svc.updateShowcase(1001, List.of(1001, 1002), "开拓者", "愿此行无悔").success());
    }
}
