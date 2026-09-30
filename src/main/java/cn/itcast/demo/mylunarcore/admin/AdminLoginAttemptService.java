// 后台登录失败锁定策略所在包
package cn.itcast.demo.mylunarcore.admin;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端登录失败锁定：同一用户名连续失败达到阈值后临时锁定。
 * <p>
 * 目的：降低后台账号被暴力猜测密码的风险。锁定逻辑只作用于后台登录接口，
 * 不影响玩家客户端登录。
 */
@Service // Spring 服务组件：在后台登录流程中注入使用
public class AdminLoginAttemptService {

    // 连续失败次数上限：达到后进入临时锁定
    private static final int MAX_FAILURES = 5;
    // 锁定时长：15 分钟后自动解锁，便于管理员在短时间内重试
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    // 按用户名（归一化后）保存失败状态；ConcurrentHashMap 保证多线程并发安全
    private final ConcurrentHashMap<String, AttemptState> attempts = new ConcurrentHashMap<>();

    /**
     * 登录前检查：若账号仍处于锁定窗口内，则直接抛出锁定异常。
     *
     * @param username 后台用户名；会先做大小写归一化，避免同一用户用不同大小写绕过限制
     */
    public void assertNotLocked(String username) {
        // 统一用户名格式，保证 "Admin" 与 "admin" 共享同一失败计数
        String key = normalize(username);
        // 取出该账号的失败状态；没有状态说明从未失败过
        AttemptState state = attempts.get(key);
        if (state == null) {
            return;
        }
        long now = System.currentTimeMillis();
        // 锁定截止时间仍大于当前时间：说明仍在锁定中，拒绝继续登录
        if (state.lockedUntilMillis > now) {
            throw new AccountLockedException("admin account temporarily locked");
        }
        // 锁定时间已过：清理旧状态，避免 map 长期保留过期记录
        if (state.lockedUntilMillis > 0 && state.lockedUntilMillis <= now) {
            attempts.remove(key, state);
        }
    }

    /**
     * 登录成功后调用：清除失败次数与锁定状态。
     *
     * @param username 成功登录的后台用户名
     */
    public void onSuccess(String username) {
        attempts.remove(normalize(username));
    }

    /**
     * 登录失败后调用：累计失败次数，达到阈值后进入锁定窗口。
     *
     * @param username 失败的后台用户名
     */
    public void onFailure(String username) {
        String key = normalize(username);
        // compute 以原子方式更新，避免并发登录时丢失失败次数
        attempts.compute(key, (ignored, current) -> {
            long now = System.currentTimeMillis();
            AttemptState next = current == null ? new AttemptState() : current;
            // 已在锁定中时，不再重复增加失败次数（避免锁定期间被刷写）
            if (next.lockedUntilMillis > now) {
                return next;
            }
            // 累加失败次数；失败次数达到阈值后触发锁定并重置计数
            next.failures++;
            if (next.failures >= MAX_FAILURES) {
                next.lockedUntilMillis = now + LOCK_DURATION.toMillis();
                next.failures = 0;
            }
            return next;
        });
    }

    /**
     * 查询账号当前是否处于锁定状态。
     *
     * @param username 后台用户名
     * @return true 表示仍在锁定窗口内
     */
    public boolean isLocked(String username) {
        AttemptState state = attempts.get(normalize(username));
        return state != null && state.lockedUntilMillis > System.currentTimeMillis();
    }

    /**
     * 账号归一化：去掉首尾空白并转换为小写，避免同一账号因输入差异产生多个计数桶。
     */
    private static String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }

    /**
     * 记录单个账号的失败与锁定状态。
     * <p>
     * failures：当前累计失败次数；
     * lockedUntilMillis：锁定结束时间戳（毫秒）。
     */
    private static final class AttemptState {
        private int failures;
        private long lockedUntilMillis;
    }
}
