package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import org.springframework.stereotype.Component;

/**
 * 服务器端确定性校验：拒绝客户端传回的伤害数值，按双方属性重跑公式后只同步结果。
 * <p>
 * 相对 {@link BattleAuditService} 的事后日志，本类在结算路径上做权威裁决。
 * 另校验客户端「顿帧结束时间戳」，防止外挂缩短 Hit-stop 变相提速输出。
 */
@Component
public class BattleDeterministicValidator {

    /** 允许客户端上报顿帧结束时间相对权威值的误差（毫秒，含网络抖动）。 */
    public static final long HIT_STOP_SLACK_MS = 80L;
    /** 客户端结束时间早于权威值超过该阈值则判定作弊提速。 */
    public static final long HIT_STOP_EARLY_CHEAT_MS = 40L;

    public record DamageIntent(int attackerEntityId, int targetEntityId, int skillId,
                               int skillPower, int clientDeclaredDamage) {}

    public record ValidationResult(boolean accepted, int serverDamage, String rejectReason) {
        public static ValidationResult reject(String reason) {
            return new ValidationResult(false, 0, reason);
        }

        public static ValidationResult ok(int damage) {
            return new ValidationResult(true, damage, "");
        }
    }

    public record HitStopValidation(boolean accepted, String rejectReason) {
        public static HitStopValidation ok() {
            return new HitStopValidation(true, "");
        }

        public static HitStopValidation reject(String reason) {
            return new HitStopValidation(false, reason);
        }
    }

    private final BattleAuditService auditService;

    public BattleDeterministicValidator(BattleAuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * 校验并计算权威伤害；{@code clientDeclaredDamage} 仅用于审计对比，不参与结算。
     */
    public ValidationResult validateAndCompute(BattleContext context, DamageIntent intent) {
        if (context == null || intent == null) {
            return ValidationResult.reject("null_context");
        }
        EntityState attacker = context.getEntities().get(intent.attackerEntityId());
        EntityState target = context.getEntities().get(intent.targetEntityId());
        if (attacker == null || target == null) {
            auditService.recordReject(context.getBattleId(), context.getPlayerId(), "missing_entity");
            return ValidationResult.reject("missing_entity");
        }
        if (attacker.isDead() || target.isDead()) {
            return ValidationResult.reject("entity_dead");
        }
        // 属性：优先读增量属性表（Buff 已增量合并）；无表时回退 HP 派生占位
        var atkSnap = attacker.resolveAttributes();
        var defSnap = target.resolveAttributes();
        int atk = Math.max(50, atkSnap.atk());
        int def = Math.max(10, defSnap.def());
        int serverDamage = BattleDamageFormula.compute(atk, def, intent.skillPower(), target.isBroken());

        if (intent.clientDeclaredDamage() > 0
                && intent.clientDeclaredDamage() > serverDamage * 2L + 50L) {
            auditService.recordReject(context.getBattleId(), context.getPlayerId(),
                    "client_damage_inflate declared=" + intent.clientDeclaredDamage() + " server=" + serverDamage);
            // 仍以服务端为准，不因客户端虚高而拒绝合法技能（防止误杀）；仅审计
        }

        auditService.recordDamage(context.getBattleId(), context.getPlayerId(), intent.skillId(),
                intent.targetEntityId(), target.getHp(), Math.max(0, target.getHp() - serverDamage), serverDamage);
        return ValidationResult.ok(serverDamage);
    }

    /**
     * 校验客户端上报的顿帧结束时间戳。
     *
     * @param expectedEndMs 服务端下发的 expected_hit_stop_end_ms；0 表示无需校验
     * @param clientEndMs   客户端上报的 client_hit_stop_end_ms；0 表示未上报（兼容旧客户端）
     * @param nowMs         当前墙钟
     */
    public HitStopValidation validateHitStopEnd(BattleContext context, long expectedEndMs,
                                                long clientEndMs, long nowMs) {
        if (expectedEndMs <= 0) {
            return HitStopValidation.ok();
        }
        // 旧客户端未上报：仅要求当前时间不早于权威结束（防本地跳过顿帧立刻出手）
        if (clientEndMs <= 0) {
            if (nowMs + HIT_STOP_SLACK_MS < expectedEndMs) {
                if (context != null) {
                    auditService.recordReject(context.getBattleId(), context.getPlayerId(),
                            "hit_stop_early now=" + nowMs + " expected=" + expectedEndMs);
                }
                return HitStopValidation.reject("hit_stop_early");
            }
            return HitStopValidation.ok();
        }
        // 客户端声称的结束时间显著早于权威 → 缩短顿帧提速
        if (clientEndMs + HIT_STOP_EARLY_CHEAT_MS < expectedEndMs) {
            if (context != null) {
                auditService.recordReject(context.getBattleId(), context.getPlayerId(),
                        "hit_stop_shorten client=" + clientEndMs + " expected=" + expectedEndMs);
            }
            return HitStopValidation.reject("hit_stop_shorten");
        }
        // 允许网络抖动：客户端可略晚，但不能无限晚伪造
        if (clientEndMs > expectedEndMs + HIT_STOP_SLACK_MS * 4) {
            // 过晚仅审计，不拒（弱网）
            if (context != null) {
                auditService.recordReject(context.getBattleId(), context.getPlayerId(),
                        "hit_stop_late_audit client=" + clientEndMs + " expected=" + expectedEndMs);
            }
        }
        return HitStopValidation.ok();
    }
}
