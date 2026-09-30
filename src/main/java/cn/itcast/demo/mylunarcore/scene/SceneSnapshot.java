package cn.itcast.demo.mylunarcore.scene;

import java.util.List;

/**
 * 切服/断线用场景会话快照（与战斗快照互补）。
 */
public record SceneSnapshot(
        long playerUid,
        int planeId,
        int floorId,
        int zoneId,
        int entryId,
        float posX,
        float posY,
        float posZ,
        int lineUpId,
        List<Integer> avatarIds,
        long savedAtMs
) {}
