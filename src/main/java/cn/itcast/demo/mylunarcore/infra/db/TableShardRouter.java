package cn.itcast.demo.mylunarcore.infra.db;

/**
 * 水平分表路由：按 uid 映射到逻辑库 + 物理表（默认 16 库 × 64 表）。
 * <p>
 * 与 ShardingSphere-JDBC 的 actualDataNodes 约定对齐，便于后续透明切换。
 */
public final class TableShardRouter {

    public static final int DEFAULT_DB_SHARDS = 16;
    public static final int DEFAULT_TABLE_SHARDS = 64;

    private TableShardRouter() {
    }

    public static int dbShard(long uid) {
        return dbShard(uid, DEFAULT_DB_SHARDS);
    }

    public static int dbShard(long uid, int dbShards) {
        int n = Math.max(1, dbShards);
        return (int) Math.floorMod(uid, n);
    }

    public static int tableShard(long uid) {
        return tableShard(uid, DEFAULT_TABLE_SHARDS);
    }

    public static int tableShard(long uid, int tableShards) {
        int n = Math.max(1, tableShards);
        return (int) Math.floorMod(uid / DEFAULT_DB_SHARDS, n);
    }

    /** 例如 player_wallet_03_027 */
    public static String physicalTable(String logicTable, long uid) {
        return physicalTable(logicTable, uid, DEFAULT_DB_SHARDS, DEFAULT_TABLE_SHARDS);
    }

    public static String physicalTable(String logicTable, long uid, int dbShards, int tableShards) {
        return logicTable + "_" + String.format("%02d", dbShard(uid, dbShards))
                + "_" + String.format("%03d", tableShard(uid, tableShards));
    }

    /** ShardingSphere actualDataNodes 模板：ds_${0..15}.player_wallet_${0..15}_${0..63} */
    public static String actualDataNodesHint(String logicTable) {
        return "ds_${0.." + (DEFAULT_DB_SHARDS - 1) + "}." + logicTable
                + "_${0.." + (DEFAULT_DB_SHARDS - 1) + "}_${0.." + (DEFAULT_TABLE_SHARDS - 1) + "}";
    }
}
