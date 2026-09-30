package cn.itcast.demo.mylunarcore.archive;

import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DataArchiveJob} 单元测试：验证归档开关、集群锁回调，以及冷归档完整性缺口时跳过热表 DELETE。
 * 使用 Mockito 模拟 JdbcTemplate / ClusterJobLock / ColdArchiveClient，不连真实库。
 */
@DisplayName("DataArchiveJob 归档业务流程")
class DataArchiveJobTest {

    /**
     * 当 {@code archiveJobEnabled=false} 时，{@link DataArchiveJob#archive()} 应直接返回，
     * 且绝不调用 {@link ClusterJobLock#tryRun}（避免无谓抢锁）。
     */
    @Test
    @DisplayName("未启用时不应抢锁执行")
    void disabledSkips() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getDataRetention().setArchiveJobEnabled(false); // 关闭定时归档
        ClusterJobLock lock = mock(ClusterJobLock.class);
        DataArchiveJob job = new DataArchiveJob(mock(JdbcTemplate.class), props, lock, mock(ColdArchiveClient.class));
        job.archive();
        // 确认未进入集群互斥执行路径
        verify(lock, never()).tryRun(anyString(), any(Duration.class), any(Runnable.class));
    }

    /**
     * 启用归档后：锁的 tryRun 必须执行传入的 Runnable；Runnable 内应对抽卡历史、账本等热表发 DELETE。
     * 冷归档关闭时不做完整性拦截。
     */
    @Test
    @DisplayName("启用时应经 ClusterJobLock 执行并清理热表")
    void enabledRunsThroughLockAndDeletes() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getDataRetention().setArchiveJobEnabled(true);
        props.getDataRetention().setGachaHistoryDays(30); // 抽卡保留 30 天
        props.getDataRetention().setLedgerArchiveDays(90); // 账本保留 90 天

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 兼容不同 update 重载签名，统一假装删了 3 行
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3);
        when(jdbc.update(anyString(), anyInt())).thenReturn(3);
        // countExpired 查询：返回 0 表示无残留过期行
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyInt())).thenReturn(0);

        ColdArchiveClient cold = mock(ColdArchiveClient.class);
        when(cold.isEnabled()).thenReturn(false); // 仅热表删除路径

        AtomicBoolean ran = new AtomicBoolean();
        ClusterJobLock lock = mock(ClusterJobLock.class);
        // 模拟抢锁成功：立即执行第三个参数 Runnable
        doAnswer(inv -> {
            ran.set(true);
            inv.getArgument(2, Runnable.class).run();
            return true;
        }).when(lock).tryRun(anyString(), any(Duration.class), any(Runnable.class));

        DataArchiveJob job = new DataArchiveJob(jdbc, props, lock, cold);
        job.archive();
        assertTrue(ran.get()); // 锁回调确实跑过

        // 捕获带 int 绑定的 DELETE SQL，确认包含关键业务表名
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), anyInt());
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("gacha_draw_history")));
        assertTrue(sql.getAllValues().stream().anyMatch(s -> s.contains("wallet_ledger")));
    }

    /**
     * 冷归档开启且 copyExpiredRows 远小于热库 remaining（100）时，
     * archiveTable 应因完整性缺口跳过对该表的 DELETE（验证 never update 含表名）。
     */
    @Test
    @DisplayName("冷归档完整性不足时应跳过该表 DELETE")
    void coldIntegrityGapSkipsDelete() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getDataRetention().setArchiveJobEnabled(true);
        // 缩短各类保留天数，确保会走到 archiveTable
        props.getDataRetention().setGachaHistoryDays(10);
        props.getDataRetention().setLedgerArchiveDays(10);
        props.getDataRetention().setChatArchiveDays(10);
        props.getDataRetention().setBattleAuditDays(10);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // remaining=100，远大于下方 copied=1 → 触发完整性缺口
        when(jdbc.queryForObject(anyString(), eq(Integer.class), anyInt())).thenReturn(100);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbc.update(anyString(), anyInt())).thenReturn(0);

        ColdArchiveClient cold = mock(ColdArchiveClient.class);
        when(cold.isEnabled()).thenReturn(true);
        when(cold.copyExpiredRows(any(), anyString(), anyString(), anyInt())).thenReturn(1); // 拷贝不足

        ClusterJobLock lock = mock(ClusterJobLock.class);
        doAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return true;
        }).when(lock).tryRun(anyString(), any(Duration.class), any(Runnable.class));

        new DataArchiveJob(jdbc, props, lock, cold).archive();

        // 完整性失败后不得 DELETE 抽卡/账本热表
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("gacha_draw_history"), anyInt());
        verify(jdbc, never()).update(org.mockito.ArgumentMatchers.contains("wallet_ledger"), anyInt());
    }
}
