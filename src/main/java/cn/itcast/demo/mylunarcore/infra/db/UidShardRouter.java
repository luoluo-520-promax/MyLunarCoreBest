package cn.itcast.demo.mylunarcore.infra.db;

/**
 * 玩家热数据 UID Hash 分库路由（钱包/背包等）。
 */
public final class UidShardRouter {

    public static final int DEFAULT_SHARD_COUNT = 16;

    private UidShardRouter() {
    }

    public static int shardIndex(long uid) {
        return shardIndex(uid, DEFAULT_SHARD_COUNT);
    }

    public static int shardIndex(long uid, int shardCount) {
        return RoutingDataSourceContext.shardOfUid(uid, shardCount);
    }

    /** 逻辑库名，如 wallet_shard_03。 */
    public static String logicalDbName(String prefix, long uid, int shardCount) {
        return prefix + "_" + String.format("%02d", shardIndex(uid, shardCount));
    }
}
