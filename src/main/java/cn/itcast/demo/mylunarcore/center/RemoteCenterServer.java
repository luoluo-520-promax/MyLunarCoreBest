package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 远端中心服适配：迁移计划由 {@link CenterRoutingClient} 向中心节点查询，
 * 本进程仅判断目标 nodeId 是否与 {@code lunarcore.center.local-node-id} 一致。
 */
@Service
@ConditionalOnProperty(prefix = "lunarcore.center", name = "mode", havingValue = "remote")
public class RemoteCenterServer implements CenterServer {

    private final CenterRoutingClient routingClient;
    private final String localNodeId;

    public RemoteCenterServer(CenterRoutingClient routingClient, LunarCoreProperties properties) {
        this.routingClient = routingClient;
        String configured = properties.getCenter().getLocalNodeId();
        this.localNodeId = (configured == null || configured.isBlank()) ? "local" : configured.trim();
    }

    /**
     * 测试构造。
     */
    public RemoteCenterServer(CenterRoutingClient routingClient, String localNodeId) {
        this.routingClient = routingClient;
        this.localNodeId = (localNodeId == null || localNodeId.isBlank()) ? "local" : localNodeId.trim();
    }

    @Override
    public MigrationPlan planMigration(int targetPlaneId, int targetFloorId) {
        MigrationPlan plan = routingClient.planMigration(targetPlaneId, targetFloorId);
        if (plan == null) {
            throw new IllegalStateException("remote center returned null MigrationPlan");
        }
        return plan;
    }

    @Override
    public boolean isLocalNode(String nodeId) {
        return nodeId == null || localNodeId.equals(nodeId);
    }

    @Override
    public String localNodeId() {
        return localNodeId;
    }
}
