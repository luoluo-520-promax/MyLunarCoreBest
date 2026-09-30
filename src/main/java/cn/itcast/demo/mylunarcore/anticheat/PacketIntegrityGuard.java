package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议层反作弊：客户端时间戳（防变速）、请求序列号（防重放）、随机 challenge（防脚本）。
 */
@Component
public class PacketIntegrityGuard {

    public enum RejectReason {
        OK,
        TIMESTAMP_SKEW,
        SEQ_REPLAY,
        CHALLENGE_MISMATCH,
        DISABLED
    }

    public record CheckResult(boolean ok, RejectReason reason) {
        public static CheckResult pass() {
            return new CheckResult(true, RejectReason.OK);
        }

        public static CheckResult reject(RejectReason reason) {
            return new CheckResult(false, reason);
        }
    }

    private final LunarCoreProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, Long> lastSeqByUid = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastClientTsByUid = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pendingChallengeByUid = new ConcurrentHashMap<>();

    public PacketIntegrityGuard(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.getAntiCheat().isPacketIntegrityEnabled();
    }

    /** 签发 challenge，客户端须在后续关键包带回。 */
    public int issueChallenge(long uid) {
        int challenge = 1 + random.nextInt(1_000_000_000);
        pendingChallengeByUid.put(uid, challenge);
        return challenge;
    }

    public CheckResult validate(long uid, long clientTimestampMs, long seq, Integer challengeEcho) {
        if (!enabled()) {
            return CheckResult.pass();
        }
        long now = System.currentTimeMillis();
        long maxSkew = properties.getAntiCheat().getMaxClientClockSkewMs();
        if (Math.abs(now - clientTimestampMs) > maxSkew) {
            return CheckResult.reject(RejectReason.TIMESTAMP_SKEW);
        }
        Long prevTs = lastClientTsByUid.get(uid);
        if (prevTs != null && clientTimestampMs + 5 < prevTs) {
            // 客户端时间明显回拨（变速齿轮常见）
            return CheckResult.reject(RejectReason.TIMESTAMP_SKEW);
        }
        Long prevSeq = lastSeqByUid.get(uid);
        if (prevSeq != null && seq <= prevSeq) {
            return CheckResult.reject(RejectReason.SEQ_REPLAY);
        }
        Integer expected = pendingChallengeByUid.get(uid);
        if (expected != null && (challengeEcho == null || !expected.equals(challengeEcho))) {
            return CheckResult.reject(RejectReason.CHALLENGE_MISMATCH);
        }
        lastClientTsByUid.put(uid, clientTimestampMs);
        lastSeqByUid.put(uid, seq);
        if (challengeEcho != null) {
            pendingChallengeByUid.remove(uid);
        }
        return CheckResult.pass();
    }

    public void clear(long uid) {
        lastSeqByUid.remove(uid);
        lastClientTsByUid.remove(uid);
        pendingChallengeByUid.remove(uid);
    }
}
