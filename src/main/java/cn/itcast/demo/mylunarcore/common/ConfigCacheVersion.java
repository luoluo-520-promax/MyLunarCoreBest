// 配置缓存版本号管理：热更成功后递增，读路径据此判断本地快照是否过期
package cn.itcast.demo.mylunarcore.common;

// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// 原子长整型：保证多线程下版本号递增的可见性与原子性
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置缓存版本号：热更成功后 {@link #bump()}，读路径比对版本可主动失效本地快照。
 * <p>典型用法：业务服务缓存配置快照时同时记录 {@link #current()}；
 * 每次读取前调用 {@link #isStale(long)} 判断缓存是否已被热更顶替。</p>
 */
@Component
public class ConfigCacheVersion {

    // 全局配置版本号，初始为 1（避免与「未初始化=0」混淆）
    private final AtomicLong version = new AtomicLong(1L);

    /**
     * @return 当前配置版本号
     */
    public long current() {
        return version.get();
    }

    /**
     * 热更成功后调用：版本号 +1，使所有持有旧版本的缓存失效。
     *
     * @return 递增后的新版本号
     */
    public long bump() {
        return version.incrementAndGet();
    }

    /**
     * 判断调用方观察到的版本是否已过期。
     *
     * @param observed 读取快照时记录的版本号
     * @return true 表示期间发生过热更，本地快照需重新加载
     */
    public boolean isStale(long observed) {
        return observed != version.get();
    }
}
