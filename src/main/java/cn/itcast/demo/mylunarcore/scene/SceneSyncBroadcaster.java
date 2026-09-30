// 场景实体位置同步广播所在包：将玩家移动通知推送给自身及 AOI 邻近在线玩家
package cn.itcast.demo.mylunarcore.scene;

// 协议命令字常量：SCENE_ENTITY_SYNC_SC_NOTIFY 对应场景实体同步下行通知
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 游戏下行数据包封装：cmdId + Protobuf 序列化 payload
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 在线玩家会话：持有 Netty Channel，用于向指定玩家推送下行包
import cn.itcast.demo.mylunarcore.player.GameSession;
// 全局会话管理器：按 uid 查找在线玩家的 Channel
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// 场景系统 Protobuf 消息定义：SceneEntitySyncScNotify 等
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
// Netty 客户端连接通道：writeAndFlush 将 GamePacket 写入网络
import io.netty.channel.Channel;
// Spring 组件注解：由 SceneNettyService 注入使用
import org.springframework.stereotype.Component;

/**
 * 场景位置同步广播器。
 * <p>
 * 当玩家移动时，将 {@link SceneSystemProto.SceneEntitySyncScNotify} 推送给：
 * <ol>
 *   <li>移动玩家自身（确认服务端已收到并校正坐标）</li>
 *   <li>同 Zone 内 AOI 九宫格范围内的其他在线玩家（让他们看到该玩家的新位置）</li>
 * </ol>
 * 跳过向自己重复发送（邻近查询已排除自身，但 selfChannel 仍会先收到一份）。
 */
@Component
public class SceneSyncBroadcaster {

    /** 按 uid 查找在线玩家 Netty Channel 的会话管理器。 */
    private final GameSessionManager sessionManager;

    /** 提供 Zone 上下文与 AOI 邻近玩家查询能力。 */
    private final ZoneManager zoneManager;

    private final SceneEntityIdAllocator entityIdAllocator;

    private final AoiAsyncProcessor aoiAsyncProcessor;

    private final SceneStateDiffer stateDiffer;

    private final LodBroadcastAdvisor lodAdvisor;

    private final PlayerBandwidthLimiter bandwidthLimiter;

    public SceneSyncBroadcaster(GameSessionManager sessionManager,
                                ZoneManager zoneManager,
                                SceneEntityIdAllocator entityIdAllocator,
                                AoiAsyncProcessor aoiAsyncProcessor,
                                SceneStateDiffer stateDiffer,
                                LodBroadcastAdvisor lodAdvisor,
                                PlayerBandwidthLimiter bandwidthLimiter) {
        this.sessionManager = sessionManager;
        this.zoneManager = zoneManager;
        this.entityIdAllocator = entityIdAllocator;
        this.aoiAsyncProcessor = aoiAsyncProcessor;
        this.stateDiffer = stateDiffer;
        this.lodAdvisor = lodAdvisor;
        this.bandwidthLimiter = bandwidthLimiter;
    }

    /**
     * 广播玩家位置更新：自身立即确认；AOI 邻近广播走异步队列，避免阻塞战斗 Tick。
     * 对邻近玩家做增量差分 + LOD + 带宽背压。
     */
    public void pushPlayerPositionUpdate(Channel selfChannel, long playerUid, int zoneId,
                                         SceneContext.ScenePos pos, float rotY) {
        SceneSystemProto.SceneEntitySyncScNotify selfNotify = buildPlayerSync(playerUid, pos, rotY, true);
        byte[] selfPayload = selfNotify.toByteArray();
        selfChannel.writeAndFlush(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, selfPayload));
        bandwidthLimiter.recordEgress(playerUid, selfPayload.length);

        aoiAsyncProcessor.submit(() -> {
            ZoneContext zone = zoneManager.get(zoneId);
            if (zone == null) {
                return;
            }
            for (Long uid : zone.nearbyPlayers(playerUid)) {
                GameSession session = sessionManager.getOrNull(uid);
                if (session == null || session.getChannel() == null || session.getChannel() == selfChannel) {
                    continue;
                }
                PlayerBandwidthLimiter.Advice advice = bandwidthLimiter.advise(uid);
                if (advice.backpressure() && advice.syncHzCap() <= 5) {
                    // 极端背压：跳过远距旁观同步
                    continue;
                }
                SceneContext.ScenePos viewerPos = zone.getPlayerPositions().get(uid);
                float vx = viewerPos == null ? pos.getX() : viewerPos.getX();
                float vz = viewerPos == null ? pos.getZ() : viewerPos.getZ();
                LodBroadcastAdvisor.LodTier lod = lodAdvisor.resolve(vx, vz, pos.getX(), pos.getZ(), false);
                SceneStateDiffer.Diff diff = stateDiffer.diffAndRemember(uid,
                        entityIdAllocator.getOrAllocate(playerUid),
                        pos.getX(), pos.getY(), pos.getZ(), rotY, 0);
                if (diff == null) {
                    continue;
                }
                boolean full = !lodAdvisor.omitOrientation(lod) || diff.isFull();
                SceneSystemProto.SceneEntitySyncScNotify notify = buildPlayerSync(playerUid, pos,
                        lodAdvisor.omitOrientation(lod) ? 0f : rotY, full);
                byte[] payload = notify.toByteArray();
                session.getChannel().writeAndFlush(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, payload));
                bandwidthLimiter.recordEgress(uid, payload.length);
            }
        });
    }

    /**
     * 推送怪物移除（sync_type=2），通知开战玩家自身。
     */
    public void pushMonsterRemoved(Channel channel, int entityId) {
        if (channel == null) {
            return;
        }
        channel.writeAndFlush(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY,
                buildMonsterSync(2, entityId, null).toByteArray()));
    }

    /** 向怪物所在 AOI 九宫格内玩家广播怪物移除。 */
    public void broadcastMonsterRemoved(int zoneId, int entityId, float x, float z) {
        broadcastToAoi(zoneId, x, z, buildMonsterSync(2, entityId, null));
    }

    /** 兼容旧调用：无坐标时退化为全 Zone。 */
    public void broadcastMonsterRemoved(int zoneId, int entityId) {
        broadcastToZone(zoneId, buildMonsterSync(2, entityId, null));
    }

    /** 向怪物所在 AOI 九宫格内玩家广播怪物出现（刷新）。 */
    public void broadcastMonsterAdded(int zoneId, ZoneContext.ZoneMonster monster) {
        if (monster == null) {
            return;
        }
        broadcastToAoi(zoneId, monster.getPos().getX(), monster.getPos().getZ(),
                buildMonsterSync(1, monster.getEntityId(), monster));
    }

    private void broadcastToAoi(int zoneId, float x, float z, SceneSystemProto.SceneEntitySyncScNotify notify) {
        ZoneContext zone = zoneManager.get(zoneId);
        if (zone == null) {
            return;
        }
        byte[] payload = notify.toByteArray();
        for (Long uid : zone.nearbyPlayersAt(x, z, 0L)) {
            GameSession session = sessionManager.getOrNull(uid);
            if (session != null && session.getChannel() != null) {
                session.getChannel().writeAndFlush(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, payload));
            }
        }
    }

    private void broadcastToZone(int zoneId, SceneSystemProto.SceneEntitySyncScNotify notify) {
        ZoneContext zone = zoneManager.get(zoneId);
        if (zone == null) {
            return;
        }
        byte[] payload = notify.toByteArray();
        for (Long uid : zone.getPlayerUids()) {
            GameSession session = sessionManager.getOrNull(uid);
            if (session != null && session.getChannel() != null) {
                session.getChannel().writeAndFlush(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, payload));
            }
        }
    }

    /** AOI 推送 NPC 日程行为（动画状态机切换）。 */
    public void broadcastNpcBehavior(int zoneId, float x, float z,
                                       SceneSystemProto.SceneNpcBehaviorScNotify notify) {
        if (notify == null) {
            return;
        }
        ZoneContext zone = zoneManager.get(zoneId);
        if (zone == null) {
            return;
        }
        byte[] payload = notify.toByteArray();
        GamePacket packet = new GamePacket(CmdIds.SCENE_NPC_BEHAVIOR_SC_NOTIFY, payload);
        var recipients = zone.nearbyPlayersAt(x, z, 0L);
        if (recipients.isEmpty()) {
            recipients = zone.getPlayerUids();
        }
        for (Long uid : recipients) {
            GameSession session = sessionManager.getOrNull(uid);
            if (session != null && session.getChannel() != null) {
                session.getChannel().writeAndFlush(packet);
            }
        }
    }

    private static SceneSystemProto.SceneEntitySyncScNotify buildMonsterSync(
            int syncType, int entityId, ZoneContext.ZoneMonster monster) {
        SceneSystemProto.SceneEntityData.Builder entity = SceneSystemProto.SceneEntityData.newBuilder()
                .setEntityId(entityId)
                .setEntityType(1);
        if (monster != null) {
            entity.setEntityInfo(SceneSystemProto.SceneEntityInfo.newBuilder()
                    .setMonster(SceneSystemProto.MonsterEntityInfo.newBuilder()
                            .setMonsterId(monster.getMonsterId())
                            .setLevel(monster.getLevel())
                            .setHp(monster.getHp())
                            .setMaxHp(monster.getMaxHp())
                            .setPos(SceneSystemProto.SceneVec3.newBuilder()
                                    .setX(monster.getPos().getX())
                                    .setY(monster.getPos().getY())
                                    .setZ(monster.getPos().getZ())
                                    .build())
                            .build())
                    .build());
        }
        return SceneSystemProto.SceneEntitySyncScNotify.newBuilder()
                .setSyncType(syncType)
                .addEntityData(entity.build())
                .build();
    }

    private SceneSystemProto.SceneEntitySyncScNotify buildPlayerSync(long playerUid, SceneContext.ScenePos pos, float rotY) {
        return buildPlayerSync(playerUid, pos, rotY, true);
    }

    private SceneSystemProto.SceneEntitySyncScNotify buildPlayerSync(long playerUid, SceneContext.ScenePos pos,
                                                                      float rotY, boolean includeRot) {
        int entityId = entityIdAllocator.getOrAllocate(playerUid);
        SceneSystemProto.PlayerEntityInfo.Builder player = SceneSystemProto.PlayerEntityInfo.newBuilder()
                .setPlayerUid(playerUid)
                .setPos(SceneSystemProto.SceneVec3.newBuilder()
                        .setX(pos.getX())
                        .setY(pos.getY())
                        .setZ(pos.getZ())
                        .build());
        if (includeRot) {
            player.setRotY(rotY);
        }
        SceneSystemProto.SceneEntityData entity = SceneSystemProto.SceneEntityData.newBuilder()
                .setEntityId(entityId)
                .setEntityType(5)
                .setEntityInfo(SceneSystemProto.SceneEntityInfo.newBuilder()
                        .setPlayer(player.build())
                        .build())
                .build();
        return SceneSystemProto.SceneEntitySyncScNotify.newBuilder()
                .setSyncType(3)
                .addEntityData(entity)
                .build();
    }
}
