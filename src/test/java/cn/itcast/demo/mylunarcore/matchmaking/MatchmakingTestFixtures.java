package cn.itcast.demo.mylunarcore.matchmaking;

import java.util.List;

final /**
 * MatchmakingTestFixtures。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MatchmakingTestFixtures}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class MatchmakingTestFixtures {

    static final int PLAYER_A = 1001;
    static final int PLAYER_B = 1002;
    static final int PLAYER_C = 1003;
    static final int MODE = 1;
    static final int LEVEL = 20;
    static final int POWER = 1500;

    private MatchmakingTestFixtures() {
    }

    static MatchQueue.QueueEntry entry(int playerId, int mode, int level, int power) {
        return new MatchQueue.QueueEntry(playerId, mode, level, power, System.currentTimeMillis());
    }

    static List<MatchQueue.QueueEntry> twoPlayers(int mode) {
        return List.of(
                entry(PLAYER_A, mode, LEVEL, POWER),
                entry(PLAYER_B, mode, LEVEL, POWER));
    }
}
