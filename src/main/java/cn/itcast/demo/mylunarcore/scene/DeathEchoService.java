package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 世界场景死亡荧光残影：只存坐标，30 分钟过期；路过玩家可抚慰并发鼓励邮件。
 */
@Service
public class DeathEchoService {

    public static final long TTL_MS = 30 * 60_000L;

    public record Echo(String echoId, long ownerUid, int planeId, float x, float y, float z, long expireAtMs) {}

    private final Map<String, Echo> echoes = new ConcurrentHashMap<>();
    private final Map<String, java.util.Set<Long>> comforts = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;
    private final MailRepository mailRepository;

    public DeathEchoService(GameSessionManager sessionManager,
                            ObjectProvider<MailRepository> mailRepositoryProvider) {
        this.sessionManager = sessionManager;
        MailRepository repo = mailRepositoryProvider == null ? null : mailRepositoryProvider.getIfAvailable();
        this.mailRepository = repo;
    }

    public Echo spawn(long ownerUid, int planeId, float x, float y, float z) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Echo echo = new Echo(id, ownerUid, planeId, x, y, z, System.currentTimeMillis() + TTL_MS);
        echoes.put(id, echo);
        broadcast(List.of(echo), "spawn");
        return echo;
    }

    public List<Echo> listActive(int planeId) {
        long now = System.currentTimeMillis();
        List<Echo> out = new ArrayList<>();
        for (Echo e : echoes.values()) {
            if (e.planeId() == planeId && e.expireAtMs() > now) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * @return 0成功 2不存在 3抚慰自己 4已抚慰
     */
    public int comfort(long comforterUid, String echoId) {
        Echo echo = echoes.get(echoId);
        if (echo == null || echo.expireAtMs() <= System.currentTimeMillis()) {
            return 2;
        }
        if (echo.ownerUid() == comforterUid) {
            return 3;
        }
        java.util.Set<Long> set = comforts.computeIfAbsent(echoId, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(comforterUid)) {
            return 4;
        }
        if (mailRepository != null) {
            long expire = System.currentTimeMillis() + 7L * 24 * 3600_000L;
            mailRepository.insertMail((int) echo.ownerUid(),
                    "来自路人的抚慰",
                    "有旅人在你倒下的悬崖边留下了鼓励，愿你再度启程。",
                    "[]",
                    new Timestamp(expire));
        }
        GameSession owner = sessionManager.getOrNull((int) echo.ownerUid());
        if (owner != null) {
            // 在线时也可即时感知
            broadcast(List.of(echo), "comfort");
        }
        return 0;
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        List<Echo> expired = new ArrayList<>();
        Iterator<Map.Entry<String, Echo>> it = echoes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Echo> e = it.next();
            if (e.getValue().expireAtMs() <= now) {
                expired.add(e.getValue());
                it.remove();
                comforts.remove(e.getKey());
            }
        }
        if (!expired.isEmpty()) {
            broadcast(expired, "expire");
        }
    }

    private void broadcast(List<Echo> list, String reason) {
        SceneSystemProto.SceneDeathEchoSyncScNotify.Builder b =
                SceneSystemProto.SceneDeathEchoSyncScNotify.newBuilder().setReason(reason);
        for (Echo e : list) {
            b.addEchoes(SceneSystemProto.SceneDeathEcho.newBuilder()
                    .setEchoId(e.echoId())
                    .setOwnerUid(e.ownerUid())
                    .setPlaneId(e.planeId())
                    .setPos(SceneSystemProto.SceneVec3.newBuilder()
                            .setX(e.x()).setY(e.y()).setZ(e.z()).build())
                    .setExpireAtMs(e.expireAtMs())
                    .build());
        }
        GamePacket packet = new GamePacket(CmdIds.SCENE_DEATH_ECHO_SYNC_SC_NOTIFY, b.build().toByteArray());
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session != null) {
                session.send(packet);
            }
        }
    }
}
