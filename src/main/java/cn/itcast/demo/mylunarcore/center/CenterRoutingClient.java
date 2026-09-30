package cn.itcast.demo.mylunarcore.center;

/**
 * 远端中心服路由查询客户端。
 * <p>
 * {@link RemoteCenterServer} 通过本接口解耦 HTTP / 内存桩等传输实现。
 */
public interface CenterRoutingClient {

    /**
     * 向远端中心服查询迁移计划。
     *
     * @param targetPlaneId 目标位面
     * @param targetFloorId 目标楼层
     * @return 远端返回的迁移计划（含目标 nodeId）
     */
    CenterServer.MigrationPlan planMigration(int targetPlaneId, int targetFloorId);
}
