package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine;
import cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService;
import cn.itcast.demo.mylunarcore.common.ConfigActiveWindow;
import cn.itcast.demo.mylunarcore.common.ConfigOverrideHotfixService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.player.ClientVersionGateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("商业化运维增强：脚本/生效窗/熔断/时间旅行")
class CommercialOpsEnhancementTest {

    @TempDir
    Path tempDir;

    @Test
    void activityScriptEngineLoadsAndInvokes() throws Exception {
        Path scripts = tempDir.resolve("scripts/activity");
        Files.createDirectories(scripts);
        Files.writeString(scripts.resolve("sign_in_bonus.groovy"), """
def day = binding.hasVariable('day') ? (binding.getVariable('day') as Number).intValue() : 1
def baseReward = 10
def reward = (day == 7) ? baseReward * 2 : baseReward
return [retcode: 0, value: [reward: reward, multiplied: (day == 7)]]
""");
        LunarCoreProperties props = new LunarCoreProperties();
        props.setDataDir(tempDir.toString());
        ActivityScriptEngine engine = new ActivityScriptEngine(props);
        int loaded = engine.reloadAll();
        assertTrue(loaded > 0, "scripts loaded=" + loaded + " snap=" + engine.snapshot());
        assertTrue(engine.hasScript("sign_in_bonus"), String.valueOf(engine.snapshot()));
        var r7 = engine.invoke("sign_in_bonus", Map.of("day", 7));
        assertTrue(r7.success(), r7.message());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) r7.value();
        assertEquals(20, ((Number) value.get("reward")).intValue());
    }

    @Test
    void activeWindowAndTimeTravel() {
        ServerClockService clock = new ServerClockService();
        long now = clock.nowEpochSecond();
        ConfigActiveWindow w = ConfigActiveWindow.of(now + 100, now + 1000, now + 50, now + 100, now + 1000, now + 900);
        assertFalse(w.isDisplayable(now));
        assertFalse(w.isEffectActive(now));
        clock.travelTo(Instant.ofEpochSecond(now + 60), "qa");
        assertTrue(w.isDisplayable(clock.nowEpochSecond()));
        assertFalse(w.isEffectActive(clock.nowEpochSecond()));
        clock.reset();
    }

    @Test
    void qaVisibilityMask() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.setDataDir(tempDir.toString());
        ServerClockService clock = new ServerClockService();
        ActivityVisibilityService vis = new ActivityVisibilityService(clock, props);
        long now = clock.nowEpochSecond();
        ActivityConfig future = new ActivityConfig();
        future.setBeginTime(now + 500);
        future.setEndTime(now + 5000);
        future.setDisplayStart(now + 400);
        future.setEffectStart(now + 500);
        future.setDisplayEnd(now + 5000);
        future.setEffectEnd(now + 5000);
        assertFalse(vis.canSee(future, false));
        vis.markQa(42L, true);
        assertTrue(vis.isQaTester(42L));
        assertTrue(vis.canSee(future, true));
        assertTrue(vis.excludeFromLeaderboard(true));
    }

    @Test
    void circuitBreakerTrips() {
        ActivityCircuitBreakerService cb = new ActivityCircuitBreakerService();
        assertFalse(cb.isTripped());
        cb.trip("test");
        assertTrue(cb.shouldDegradeToMail());
        assertTrue(cb.shouldBlockTimedPlay());
        cb.reset();
        assertFalse(cb.isTripped());
    }

    @Test
    void overrideHotfixAppliesPath() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.setDataDir(tempDir.toString());
        ConfigOverrideHotfixService svc = new ConfigOverrideHotfixService(
                props, new cn.itcast.demo.mylunarcore.common.ConfigDeltaPatchService());
        var r = svc.applyOverride("gacha", Map.of("base_probability", 0.006), "fix_up_rate");
        assertTrue(Boolean.TRUE.equals(r.get("ok")));
        assertEquals(0.006, svc.resolve("gacha", "base_probability", null));
    }

    @Test
    void resourceOutdatedRetcode() {
        assertEquals(11, ClientVersionGateService.ERR_RESOURCE_OUTDATED);
        ClientVersionGateService gate = new ClientVersionGateService(new ObjectMapper(), tempDir.toString());
        assertEquals(10, ClientVersionGateService.ERR_CLIENT_TOO_OLD);
        assertTrue(gate.check("999.0.0").allowed() || !gate.check("999.0.0").allowed());
    }
}
