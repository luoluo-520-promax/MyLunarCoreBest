package cn.itcast.demo.mylunarcore.player; // 玩法会话清理：离开场景/战斗后回到主界面或下线

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager; // 清理进行中战局
import cn.itcast.demo.mylunarcore.rogue.RogueManager; // 清理进行中 Rogue
import cn.itcast.demo.mylunarcore.scene.SceneContext; // 读取 plane/floor 以离开 Zone
import cn.itcast.demo.mylunarcore.scene.SceneManager; // 移除个人场景上下文
import cn.itcast.demo.mylunarcore.scene.ZoneManager; // 从共享 Zone/AOI 移除玩家
import org.springframework.stereotype.Service; // Spring 服务

/**
 * 玩法运行时清理：离开场景 Zone、移除 SceneContext、清理战斗与 Rogue。
 * <p>供「返回主界面」与「退出游戏/断线」共用，避免场景幽灵实体。
 */
@Service
public class PlaySessionCleanupService {

    /**
     * 玩家个人场景索引
     */
    private final SceneManager sceneManager;
    /**
     * 共享场景 Zone / AOI
     */
    private final ZoneManager zoneManager;
    /**
     * 进行中战局索引
     */
    private final BattleManager battleManager;
    /**
     * 进行中 Rogue 局索引
     */
    private final RogueManager rogueManager;

    /**
     * 构造注入场景、战斗、Rogue 运行时管理器
     */
    public PlaySessionCleanupService(SceneManager sceneManager,
                                     ZoneManager zoneManager,
                                     BattleManager battleManager,
                                     RogueManager rogueManager) {
        this.sceneManager = sceneManager;
        this.zoneManager = zoneManager;
        this.battleManager = battleManager;
        this.rogueManager = rogueManager;
    }

    /**
     * 清理指定玩家当前玩法运行时（场景 + 战斗 + Rogue），不改会话状态、不断开连接。
     *
     * @param uid 玩家 uid
     */
    public void leaveCurrentPlay(long uid) {
        leaveCurrentPlay(uid, true);
    }

    /**
     * @param abortBattle true=中断战斗（回主界面）；false=掉线托管自动战斗
     */
    public void leaveCurrentPlay(long uid, boolean abortBattle) {
        SceneContext ctx = sceneManager.getByPlayerUid(uid);
        if (ctx != null) {
            zoneManager.leaveZone(ctx.getZoneId(), uid);
            sceneManager.remove(uid);
        }
        int playerId = (int) uid;
        if (abortBattle) {
            battleManager.removeByPlayerId(playerId);
        } else {
            BattleContext active = battleManager.findActiveByPlayerId(playerId);
            if (active != null && !active.isEnded()) {
                synchronized (active.getLock()) {
                    active.setAutoBattle(true, "disconnect");
                    active.scheduleNextAutoAction(1_500L);
                }
            }
        }
        if (rogueManager.get(playerId) != null) {
            rogueManager.remove(playerId);
        }
    }
}
