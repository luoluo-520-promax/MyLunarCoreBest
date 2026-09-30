package cn.itcast.demo.mylunarcore.battle;

/**
 * 战斗行动增量：断线重连时按时间戳回放，而非只给最终血条。
 */
public record BattleDeltaRecord(
        long actionId,
        long timestampMs,
        int casterId,
        int skillId,
        int targetId,
        int damage,
        boolean critical,
        boolean kill,
        int hpAfter
) {}
