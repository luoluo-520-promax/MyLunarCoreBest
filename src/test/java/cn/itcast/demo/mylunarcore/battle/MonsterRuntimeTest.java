package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("MonsterRuntime 怪物运行时测试")
class MonsterRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(MonsterRuntimeTest.class);

    @Test
    @DisplayName("创建时应满血且属性与构造参数一致")
    void shouldInitializeWithFullHp() {
        MonsterRuntime monster = new MonsterRuntime(101, 3, 300);

        log.info("怪物初始化校验: configId={}, level={}, maxHp={}, hp={}",
                monster.getConfigMonsterId(), monster.getLevel(), monster.getMaxHp(), monster.getHp());
        assertEquals(101, monster.getConfigMonsterId());
        assertEquals(3, monster.getLevel());
        assertEquals(300, monster.getMaxHp());
        assertEquals(300, monster.getHp());
    }

    @Test
    @DisplayName("setHp 应更新当前血量")
    void setHpShouldUpdateCurrentHp() {
        MonsterRuntime monster = new MonsterRuntime(202, 1, 100);
        monster.setHp(45);

        log.info("HP 更新校验: configId={}, maxHp={}, hpAfterSet={}",
                monster.getConfigMonsterId(), monster.getMaxHp(), monster.getHp());
        assertEquals(45, monster.getHp());
    }
}
