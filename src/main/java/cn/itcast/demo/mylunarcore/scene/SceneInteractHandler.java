package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.exploration.ExplorationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 场景交互钩子：开启宝箱、完成解密等事件统一转发到 {@link ExplorationService}。
 */
@Component
public class SceneInteractHandler {

    private final ObjectProvider<ExplorationService> explorationProvider;

    public SceneInteractHandler(ObjectProvider<ExplorationService> explorationProvider) {
        this.explorationProvider = explorationProvider;
    }

    public void onChestOpened(int playerId, int planeId, int floorId, String chestId) {
        ExplorationService svc = explorationProvider.getIfAvailable();
        if (svc != null) {
            svc.onSceneInteract(playerId, planeId, floorId, chestId, "chest");
        }
    }

    public void onPuzzleSolved(int playerId, int planeId, int floorId, String puzzleId) {
        ExplorationService svc = explorationProvider.getIfAvailable();
        if (svc != null) {
            svc.onSceneInteract(playerId, planeId, floorId, puzzleId, "puzzle");
        }
    }

    public void onViewpointUnlocked(int playerId, int planeId, int floorId, String viewId) {
        ExplorationService svc = explorationProvider.getIfAvailable();
        if (svc != null) {
            svc.onSceneInteract(playerId, planeId, floorId, viewId, "viewpoint");
        }
    }

    public void onPropPickup(int playerId, int planeId, int floorId, String entityId) {
        ExplorationService svc = explorationProvider.getIfAvailable();
        if (svc != null) {
            svc.onSceneInteract(playerId, planeId, floorId, entityId, "pickup");
        }
    }
}
