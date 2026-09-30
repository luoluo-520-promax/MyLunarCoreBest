package cn.itcast.demo.mylunarcore.tutorial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("新手引导检查点与跳过")
class NewbieGuideCheckpointTest {

    @Test
    @DisplayName("推进一步应写入检查点；跳过整条后 completed")
    void checkpointAndSkip() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);

        NewbieGuideService svc = new NewbieGuideService(jdbc, new DefaultResourceLoader());
        svc.load();
        String first = svc.steps().get(0).id();
        assertTrue(svc.advance(9, first, true));
        NewbieGuideService.Progress p = svc.progressDetail(9);
        assertEquals(first, p.checkpointStepId());
        assertTrue(p.committedStepIds().contains(first));
        assertFalse(p.completed());

        assertTrue(svc.skipAll(9));
        NewbieGuideService.Progress skipped = svc.progressDetail(9);
        assertTrue(skipped.skipped());
        assertTrue(skipped.completed());
    }
}
