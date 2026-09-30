package cn.itcast.demo.mylunarcore.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClientVersionGateService 资源版本门禁")
class ClientVersionGateServiceTest {

    @Test
    void compareSemverOrdering() {
        assertTrue(ClientVersionGateService.compareSemver("0.9.9", "1.0.0") < 0);
        assertEquals(0, ClientVersionGateService.compareSemver("1.0.0", "1.0.0"));
        assertTrue(ClientVersionGateService.compareSemver("1.2.0", "1.1.9") > 0);
    }

    @Test
    void rejectWhenBelowMin() {
        ClientVersionGateService gate = new ClientVersionGateService(
                new com.fasterxml.jackson.databind.ObjectMapper(), "data");
        // 手动注入阈值
        try {
            var field = ClientVersionGateService.class.getDeclaredField("required");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var ref = (java.util.concurrent.atomic.AtomicReference<ClientVersionGateService.RequiredVersions>) field.get(gate);
            ref.set(new ClientVersionGateService.RequiredVersions("2.0.0", "https://u.example/app", "2.0.0"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        var bad = gate.check("1.9.0");
        assertFalse(bad.allowed());
        assertEquals(ClientVersionGateService.ERR_CLIENT_TOO_OLD, bad.retcode());
        assertTrue(gate.check("2.0.0").allowed());
    }
}
