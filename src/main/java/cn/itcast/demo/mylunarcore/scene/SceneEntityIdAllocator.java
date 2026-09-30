package cn.itcast.demo.mylunarcore.scene;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景玩家实体 ID 分配器：替代 {@code playerUid % Integer.MAX_VALUE}，降低碰撞风险。
 * <p>与 SceneNettyService 怪物 entityIdSeed（自 1000000）分区：玩家从 5_000_000 起分配。</p>
 */
@Component
public class SceneEntityIdAllocator {

    private static final int PLAYER_ENTITY_ID_BASE = 5_000_000;

    private final AtomicInteger nextId = new AtomicInteger(PLAYER_ENTITY_ID_BASE);
    private final ConcurrentHashMap<Long, Integer> playerEntityIds = new ConcurrentHashMap<>();

    public int allocateForPlayer(long playerUid) {
        return playerEntityIds.computeIfAbsent(playerUid, uid -> nextId.getAndIncrement());
    }

    public int getOrAllocate(long playerUid) {
        return allocateForPlayer(playerUid);
    }

    public void release(long playerUid) {
        playerEntityIds.remove(playerUid);
    }
}
