package cn.itcast.demo.mylunarcore.battle;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 战斗预输入缓冲：每个战斗实体维护长度 2~3 的指令队列。
 * <p>
 * 取消层级：闪避/冲刺 &gt; 终结技 &gt; 战技 &gt; 普攻。
 * 收到指令后不立刻覆盖，按优先级插入；在下一个 ActionWindow 开启时弹出执行。
 */
@Service
public class PlayerInputBufferService {

    public static final int MAX_BUFFER = 5;
    /** 网络抖动下预输入最大存活时间（毫秒），超时丢弃。 */
    public static final long INPUT_TTL_MS = 2_500L;

    /** 取消/优先级：数值越大越高 */
    public enum CancelTier {
        BASIC(10),
        SKILL(20),
        ULT(30),
        DODGE(40);

        private final int priority;

        CancelTier(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }

        public static CancelTier ofSkill(int skillId, int actionType) {
            if (actionType == 4 || skillId == 9) {
                return DODGE;
            }
            if (skillId >= 3000 || skillId == 3) {
                return ULT;
            }
            if (skillId >= 1000 || skillId == 2) {
                return SKILL;
            }
            return BASIC;
        }
    }

    public record BufferedInput(long seq, int playerId, long battleId, int actionType, int skillId,
                                int casterId, List<Integer> targetIds, CancelTier tier, long enqueuedAtMs) {}

    private final Map<String, List<BufferedInput>> buffers = new ConcurrentHashMap<>();
    private final Map<Long, Long> actionWindowOpenAt = new ConcurrentHashMap<>();
    private final AtomicLong seqGen = new AtomicLong();

    private static String key(long battleId, int entityId) {
        return battleId + ":" + entityId;
    }

    /** 标记 ActionWindow 开启时刻（顿帧/后摇结束后由权威路径调用）。 */
    public void openActionWindow(long battleId, long openAtMs) {
        actionWindowOpenAt.put(battleId, openAtMs);
    }

    public boolean isActionWindowOpen(long battleId, long nowMs) {
        Long openAt = actionWindowOpenAt.get(battleId);
        return openAt == null || nowMs >= openAt;
    }

    public long actionWindowOpenAt(long battleId) {
        Long v = actionWindowOpenAt.get(battleId);
        return v == null ? 0L : v;
    }

    /**
     * 入队：同级或更低优先级不覆盖高优先级；队列满时剔除最低优先级最旧项。
     *
     * @return true=已入缓冲（需等窗口），false=窗口已开可立即执行
     */
    public boolean enqueueOrExecuteNow(long battleId, int entityId, int playerId, int actionType,
                                       int skillId, int casterId, List<Integer> targetIds, long nowMs) {
        if (isActionWindowOpen(battleId, nowMs)) {
            return false;
        }
        CancelTier tier = CancelTier.ofSkill(skillId, actionType);
        String k = key(battleId, entityId);
        buffers.compute(k, (kk, existing) -> {
            List<BufferedInput> list = existing == null ? new ArrayList<>(MAX_BUFFER) : new ArrayList<>(existing);
            BufferedInput incoming = new BufferedInput(seqGen.incrementAndGet(), playerId, battleId, actionType,
                    skillId, casterId,
                    targetIds == null ? List.of() : List.copyOf(targetIds),
                    tier, nowMs);
            // 更高优先级可挤掉更低优先级同槽位
            list.removeIf(b -> b.tier().priority() < tier.priority() && list.size() >= MAX_BUFFER);
            list.add(incoming);
            list.sort(Comparator
                    .comparingInt((BufferedInput b) -> -b.tier().priority())
                    .thenComparingLong(BufferedInput::seq));
            while (list.size() > MAX_BUFFER) {
                // 去掉优先级最低且最旧
                int dropIdx = list.size() - 1;
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).tier().priority() < list.get(dropIdx).tier().priority()
                            || (list.get(i).tier().priority() == list.get(dropIdx).tier().priority()
                            && list.get(i).seq() < list.get(dropIdx).seq())) {
                        dropIdx = i;
                    }
                }
                list.remove(dropIdx);
            }
            return list;
        });
        return true;
    }

    /** ActionWindow 开始时弹出最高优先级指令（丢弃超时项）。 */
    public BufferedInput poll(long battleId, int entityId) {
        return poll(battleId, entityId, System.currentTimeMillis());
    }

    public BufferedInput poll(long battleId, int entityId, long nowMs) {
        String k = key(battleId, entityId);
        List<BufferedInput> list = buffers.get(k);
        if (list == null || list.isEmpty()) {
            return null;
        }
        BufferedInput head;
        synchronized (list) {
            while (!list.isEmpty()) {
                BufferedInput cand = list.get(0);
                if (nowMs - cand.enqueuedAtMs() > INPUT_TTL_MS) {
                    list.remove(0);
                    continue;
                }
                head = list.remove(0);
                if (list.isEmpty()) {
                    buffers.remove(k, list);
                }
                return head;
            }
        }
        buffers.remove(k, list);
        return null;
    }

    public void clearBattle(long battleId) {
        actionWindowOpenAt.remove(battleId);
        buffers.keySet().removeIf(k -> k.startsWith(battleId + ":"));
    }
}
