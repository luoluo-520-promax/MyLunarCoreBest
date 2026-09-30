package cn.itcast.demo.mylunarcore.dialogue;

import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 剧情解锁查询门面。
 * <p>
 * 业务含义：很多内容并不是通过战斗胜负直接解锁，而是由剧情推进产生的“隐藏条件”决定：
 * <ul>
 *   <li>任务解锁：要求玩家曾经看过某条剧情 flag；</li>
 *   <li>场景/分支解锁：要求玩家曾经选择过某个 choiceId；</li>
 *   <li>调试/运营查询：一次性导出玩家持有的全部剧情 flag 集合。</li>
 * </ul>
 * 本类只是对 {@link DialogueProgressService} 的薄封装，便于任务/地图/活动系统调用。
 */
@Service
public class DialogueUnlockQueryService {

    /** 对话进度服务：保存并聚合玩家剧情标记与分支记录。 */
    private final DialogueProgressService progressService;

    public DialogueUnlockQueryService(DialogueProgressService progressService) {
        this.progressService = progressService;
    }

    /** 判断某任务/剧情节点要求的 flag 是否已解锁。 */
    public boolean canUnlockQuest(int playerId, String requiredFlag) {
        return progressService.hasQuestFlag(playerId, requiredFlag);
    }

    /** 判断玩家是否选过某个分支 choiceId（任意对话树中选过即算）。 */
    public boolean canUnlockByChoice(int playerId, String choiceId) {
        return progressService.hasChosen(playerId, choiceId);
    }

    /** 导出玩家当前持有的全部剧情 flag 集合，供调试、运营排查或任务系统批量判断。 */
    public Set<String> flags(int playerId) {
        return progressService.allFlags(playerId);
    }
}
