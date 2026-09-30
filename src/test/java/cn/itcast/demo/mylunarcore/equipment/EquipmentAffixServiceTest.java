package cn.itcast.demo.mylunarcore.equipment;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 光锥/遗器词条服务。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code EquipmentAffixServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("光锥/遗器词条服务")
class EquipmentAffixServiceTest {

    private EquipmentAffixService service;

    @BeforeEach
    void setUp() {
        service = new EquipmentAffixService(new ObjectMapper());
        service.load();
    }

    /**
     * 验证点：rollNew 应产出主词条与副词条 JSON。
     * <p>测试方法 {@code rollNewProducesAffixes}：
     * <ul>
     *   <li>{@code assertNotNull(roll);}</li>
     *   <li>{@code assertTrue(roll.subAffixesJson().startsWith("["));}</li>
     * </ul>
     */
    @Test
    @DisplayName("rollNew 应产出主词条与副词条 JSON")
    void rollNewProducesAffixes() {
        EquipmentAffixService.RollResult roll = service.rollNew("BODY", 501001);
        assertNotNull(roll);
        assertTrue(roll.subAffixesJson().startsWith("["));
    }

    /**
     * 验证点：分解应按返还比例给出经验。
     * <p>测试方法 {@code decomposeReturnsPartialExp}：
     * <ul>
     *   <li>{@code assertTrue(r.returnExp() > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("分解应按返还比例给出经验")
    void decomposeReturnsPartialExp() {
        GameItemEntity item = new GameItemEntity();
        item.setLevel(10);
        item.setExp(5000);
        EquipmentAffixService.DecomposeResult r = service.decompose(item);
        assertTrue(r.returnExp() > 0);
    }

    /**
     * 验证点：装备汇总应叠加光锥等级加成。
     * <p>测试方法 {@code sumEquippedAddsLightCone}：
     * <ul>
     *   <li>{@code assertTrue(b.atkFlat() > 0 || b.atkPct() > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("装备汇总应叠加光锥等级加成")
    void sumEquippedAddsLightCone() {
        GameItemEntity lc = new GameItemEntity();
        lc.setType(1);
        lc.setLevel(20);
        lc.setRank(1);
        lc.setItemId(20001);
        EquipmentBonus b = service.sumEquippedBonus(List.of(lc));
        assertTrue(b.atkFlat() > 0 || b.atkPct() > 0);
    }
}
