package cn.itcast.demo.mylunarcore.matchmaking;

/**
 * 匹配轻量状态：排队期不锁会话，仅准备确认阶段锁定角色。
 */
public enum MatchPhase {
    IDLE,
    QUEUED,
    READY_CHECK,
    LOCKED
}
