package cn.itcast.demo.mylunarcore.infra.db;

/**
 * 数据源路由键：主库写、从库读、压测影子库。
 */
public enum DataSourceType {
    PRIMARY,
    REPLICA,
    SHADOW
}
