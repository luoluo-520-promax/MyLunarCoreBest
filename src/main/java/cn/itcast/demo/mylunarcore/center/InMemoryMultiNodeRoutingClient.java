package cn.itcast.demo.mylunarcore.center;

import java.util.List;

/**
 * 无网络的多节点路由桩：按 planeId 轮转分配 nodeId，供单测与发布演练使用。
 * <p>
 * 非 Spring Bean；由测试或演练代码显式构造注入 {@link RemoteCenterServer}。
 */
public class InMemoryMultiNodeRoutingClient implements CenterRoutingClient {

    private final List<String> nodeIds;

    public InMemoryMultiNodeRoutingClient(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            throw new IllegalArgumentException("nodeIds required");
        }
        this.nodeIds = List.copyOf(nodeIds);
    }

    @Override
    public CenterServer.MigrationPlan planMigration(int targetPlaneId, int targetFloorId) {
        int zoneId = SceneRegistry.zoneId(targetPlaneId, targetFloorId);
        String nodeId = nodeIds.get(Math.floorMod(targetPlaneId, nodeIds.size()));
        return CenterServer.MigrationPlan.of(zoneId, targetPlaneId, targetFloorId, nodeId);
    }
}
