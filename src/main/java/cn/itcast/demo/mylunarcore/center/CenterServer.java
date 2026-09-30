package cn.itcast.demo.mylunarcore.center;

/**
 * 中心服路由决策抽象。
 */
public interface CenterServer {

    /**
     * 场景迁移计划。
     *
     * @param nodeHost 目标节点对外地址（跨节点重连用；本机可空）
     * @param nodePort 目标节点游戏端口
     */
    record MigrationPlan(int zoneId, int planeId, int floorId, String nodeId,
                         String nodeHost, int nodePort) {
        public MigrationPlan {
            if (nodeId == null) {
                nodeId = "";
            }
            if (nodeHost == null) {
                nodeHost = "";
            }
        }

        public static MigrationPlan of(int zoneId, int planeId, int floorId, String nodeId) {
            return new MigrationPlan(zoneId, planeId, floorId, nodeId, "", 0);
        }
    }

    MigrationPlan planMigration(int targetPlaneId, int targetFloorId);

    boolean isLocalNode(String nodeId);

    String localNodeId();
}
