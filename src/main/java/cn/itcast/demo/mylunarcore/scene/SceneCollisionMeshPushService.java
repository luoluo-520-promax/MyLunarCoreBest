package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

/**
 * 进场景时向客户端推送阻挡网格与胶囊体参数，减少穿模后再回滚。
 */
@Component
public class SceneCollisionMeshPushService {

    private final SceneCollisionProxy collisionProxy;
    private final LunarCoreProperties properties;

    public SceneCollisionMeshPushService(SceneCollisionProxy collisionProxy,
                                         LunarCoreProperties properties) {
        this.collisionProxy = collisionProxy;
        this.properties = properties;
    }

    public void pushOnEnter(Channel channel, int planeId) {
        if (channel == null || !channel.isActive() || !properties.getAntiCheat().isCollisionCheckEnabled()) {
            return;
        }
        SceneCollisionProxy.Capsule cap = collisionProxy.playerCapsule();
        SceneSystemProto.SceneCollisionMeshScNotify.Builder b =
                SceneSystemProto.SceneCollisionMeshScNotify.newBuilder()
                        .setPlaneId(planeId)
                        .setCapsuleRadius(cap.radius())
                        .setCapsuleHeight(cap.height())
                        .setSmoothCorrection(properties.getAntiCheat().isSmoothPositionCorrection());
        for (SceneCollisionProxy.BlockerPolygon poly : collisionProxy.blockersForClient(planeId)) {
            SceneSystemProto.CollisionPolygon.Builder pb = SceneSystemProto.CollisionPolygon.newBuilder();
            for (float[] v : poly.vertices()) {
                if (v != null && v.length >= 2) {
                    pb.addXz(v[0]).addXz(v[1]);
                }
            }
            if (pb.getXzCount() >= 6) {
                b.addBlockers(pb);
            }
        }
        channel.writeAndFlush(new GamePacket(CmdIds.SCENE_COLLISION_MESH_SC_NOTIFY, b.build().toByteArray()));
    }
}
