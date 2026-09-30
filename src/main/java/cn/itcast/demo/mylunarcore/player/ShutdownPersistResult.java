package cn.itcast.demo.mylunarcore.player;

import java.util.List;

/**
 * 优雅停机落盘汇总：成功数、失败 uid、因超时未处理的 uid。
 */
public record ShutdownPersistResult(
        int online,
        int succeeded,
        List<Long> failedUids,
        List<Long> timedOutUids
) {
    public boolean hasFailures() {
        return !failedUids.isEmpty() || !timedOutUids.isEmpty();
    }
}
