package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EntityState}：HP/死亡标记，以及 buff 叠层上限与归零移除。
 */
@DisplayName("EntityState 实体状态测试")
class EntityStateTest {

    private static final Logger log = LoggerFactory.getLogger(EntityStateTest.class);

    /** 构造 id=101、初始 HP=500，setHp(300)+setDead(true) 后读写一致。 */
    @Test
    @DisplayName("应正确保存 HP 与死亡标记")
    void shouldTrackHpAndDeadFlag() {
        EntityState entity = new EntityState(101, 500, false);
        entity.setHp(300);
        entity.setDead(true);

        log.info("HP/死亡标记校验: id={}, hp={}, dead={}",
                entity.getId(), entity.getHp(), entity.isDead());
        assertEquals(101, entity.getId());
        assertEquals(300, entity.getHp());
        assertTrue(entity.isDead());
    }

    /**
     * buffId=10：先加 2 层得 2；再加 5 但 maxStack=3，结果封顶为 3。
     */
    @Test
    @DisplayName("addBuffStack 应正确叠加并受 maxStack 限制")
    void addBuffStackShouldRespectMaxStack() {
        EntityState entity = new EntityState(1, 1000, false);

        entity.addBuffStack(10, 2, 3);
        int stacksAfterFirst = entity.getBuffStacks().get(10);
        entity.addBuffStack(10, 5, 3);
        int stacksAfterSecond = entity.getBuffStacks().get(10);

        log.info("Buff 叠层校验: buffId=10, stacksAfterAdd2={}, stacksAfterAdd5(maxStack=3)={}",
                stacksAfterFirst, stacksAfterSecond);
        assertEquals(2, stacksAfterFirst);
        assertEquals(3, stacksAfterSecond);
    }

    /** 加 1 再减 1 后 map 中不再包含该 buffId。 */
    @Test
    @DisplayName("Buff 层数减至 0 时应从表中移除")
    void buffShouldBeRemovedWhenStackReachesZero() {
        EntityState entity = new EntityState(1, 1000, false);
        entity.addBuffStack(20, 1, 5);
        entity.addBuffStack(20, -1, 5);

        log.info("Buff 归零移除校验: buffId=20, containsBuff20={}, buffStacksEmpty={}",
                entity.getBuffStacks().containsKey(20), entity.getBuffStacks().isEmpty());
        assertFalse(entity.getBuffStacks().containsKey(20));
    }
}
