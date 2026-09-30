// 业务配置灰度读取统一入口：按玩家/区服自动选择 canary 或 stable 配置
package cn.itcast.demo.mylunarcore.common;

// 懒加载供应商：仅在被选中分支时才执行配置加载，避免两套配置都解析
import java.util.function.Supplier;

/**
 * 业务配置灰度读取封装：所有配置热更读路径应经此工具按 UID/server 分支。
 * <p>灰度语义：玩家命中灰度集（UID 尾号或区服白名单）时读取 canary（新配置），
 * 否则读取 stable（旧配置），保证灰度期间新旧逻辑并存、互不干扰。</p>
 */
@org.springframework.stereotype.Service
public class ConfigGrayReader {

    // 灰度判定核心：持有当前灰度策略
    private final ConfigGrayRelease grayRelease;

    /**
     * 构造器注入灰度发布器。
     */
    public ConfigGrayReader(ConfigGrayRelease grayRelease) {
        this.grayRelease = grayRelease;
    }

    /**
     * 双配置直接分支选择（两套配置均已加载好的场景）。
     *
     * @param uid      玩家 uid（决定灰度命中）
     * @param serverId 区服 id（区服白名单命中条件，可空）
     * @param canary   灰度新配置
     * @param stable   稳定旧配置
     * @return 灰度开启且玩家不在灰度集时返回 stable；否则返回 canary（新配置）
     */
    public <T> T select(long uid, String serverId, T canary, T stable) {
        if (grayRelease.inGray(uid, serverId)) {
            return canary;
        }
        // stable 未提供时退化为 canary，保证调用方行为可用
        return stable != null ? stable : canary;
    }

    /**
     * 懒加载版本：仅执行被选中分支的 {@link Supplier}，未选中的配置完全不解析。
     *
     * @param uid      玩家 uid
     * @param serverId 区服 id（可空）
     * @param canary   灰度配置加载逻辑
     * @param stable   稳定配置加载逻辑
     * @return 命中的分支配置值
     */
    public <T> T select(long uid, String serverId, Supplier<T> canary, Supplier<T> stable) {
        if (grayRelease.inGray(uid, serverId)) {
            return canary.get();
        }
        T s = stable.get();
        return s != null ? s : canary.get();
    }

    /**
     * 判断该玩家当前是否应使用灰度（canary）配置。
     */
    public boolean useCanary(long uid, String serverId) {
        return grayRelease.inGray(uid, serverId);
    }

    /**
     * @return 当前生效的灰度策略（供回滚影响分析等查询使用）
     */
    public ConfigGrayRelease.GrayPolicy policy() {
        return grayRelease.current();
    }
}
