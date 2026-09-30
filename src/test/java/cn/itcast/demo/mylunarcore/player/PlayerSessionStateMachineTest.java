package cn.itcast.demo.mylunarcore.player;

import io.netty.channel.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PlayerSessionStateMachine 会话状态机测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerSessionStateMachineTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerSessionStateMachine 会话状态机测试")
class PlayerSessionStateMachineTest {

    /**
     * 验证点：大厅可进入匹配，战斗不可直接进入匹配。
     * <p>测试方法 {@code shouldAllowAndRejectTransitions}：
     * <ul>
     *   <li>{@code assertTrue(PlayerSessionStateMachine.canTransition(PlayerSessionState.HALL, PlayerSessionState.MATCHING));}</li>
     *   <li>{@code assertFalse(PlayerSessionStateMachine.canTransition(PlayerSessionState.BATTLE, PlayerSessionState.MATCHING));}</li>
     * </ul>
     */
    @Test
    @DisplayName("大厅可进入匹配，战斗不可直接进入匹配")
    void shouldAllowAndRejectTransitions() {
        assertTrue(PlayerSessionStateMachine.canTransition(PlayerSessionState.HALL, PlayerSessionState.MATCHING));
        assertFalse(PlayerSessionStateMachine.canTransition(PlayerSessionState.BATTLE, PlayerSessionState.MATCHING));
    }

    /**
     * 验证点：非法迁移应抛异常。
     * <p>测试方法 {@code illegalTransitionShouldThrow}：
     * <ul>
     *   <li>{@code assertThrows(IllegalStateException.class,}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法迁移应抛异常")
    void illegalTransitionShouldThrow() {
        GameSession session = new GameSession(1L, mock(Channel.class), null);
        session.setSessionState(PlayerSessionState.BATTLE);
        assertThrows(IllegalStateException.class,
                () -> PlayerSessionStateMachine.transition(session, PlayerSessionState.MATCHING));
    }
}
