package cn.itcast.demo.mylunarcore.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 家园基建 → 角色入驻 → 体力恢复 / 产出 / 互访。
 */
@DisplayName("家园/基地业务流程")
class HomeBaseServiceTest {

    private HomeBaseService home;

    @BeforeEach
    void setUp() {
        // 无 JDBC：走纯内存路径，避免 mock query 返回 null 污染状态
        home = new HomeBaseService(new ObjectMapper(), "data");
        assertTrue(home.reloadCatalog(), "应能加载 data/HomeFacilityConfigs.json");
        assertTrue(home.catalogSnapshot().containsKey(1));
    }

    @Test
    @DisplayName("放置基建成功后应写入等级")
    void placeFacilityShouldPersistLevel() {
        HomeBaseService.OpResult r = home.placeFacility(501, 1, 3);
        assertTrue(r.success());
        assertEquals(0, r.retcode());
        assertEquals(3, r.state().facilities().get(1));
    }

    @Test
    @DisplayName("未知基建应失败")
    void unknownFacilityShouldFail() {
        HomeBaseService.OpResult r = home.placeFacility(501, 9999, 1);
        assertTrue(!r.success());
        assertEquals(1, r.retcode());
    }

    @Test
    @DisplayName("入驻流程：先放基建再入驻角色")
    void stationAvatarAfterPlace() {
        assertTrue(home.placeFacility(502, 2, 1).success());
        HomeBaseService.OpResult r = home.stationAvatar(502, 2, 10086);
        assertTrue(r.success());
        assertEquals(10086, r.state().stationedAvatars().get(2));
    }

    @Test
    @DisplayName("未放置基建时入驻应失败")
    void stationWithoutFacilityShouldFail() {
        HomeBaseService.OpResult r = home.stationAvatar(503, 1, 7);
        assertTrue(!r.success());
        assertEquals(2, r.retcode());
    }

    @Test
    @DisplayName("getOrCreate 应给出默认体力")
    void getOrCreateShouldInitStamina() {
        HomeBaseService.HomeState state = home.getOrCreate(504);
        assertEquals(504, state.playerId());
        assertTrue(state.stamina() > 0);
        assertTrue(state.staminaCap() >= state.stamina());
    }

    @Test
    @DisplayName("家具摆放应写入列表")
    void placeFurnitureShouldAppend() {
        HomeBaseService.OpResult r = home.placeFurniture(505, 9001, 1.0f, 0f, 2.0f, 90);
        assertTrue(r.success());
        assertEquals(1, r.state().furniture().size());
        assertEquals(9001, r.state().furniture().get(0).furnitureId());
    }
}
