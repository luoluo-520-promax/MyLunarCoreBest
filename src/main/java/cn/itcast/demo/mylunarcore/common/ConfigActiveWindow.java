package cn.itcast.demo.mylunarcore.common;

/**
 * 配置生效窗：展示时间 vs 实际生效时间，支持未来版本预加载但不对外可见。
 */
public record ConfigActiveWindow(long displayStart, long effectStart, long effectEnd, long displayEnd) {

    public static ConfigActiveWindow of(long beginTime, long endTime,
                                        long displayStart, long effectStart,
                                        long displayEnd, long effectEnd) {
        long ds = displayStart > 0 ? displayStart : beginTime;
        long es = effectStart > 0 ? effectStart : beginTime;
        long ee = effectEnd > 0 ? effectEnd : endTime;
        long de = displayEnd > 0 ? displayEnd : endTime;
        return new ConfigActiveWindow(ds, es, ee, de);
    }

    /** 客户端是否可见（展示窗内）。 */
    public boolean isDisplayable(long nowSec) {
        return nowSec >= displayStart && nowSec <= displayEnd;
    }

    /** 玩法是否真正可交互（生效窗内）。 */
    public boolean isEffectActive(long nowSec) {
        return nowSec >= effectStart && nowSec <= effectEnd;
    }

    /** 已加载但未到展示时间（预热态）。 */
    public boolean isPreloaded(long nowSec) {
        return nowSec < displayStart && effectStart > 0;
    }
}
