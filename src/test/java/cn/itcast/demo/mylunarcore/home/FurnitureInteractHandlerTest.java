package cn.itcast.demo.mylunarcore.home;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("家园家具互动")
class FurnitureInteractHandlerTest {

    @Test
    @DisplayName("坐椅子应同步 idle=sit")
    void sitChairUpdatesPresence(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("HomeFacilityConfigs.json"), "[]");
        HomeBaseService home = new HomeBaseService(new ObjectMapper(), dir.toString());
        home.load();
        home.placeFurniture(7, 501, 1, 0, 1, 90);
        HomePresenceService presence = new HomePresenceService();
        FurnitureInteractHandler handler = new FurnitureInteractHandler(home, presence);

        FurnitureInteractHandler.InteractResult r = handler.interact(7, 7, 501, "sit", 1, 0, 1, 90, 12);
        assertTrue(r.ok());
        assertEquals("sit", r.presence().idleAnim());
        assertEquals(501, r.presence().interactFurnitureId());
        assertEquals(12, presence.list(7).get(0).skinId());
    }

    @Test
    @DisplayName("非法动作应拒绝")
    void invalidActionRejected(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("HomeFacilityConfigs.json"), "[]");
        HomeBaseService home = new HomeBaseService(new ObjectMapper(), dir.toString());
        home.load();
        FurnitureInteractHandler handler = new FurnitureInteractHandler(home, new HomePresenceService());
        assertEquals(3, handler.interact(1, 1, 1, "fly", 0, 0, 0, 0, 0).retcode());
    }
}
