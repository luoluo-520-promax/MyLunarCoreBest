package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EntityState 实体状态测试")
class EntityStateTest {

    private static final Logger log = LoggerFactory.getLogger(EntityStateTest.class);

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
