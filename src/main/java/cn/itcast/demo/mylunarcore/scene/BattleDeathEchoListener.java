package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 战斗失败时在场景落点留下荧光残影。
 */
@Component
public class BattleDeathEchoListener {

    private final DeathEchoService deathEchoService;
    private final SceneManager sceneManager;

    public BattleDeathEchoListener(DeathEchoService deathEchoService, SceneManager sceneManager) {
        this.deathEchoService = deathEchoService;
        this.sceneManager = sceneManager;
    }

    @EventListener
    public void onBattleEnded(BattleEndedEvent event) {
        if (event == null || event.endStatus() == 1) {
            return;
        }
        long uid = event.playerId();
        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (ctx == null || !ctx.isInitialized()) {
            return;
        }
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        deathEchoService.spawn(uid, ctx.getPlaneId(), pos.getX(), pos.getY(), pos.getZ());
    }
}
