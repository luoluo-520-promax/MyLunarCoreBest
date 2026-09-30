package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleNettyService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import org.slf4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 服务端遭遇引擎：ZoneTick 刷新后的仇恨判定 → FightStart。
 */
@Service
public class WorldEncounterService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, WorldEncounterService.class);
    private static final long ENCOUNTER_COOLDOWN_MS = 3_000L;
    private static final int DEFAULT_LINEUP_ID = 1;

    private final ZoneManager zoneManager;
    private final GameSessionManager sessionManager;
    private final BattleManager battleManager;
    private final BattleNettyService battleNettyService;
    private final SceneManager sceneManager;

    public WorldEncounterService(ZoneManager zoneManager,
                                 GameSessionManager sessionManager,
                                 BattleManager battleManager,
                                 @Lazy BattleNettyService battleNettyService,
                                 SceneManager sceneManager) {
        this.zoneManager = zoneManager;
        this.sessionManager = sessionManager;
        this.battleManager = battleManager;
        this.battleNettyService = battleNettyService;
        this.sceneManager = sceneManager;
    }

    /**
     * ZoneTick：对区内每个 SCENE 状态玩家做仇恨判定（怪物刷新已在 ZoneTickService 完成）。
     */
    public void onZoneTick(ZoneContext zone, long nowMillis) {
        if (zone == null) {
            return;
        }
        for (Long playerUid : zone.getPlayerUids()) {
            SceneContext scene = sceneManager.getByPlayerUid(playerUid);
            if (scene == null || !scene.isInitialized()) {
                continue;
            }
            tryEncounterForPlayer(scene, zone, nowMillis);
        }
    }

    /**
     * @deprecated 请使用 ZoneTick；保留仅兼容旧调用路径。
     */
    @Deprecated
    public void onSceneTick(SceneContext scene, long nowMillis) {
        if (scene == null || !scene.isInitialized()) {
            return;
        }
        ZoneContext zone = zoneManager.get(scene.getZoneId());
        if (zone == null) {
            return;
        }
        tryEncounterForPlayer(scene, zone, nowMillis);
    }

    private void tryEncounterForPlayer(SceneContext scene, ZoneContext zone, long nowMillis) {
        GameSession session = sessionManager.getOrNull(scene.getPlayerUid());
        if (session == null || session.getSessionState() != PlayerSessionState.SCENE) {
            return;
        }
        if (battleManager.findActiveByPlayerId((int) (scene.getPlayerUid() & 0xffffffffL)) != null) {
            return;
        }
        if (nowMillis < scene.getEncounterCooldownUntilMillis()) {
            return;
        }

        SceneContext.ScenePos playerPos = scene.getPlayerPos();
        for (ZoneContext.ZoneMonster monster : zone.getMonsters().values()) {
            if (!monster.isAlive() || !monster.isAggressive()) {
                continue;
            }
            float dx = playerPos.getX() - monster.getPos().getX();
            float dz = playerPos.getZ() - monster.getPos().getZ();
            float radius = monster.getAggroRadius() > 0 ? monster.getAggroRadius() : 5f;
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            triggerEncounter(scene, session, monster, nowMillis);
            return;
        }
    }

    private void triggerEncounter(SceneContext scene, GameSession session,
                                  ZoneContext.ZoneMonster monster, long nowMillis) {
        if (session.getChannel() == null) {
            return;
        }
        scene.setEncounterCooldownUntilMillis(nowMillis + ENCOUNTER_COOLDOWN_MS);
        scene.setLastAutoEncounterEntityId(monster.getEntityId());

        BattleSystemProto.FightStartCsReq req = BattleSystemProto.FightStartCsReq.newBuilder()
                .setSceneEntityUid(monster.getEntityId())
                .setBattleStageId(0)
                .setLineupId(DEFAULT_LINEUP_ID)
                .build();
        BattleSystemProto.FightStartScRsp rsp = battleNettyService.handleFightStart(req, session.getChannel());
        if (rsp.getRetcode() == 0) {
            session.getChannel().writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                    cn.itcast.demo.mylunarcore.net.CmdIds.FIGHT_START_SC_RSP, rsp.toByteArray()));
            log.info("auto encounter started, uid={}, entityId={}, monsterId={}, battleId={}",
                    scene.getPlayerUid(), monster.getEntityId(), monster.getMonsterId(), rsp.getBattleId());
        } else {
            log.debug("auto encounter skipped, uid={}, entityId={}, retcode={}",
                    scene.getPlayerUid(), monster.getEntityId(), rsp.getRetcode());
        }
    }
}
