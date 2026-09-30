package cn.itcast.demo.mylunarcore.e2e;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端链路骨架：登录 → 大厅 → 抽卡/战斗/结算 的协议号段与兼容门闩。
 * 完整模拟客户端需接 Netty 集成或 Testcontainers；此处保证号段与 wire version 不回归。
 */
@DisplayName("E2E 协议链路骨架")
class ClientJourneyE2ESkeletonTest {

    @Test
    @DisplayName("核心旅程 CmdId：登录→经济→公会→兼容门闩")
    void coreJourneyCmdIdsReserved() {
        assertEquals(68, CmdIds.PLAYER_LOGIN_CS_REQ);
        assertEquals(140, CmdIds.GET_SHOP_LIST_CS_REQ);
        assertEquals(145, CmdIds.CREATE_IAP_ORDER_CS_REQ);
        assertTrue(CmdIds.CREATE_GUILD_CS_REQ >= 970 && CmdIds.CREATE_GUILD_CS_REQ < 990);
        assertTrue(CmdIds.BUY_GUILD_SHOP_CS_REQ > CmdIds.CREATE_GUILD_CS_REQ);
        assertEquals(CmdIds.GET_GUILD_INFO_CS_REQ + 1, CmdIds.GET_GUILD_INFO_SC_RSP);
    }

    @Test
    @DisplayName("旧客户端 wire version 必须被明确拒绝")
    void legacyWireVersionRejected() {
        ProtocolCompatService compat = new ProtocolCompatService();
        assertEquals(CmdIds.PROTOCOL_WIRE_VERSION, compat.currentWireVersion());
        assertTrue(compat.isCompatible(2));
        assertFalse(compat.isCompatible(1));
    }
}
