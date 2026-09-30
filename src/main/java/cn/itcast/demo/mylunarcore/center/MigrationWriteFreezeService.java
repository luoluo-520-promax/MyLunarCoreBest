package cn.itcast.demo.mylunarcore.center;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 切服写冻结：迁移包生成前冻结玩家写操作，目标节点确认接收后解冻。
 */
@Service
public class MigrationWriteFreezeService {

    public record MigrationPackage(long uid, long frozenAtMs, String walletSnapshotJson,
                                   String battleSnapshotJson, String sceneSnapshotJson,
                                   Map<String, String> cacheExtras) {
        public MigrationPackage {
            walletSnapshotJson = walletSnapshotJson == null ? "" : walletSnapshotJson;
            battleSnapshotJson = battleSnapshotJson == null ? "" : battleSnapshotJson;
            sceneSnapshotJson = sceneSnapshotJson == null ? "" : sceneSnapshotJson;
            cacheExtras = cacheExtras == null ? Map.of() : Map.copyOf(cacheExtras);
        }
    }

    private final Map<Long, Long> frozenUntilMs = new ConcurrentHashMap<>();
    private final Map<Long, MigrationPackage> pendingPackages = new ConcurrentHashMap<>();

    /** 默认冻结窗口 15s，超时自动解冻避免卡死。 */
    private static final long DEFAULT_FREEZE_MS = 15_000L;

    public void freeze(long uid) {
        frozenUntilMs.put(uid, System.currentTimeMillis() + DEFAULT_FREEZE_MS);
    }

    public void unfreeze(long uid) {
        frozenUntilMs.remove(uid);
        pendingPackages.remove(uid);
    }

    public boolean isFrozen(long uid) {
        Long until = frozenUntilMs.get(uid);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            unfreeze(uid);
            return false;
        }
        return true;
    }

    public void putPackage(MigrationPackage pack) {
        if (pack == null) {
            return;
        }
        pendingPackages.put(pack.uid(), pack);
    }

    public MigrationPackage getPackage(long uid) {
        return pendingPackages.get(uid);
    }

    /** 目标节点确认接收后调用。 */
    public void ackReceived(long uid) {
        unfreeze(uid);
    }
}
