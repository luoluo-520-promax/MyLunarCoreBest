package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 进程内中心服：Zone 登记在本地 {@link SceneRegistry}，nodeId 为本机标识。
 */
@Service
@ConditionalOnProperty(prefix = "lunarcore.center", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalCenterServer implements CenterServer {

    private final SceneRegistry sceneRegistry;
    private final String localNodeId;
    private final String advertiseHost;
    private final int advertisePort;

    public LocalCenterServer(SceneRegistry sceneRegistry, LunarCoreProperties properties) {
        this.sceneRegistry = sceneRegistry;
        String configured = properties.getCenter().getLocalNodeId();
        this.localNodeId = (configured == null || configured.isBlank()) ? "local" : configured.trim();
        this.advertiseHost = properties.getCenter().getAdvertiseHost() == null
                ? "127.0.0.1" : properties.getCenter().getAdvertiseHost().trim();
        int port = properties.getCenter().getAdvertisePort();
        this.advertisePort = port > 0 ? port : properties.getNettyPort();
    }

    public LocalCenterServer(SceneRegistry sceneRegistry, String localNodeId) {
        this.sceneRegistry = sceneRegistry;
        this.localNodeId = (localNodeId == null || localNodeId.isBlank()) ? "local" : localNodeId.trim();
        this.advertiseHost = "127.0.0.1";
        this.advertisePort = 9000;
    }

    @Override
    public MigrationPlan planMigration(int targetPlaneId, int targetFloorId) {
        SceneRegistry.ZoneInfo zone = sceneRegistry.register(targetPlaneId, targetFloorId, localNodeId);
        // 计划迁移时续约，避免租约空闲过期
        sceneRegistry.heartbeat(zone.zoneId(), localNodeId, zone.playerCountHint());
        return new MigrationPlan(zone.zoneId(), zone.planeId(), zone.floorId(), zone.nodeId(),
                advertiseHost, advertisePort);
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
