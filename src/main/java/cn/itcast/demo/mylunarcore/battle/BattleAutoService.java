package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy;
import cn.itcast.demo.mylunarcore.battle.assist.BattleStateVectorSerializer;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动战斗执行器：玩家开启 Auto 或掉线后，按 {@link BattleAssistPolicy} 真正出手，而非只发提示。
 */
@Service
public class BattleAutoService {

    public static final long BASE_AUTO_WAIT_MS = 1_500L;
    /** 大招预估施法时长；客户端可据此提前播蓄力 */
    public static final int ULT_ESTIMATED_CAST_MS = 800;
    /** 允许客户端在 ScRsp 到达前预表现的高亮闪屏 */
    public static final int CLIENT_PRE_FX_MS = 200;

    public record AutoAction(int skillId, int casterId, List<Integer> targetIds, int hpDelta, String reason,
                             int waitMs, int estimatedCastMs) {
        public AutoAction(int skillId, int casterId, List<Integer> targetIds, int hpDelta, String reason,
                          int waitMs) {
            this(skillId, casterId, targetIds, hpDelta, reason, waitMs, 0);
        }
    }

    private final BattleManager battleManager;
    private final BattleAssistPolicy battleAssistPolicy;
    private final GameSessionManager sessionManager;
    private final BattleTurnPredictor turnPredictor;
    private final ObjectProvider<BattleAutoWeakNetService> weakNetProvider;

    public BattleAutoService(BattleManager battleManager,
                             BattleAssistPolicy battleAssistPolicy,
                             GameSessionManager sessionManager,
                             BattleTurnPredictor turnPredictor,
                             ObjectProvider<BattleAutoWeakNetService> weakNetProvider) {
        this.battleManager = battleManager;
        this.battleAssistPolicy = battleAssistPolicy;
        this.sessionManager = sessionManager;
        this.turnPredictor = turnPredictor;
        this.weakNetProvider = weakNetProvider;
    }

    /** 单测便捷：内建回合预计算。 */
    public BattleAutoService(BattleManager battleManager,
                             BattleAssistPolicy battleAssistPolicy,
                             GameSessionManager sessionManager) {
        this(battleManager, battleAssistPolicy, sessionManager, new BattleTurnPredictor(), null);
    }

    public BattleAutoService(BattleManager battleManager,
                             BattleAssistPolicy battleAssistPolicy,
                             GameSessionManager sessionManager,
                             BattleTurnPredictor turnPredictor) {
        this(battleManager, battleAssistPolicy, sessionManager, turnPredictor, null);
    }

    public boolean enableAuto(long battleId, int playerId, boolean enabled, String reason) {
        return enableAuto(battleId, playerId, enabled, reason, 0);
    }

    public boolean enableAuto(long battleId, int playerId, boolean enabled, String reason, int strategy) {
        return enableAuto(battleId, playerId, enabled, reason, strategy, 0);
    }

    public boolean enableAuto(long battleId, int playerId, boolean enabled, String reason, int strategy, int targetFocus) {
        BattleContext ctx = battleManager.get(battleId);
        if (ctx == null || ctx.isEnded() || !ctx.isParticipant(playerId)) {
            return false;
        }
        synchronized (ctx.getLock()) {
            if (enabled && !ctx.setAutoStrategy(strategy)) {
                return false;
            }
            if (enabled && !ctx.setTargetFocus(targetFocus)) {
                return false;
            }
            ctx.setAutoBattle(enabled, enabled ? reason : "");
            if (enabled) {
                ctx.scheduleNextAutoAction(resolveWaitMs(ctx));
            }
        }
        battleManager.checkpoint(ctx);
        return true;
    }

    /**
     * Auto 中手动插入大招：抢占式清空 1500ms 倒计时，立即结算后恢复 Auto。
     */
    public AutoAction executeManualUlt(long battleId, int playerId, int skillId, int casterId,
                                       java.util.List<Integer> targetIds) {
        BattleContext ctx = battleManager.get(battleId);
        if (ctx == null || ctx.isEnded() || !ctx.isParticipant(playerId) || !ctx.isAutoBattle()) {
            return null;
        }
        synchronized (ctx.getLock()) {
            ctx.queueManualUlt(skillId, casterId, targetIds);
        }
        AutoAction action = executeOneTurn(ctx);
        if (action != null) {
            action = new AutoAction(action.skillId(), action.casterId(), action.targetIds(),
                    action.hpDelta(), action.reason(), action.waitMs(), ULT_ESTIMATED_CAST_MS);
            pushNotify(ctx, action, true);
        }
        return action;
    }

    /** 掉线：若仍有进行中战局则托管自动，不中断战斗。 */
    public void enableForDisconnect(int playerId) {
        BattleContext ctx = battleManager.findActiveByPlayerId(playerId);
        if (ctx == null || ctx.isEnded()) {
            return;
        }
        synchronized (ctx.getLock()) {
            ctx.setAutoBattle(true, "disconnect");
            ctx.scheduleNextAutoAction(resolveWaitMs(ctx));
        }
        battleManager.checkpoint(ctx);
    }

    private long resolveWaitMs(BattleContext ctx) {
        BattleAutoWeakNetService weak = weakNetProvider == null ? null : weakNetProvider.getIfAvailable();
        if (weak != null) {
            return weak.resolveAutoWaitMs(ctx);
        }
        return BASE_AUTO_WAIT_MS;
    }

    public boolean setSpeed(long battleId, int playerId, int multiplier) {
        BattleContext ctx = battleManager.get(battleId);
        if (ctx == null || !ctx.isParticipant(playerId)) {
            return false;
        }
        boolean ok;
        synchronized (ctx.getLock()) {
            ok = ctx.setSpeedMultiplier(multiplier);
        }
        if (ok) {
            battleManager.checkpoint(ctx);
        }
        return ok;
    }

    @Scheduled(fixedDelay = 400)
    public void tick() {
        long now = System.currentTimeMillis();
        List<BattleContext> due = new ArrayList<>();
        for (BattleContext ctx : battleManager.snapshotActive()) {
            if (ctx == null || ctx.isEnded() || !ctx.isAutoBattle()) {
                continue;
            }
            if (now < ctx.getNextActionAtMs()) {
                continue;
            }
            // 按托管原因更新优先级：掉线托管降频
            BattleInstancePool.Priority p = "disconnect".equals(ctx.getAutoReason())
                    ? BattleInstancePool.Priority.OFFLINE_HOSTED
                    : BattleInstancePool.Priority.ONLINE_AUTO;
            battleManager.instancePool().updatePriority(ctx.getBattleId(), p);
            due.add(ctx);
        }
        battleManager.instancePool().dispatchTick(due, now, ctx -> {
            // 空闲预计算下一回合候选
            turnPredictor.precompute(ctx, ctx.getPlayerId(), List.of(1, 2, 3));
            AutoAction action = executeOneTurn(ctx);
            if (action != null) {
                pushNotify(ctx, action, false);
            }
        });
    }

    /**
     * 按启发式策略选择技能/目标并在服务端结算（普攻或技能），权威不交给客户端。
     */
    public AutoAction executeOneTurn(BattleContext ctx) {
        if (ctx == null || ctx.isEnded()) {
            return null;
        }
        synchronized (ctx.getLock()) {
            if (ctx.isEnded()) {
                return null;
            }
            int casterId = ctx.getPlayerId();
            boolean manual = ctx.hasPendingManualUlt();
            int skillId;
            List<Integer> targets = new ArrayList<>();
            BattleAssistPolicy.Suggestion suggestion = null;
            if (manual) {
                skillId = ctx.consumePendingManualSkillId();
                casterId = ctx.getPendingManualCasterId() > 0 ? ctx.getPendingManualCasterId() : casterId;
                targets.addAll(ctx.snapshotPendingManualTargets());
            } else {
                suggestion = battleAssistPolicy.suggest(ctx, casterId);
                skillId = pickSkillByStrategy(ctx.getAutoStrategy(), suggestion);
            }
            if (targets.isEmpty() && suggestion != null && suggestion.targetIds() != null) {
                targets.addAll(suggestion.targetIds());
            }
            if (targets.isEmpty()) {
                targets.addAll(ctx.listAliveMonsterIdsByFocus(ctx.getTargetFocus()));
            } else if (ctx.getTargetFocus() > 0) {
                // 策略建议有目标时，仍按 focus 把精英/破盾目标顶到队首
                List<Integer> focused = ctx.listAliveMonsterIdsByFocus(ctx.getTargetFocus());
                if (!focused.isEmpty()) {
                    targets = new ArrayList<>(focused);
                }
            }
            if (targets.isEmpty()) {
                ctx.setAutoBattle(false, "cleared");
                return null;
            }
            int targetId = targets.get(0);
            EntityState entity = ctx.getEntity(targetId);
            long waitBase = resolveWaitMs(ctx);
            if (entity == null || entity.isDead()) {
                ctx.scheduleNextAutoAction(waitBase);
                return new AutoAction(skillId, casterId, List.of(targetId), 0,
                        manual ? "manual_ult" : (suggestion == null ? "heuristic:empty" : suggestion.reason()),
                        (int) ctx.compressedWaitMs(waitBase));
            }
            int dmg = skillId >= 3 ? 240 : (skillId >= 2 ? 160 : 80);
            if (ctx.getAutoStrategy() == 3 && !manual) {
                dmg = 80;
            }
            int before = entity.getHp();
            int after = Math.max(0, before - dmg);
            entity.setHp(after);
            if (after == 0) {
                entity.setDead(true);
            }
            ctx.incrementTurn();
            BattleStateVectorSerializer.onSkillUsed(ctx, skillId);
            if (manual && ctx.consumeRevertAutoAfterMicro()) {
                ctx.setAutoStrategy(ctx.getSavedAutoStrategy());
                ctx.setTargetFocus(ctx.getSavedTargetFocus());
            }
            ctx.scheduleNextAutoAction(waitBase);
            String reason = manual ? "manual_ult:skill=" + skillId
                    : (suggestion == null || suggestion.reason().isBlank()
                    ? "heuristic:auto_skill=" + skillId : suggestion.reason());
            return new AutoAction(skillId, casterId, List.of(targetId), after - before, reason,
                    (int) ctx.compressedWaitMs(waitBase));
        }
    }

    private static int pickSkillByStrategy(int strategy, BattleAssistPolicy.Suggestion suggestion) {
        if (strategy == 2) {
            return 1;
        }
        if (strategy == 3) {
            return 1;
        }
        if (suggestion != null && suggestion.skillId() > 0) {
            return Math.max(2, suggestion.skillId());
        }
        return 2;
    }

    private void pushNotify(BattleContext ctx, AutoAction action, boolean manualOverride) {
        BattleSystemProto.BattleAutoScNotify notify = BattleSystemProto.BattleAutoScNotify.newBuilder()
                .setBattleId(ctx.getBattleId())
                .setSkillId(action.skillId())
                .setCasterId(action.casterId())
                .addAllTargetIds(action.targetIds())
                .setReason(action.reason() == null ? "" : action.reason())
                .setSpeedMultiplier(ctx.getSpeedMultiplier())
                .setNextWaitMs(action.waitMs())
                .setAutoStrategy(BattleSystemProto.BattleAutoStrategy.forNumber(ctx.getAutoStrategy()) == null
                        ? BattleSystemProto.BattleAutoStrategy.PRIORITY_SKILL
                        : BattleSystemProto.BattleAutoStrategy.forNumber(ctx.getAutoStrategy()))
                .setManualOverride(manualOverride)
                .build();
        GamePacket packet = new GamePacket(CmdIds.BATTLE_AUTO_SC_NOTIFY, notify.toByteArray());
        for (Integer pid : ctx.getParticipantPlayerIds()) {
            GameSession session = sessionManager.getOrNull(pid);
            if (session != null) {
                session.send(packet);
            }
        }
        BattleAutoWeakNetService weak = weakNetProvider == null ? null : weakNetProvider.getIfAvailable();
        if (weak != null) {
            weak.markActionPushed(ctx.getBattleId());
            for (Integer pid : ctx.getParticipantPlayerIds()) {
                weak.pushTickProgress(ctx, pid, "action");
            }
        }
    }
}
