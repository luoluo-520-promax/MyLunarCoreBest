package cn.itcast.demo.mylunarcore.infra.db;

/**
 * 线程级数据源路由上下文：读写分离 + 压测影子库（x-pt-load）。
 */
public final class RoutingDataSourceContext {

    private static final ThreadLocal<DataSourceType> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Integer> SHARD = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PT_LOAD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RoutingDataSourceContext() {
    }

    public static void set(DataSourceType type) {
        HOLDER.set(type);
    }

    public static DataSourceType get() {
        DataSourceType t = HOLDER.get();
        if (t != null) {
            return t;
        }
        return Boolean.TRUE.equals(PT_LOAD.get()) ? DataSourceType.SHADOW : DataSourceType.PRIMARY;
    }

    public static void setShard(int shardIndex) {
        SHARD.set(shardIndex);
    }

    public static int getShard(int shardCount) {
        Integer s = SHARD.get();
        if (s == null || shardCount <= 0) {
            return 0;
        }
        return Math.floorMod(s, shardCount);
    }

    /** 按 UID 哈希分库（默认 16 逻辑库）。 */
    public static int shardOfUid(long uid, int shardCount) {
        int n = Math.max(1, shardCount);
        return (int) Math.floorMod(uid, n);
    }

    public static void markPtLoad(boolean enabled) {
        PT_LOAD.set(enabled);
    }

    public static boolean isPtLoad() {
        return Boolean.TRUE.equals(PT_LOAD.get());
    }

    public static void clear() {
        HOLDER.remove();
        SHARD.remove();
        PT_LOAD.remove();
    }
}
