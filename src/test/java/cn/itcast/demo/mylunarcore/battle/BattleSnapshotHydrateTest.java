package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗快照：{@link BattleContext#fromSnapshot} 恢复进度；
 * {@link BattleSnapshotService} 在关闭 Redis 时 export/import 迁移载荷闭环。
 */
@DisplayName("战斗快照 hydrate / 迁移载荷")
class BattleSnapshotHydrateTest {

    /**
     * 快照含 turn=5、wave=2/3、玩家 HP=800、怪物已死亡；
     * hydrate 后字段一致，且 lineup 含 1002 时 isParticipant(1002)=true。
     */
    @Test
    @DisplayName("fromSnapshot 应恢复回合、波次与实体 HP")
    void fromSnapshotRestoresProgress() {
        BattleSnapshot snap = new BattleSnapshot(
                99L, 1001, List.of(1001, 1002), 1, 5001, 1_700_000_000L,
                5, 2, 3, false,
                Map.of(
                        1001, new BattleSnapshot.EntitySnap(1001, 800, false, 40, false, 0),
                        2001, new BattleSnapshot.EntitySnap(2001, 0, true, 0, true, 0)
                ),
                System.currentTimeMillis());

        BattleContext ctx = BattleContext.fromSnapshot(snap);
        assertNotNull(ctx);
        assertEquals(99L, ctx.getBattleId());
        assertEquals(5, ctx.getTurn());
        assertEquals(2, ctx.getCurrentWave());
        assertEquals(3, ctx.getWaveCount());
        assertFalse(ctx.isEnded());
        assertEquals(800, ctx.getEntity(1001).getHp());
        assertTrue(ctx.getEntity(2001).isDead());
        assertTrue(ctx.isParticipant(1002));
    }

    /**
     * 开启 battleSnapshot、关闭 Redis：save 后 exportMigrationPayload 有值，
     * import 后玩家 42 的 HP 仍为 555，battleId=7。
     */
    @Test
    @DisplayName("export/import 迁移载荷应闭环")
    void migrationPayloadRoundTrip() {
        cn.itcast.demo.mylunarcore.config.LunarCoreProperties props =
                new cn.itcast.demo.mylunarcore.config.LunarCoreProperties();
        props.getBattleSnapshot().setEnabled(true);
        props.getRedis().setEnabled(false); // 走本地内存快照路径

        BattleSnapshotService svc = new BattleSnapshotService(
                new com.fasterxml.jackson.databind.ObjectMapper(), props, null);

        BattleContext original = BattleContext.createNew(7L, 42, 1, 100, 1000L, List.of());
        original.getEntity(42).setHp(555);
        svc.save(original);

        Optional<String> payload = svc.exportMigrationPayload(7L);
        assertTrue(payload.isPresent());

        Optional<BattleContext> imported = svc.importMigrationPayload(payload.get());
        assertTrue(imported.isPresent());
        assertEquals(555, imported.get().getEntity(42).getHp());
        assertEquals(7L, imported.get().getBattleId());
    }
}
