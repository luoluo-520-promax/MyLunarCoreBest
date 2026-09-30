package cn.itcast.demo.mylunarcore.party;

/**
 * 跨节点组队/跟随迁移事务状态机（Saga）。
 * <pre>
 * INIT → SERIALIZING → TRANSFERRING → CONFIRMED
 *                  ↘          ↘
 *                   → ROLLBACK ←
 * </pre>
 */
public enum MigrateState {
    INIT,
    SERIALIZING,
    TRANSFERRING,
    CONFIRMED,
    ROLLBACK;

    public boolean isTerminal() {
        return this == CONFIRMED || this == ROLLBACK;
    }

    public boolean canAdvanceTo(MigrateState next) {
        if (next == null || this.isTerminal()) {
            return false;
        }
        return switch (this) {
            case INIT -> next == SERIALIZING || next == ROLLBACK;
            case SERIALIZING -> next == TRANSFERRING || next == ROLLBACK;
            case TRANSFERRING -> next == CONFIRMED || next == ROLLBACK;
            default -> false;
        };
    }
}
