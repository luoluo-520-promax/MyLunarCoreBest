package cn.itcast.demo.mylunarcore.battle;

import java.util.List;
import java.util.Map;

/**
 * 战斗中间态快照 DTO：用于断线重连 / 进程热恢复，避免仅存内存导致切后台杀进程丢战局。
 */
public record BattleSnapshot(
        long battleId,
        int playerId,
        List<Integer> participantPlayerIds,
        int lineupId,
        int battleStageId,
        long startTimeSeconds,
        int turn,
        int currentWave,
        int waveCount,
        boolean ended,
        Map<Integer, EntitySnap> entities,
        long savedAtEpochMs
) {
    public record EntitySnap(int id, int hp, boolean dead, int toughness, boolean broken, int skinId) {}
}
