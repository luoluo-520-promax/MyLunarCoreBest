package cn.itcast.demo.mylunarcore.abyss;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AbyssSeasonService} 测试：深渊赛季「多队轮战」通关校验。
 * <p>
 * 使用 mock {@link JdbcTemplate} + 真实 {@link ObjectMapper}，在 {@link #setUp()} 中调用
 * {@code load()} 读入赛季楼层配置（文件缺失时服务内部有兜底）。
 */
@DisplayName("深渊赛季多队轮战")
class AbyssSeasonServiceTest {

    private AbyssSeasonService service;

    /** 每个用例前新建服务并加载赛季配置。 */
    @BeforeEach
    void setUp() {
        service = new AbyssSeasonService(mock(JdbcTemplate.class), new ObjectMapper());
        service.load(); // 加载 AbyssSeasonConfigs 或内置默认
    }

    /**
     * 第 3 层通常要求多队；仅提交 1 个队伍 ID 时应失败。
     * 约定 retcode=2 表示队伍数不足。
     */
    @Test
    @DisplayName("队伍数不足应拒绝通关")
    void rejectWhenTeamsInsufficient() {
        // player=1001，楼层 3，星级申报 3，但只带一队
        AbyssSeasonService.ClearResult r = service.reportClear(1001, 3, 3, List.of(1));
        assertFalse(r.success());
        assertEquals(2, r.retcode()); // 队伍数不足业务码
    }

    /**
     * 配置有楼层时：先通 1、2 层解锁，再以双队通第 3 层，应成功且累计星级 ≥3。
     * 若当前赛季 floors 为空（配置未加载出楼层），则通关会返回 retcode=1（无此层），本用例提前返回。
     */
    @Test
    @DisplayName("满足多队要求应累计星级")
    void clearWithMultiTeam() {
        if (service.currentSeason().floors().isEmpty()) {
            // 无楼层配置时的降级断言：非法层返回 1
            AbyssSeasonService.ClearResult r = service.reportClear(1002, 3, 3, List.of(1, 2));
            assertEquals(1, r.retcode());
            return;
        }
        // 顺序解锁到第 3 层（单队即可过前两层的常规配置）
        assertTrue(service.reportClear(1002, 1, 3, List.of(1)).success());
        assertTrue(service.reportClear(1002, 2, 3, List.of(1)).success());
        // 第 3 层提交两队，应通关并累计至少 3 星
        AbyssSeasonService.ClearResult r = service.reportClear(1002, 3, 3, List.of(1, 2));
        assertTrue(r.success());
        assertTrue(r.totalStars() >= 3);
    }
}
