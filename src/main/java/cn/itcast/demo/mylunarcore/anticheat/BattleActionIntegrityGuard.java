package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗动作即时完整性校验：每回合关键动作附带服务器 Salt，客户端须回传 Hash；
 * 校验失败立即中断战斗并踢下线（相对事后审计）。
 */
@Component
public class BattleActionIntegrityGuard {

    public record Challenge(String salt, long issuedAtMs) {}

    public record CheckResult(boolean ok, String reason) {
        public static CheckResult pass() {
            return new CheckResult(true, "");
        }

        public static CheckResult fail(String reason) {
            return new CheckResult(false, reason);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final BattleAuditService auditService;
    private final GameSessionManager sessionManager;
    private volatile boolean kickOnFail = true;

    public BattleActionIntegrityGuard(BattleAuditService auditService, GameSessionManager sessionManager) {
        this.auditService = auditService;
        this.sessionManager = sessionManager;
    }

    public void setKickOnFail(boolean kickOnFail) {
        this.kickOnFail = kickOnFail;
    }

    /** 为下一动作签发 Salt。 */
    public Challenge issue(long battleId, int actionSeq) {
        byte[] buf = new byte[16];
        random.nextBytes(buf);
        String salt = HexFormat.of().formatHex(buf);
        Challenge c = new Challenge(salt, System.currentTimeMillis());
        challenges.put(key(battleId, actionSeq), c);
        return c;
    }

    /**
     * 校验客户端 Hash = SHA-256(battleId|seq|skillId|targetId|salt)。
     */
    public CheckResult verifyAndConsume(BattleContext context, int actionSeq, int skillId,
                                        int targetId, String clientHash) {
        if (context == null) {
            return CheckResult.fail("null_context");
        }
        long battleId = context.getBattleId();
        Challenge c = challenges.remove(key(battleId, actionSeq));
        if (c == null) {
            return reject(context, "missing_challenge");
        }
        if (System.currentTimeMillis() - c.issuedAtMs() > 15_000L) {
            return reject(context, "challenge_expired");
        }
        String expected = sha256(battleId + "|" + actionSeq + "|" + skillId + "|" + targetId + "|" + c.salt());
        if (clientHash == null || !expected.equalsIgnoreCase(clientHash.trim())) {
            return reject(context, "hash_mismatch");
        }
        return CheckResult.pass();
    }

    private CheckResult reject(BattleContext context, String reason) {
        auditService.recordReject(context.getBattleId(), context.getPlayerId(), "integrity:" + reason);
        context.setEnded(true);
        if (kickOnFail) {
            GameSession session = sessionManager.getOrNull((long) context.getPlayerId());
            if (session != null && session.getChannel() != null) {
                session.getChannel().close();
            }
        }
        return CheckResult.fail(reason);
    }

    private static String key(long battleId, int seq) {
        return battleId + "#" + seq;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
