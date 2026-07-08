// 挑战玩法运行时状态所在包
package cn.itcast.demo.mylunarcore.challenge;

// Spring 组件扫描注册
import org.springframework.stereotype.Component;

// 并发哈希映射
import java.util.Map;
// 线程安全 Map 实现
import java.util.concurrent.ConcurrentHashMap;
// 原子长整型（自增 challengeUid）
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进行中的挑战关卡运行时索引：分配临时 {@code challengeUid}，缓存 {@link ChallengeRuntime} 实例。
 */
@Component // Spring Bean：缓存进行中的 ChallengeRuntime
public class ChallengeManager {

    // challengeUid → 运行时对象
    private final Map<Long, ChallengeRuntime> runtimes = new ConcurrentHashMap<>();
    // 自增 UID 序列（起始值避免与客户端测试值冲突）
    private final AtomicLong uidSeq = new AtomicLong(10_000);

    /**
     * 分配下一条 challengeUid。
     */
    public long nextUid() { // 分配下一条 challengeUid
        return uidSeq.incrementAndGet(); // 原子自增并返回新 UID
    }

    /**
     * 注册运行时对象。
     */
    public void put(ChallengeRuntime runtime) { // 注册运行时对象
        runtimes.put(runtime.getChallengeUid(), runtime); // 以 challengeUid 为键存入
    }

    /**
     * 按 challengeUid 获取运行时；不存在返回 null。
     */
    public ChallengeRuntime get(long challengeUid) { // 按 UID 查询运行时
        return runtimes.get(challengeUid); // 不存在则返回 null
    }

    /**
     * 移除已结束挑战，释放内存。
     */
    public void remove(long challengeUid) { // 移除已结束挑战
        runtimes.remove(challengeUid); // 从内存索引删除
    }
}
