package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 配置热更灰度发布：按 UID 尾号或服务器白名单先生效，避免卡池等配置全服即时翻车。
 * <p>该服务维护一份原子替换的灰度策略快照，读路径无需加锁即可拿到一致的策略视图。</p>
 */
@Service
public class ConfigGrayRelease {

    /**
     * 灰度策略快照：
     * <ul>
     *   <li>enabled：总开关，关闭时视为全量生效</li>
     *   <li>uidTailDigits：允许命中的 UID 尾号集合（例如 1/3/5）</li>
     *   <li>serverIds：允许命中的区服白名单</li>
     *   <li>note：发布备注，用于审计/回滚说明</li>
     * </ul>
     */
    public record GrayPolicy(boolean enabled, Set<Integer> uidTailDigits, Set<String> serverIds, String note) {
        public GrayPolicy {
            uidTailDigits = uidTailDigits == null ? Set.of() : Set.copyOf(uidTailDigits);
            serverIds = serverIds == null ? Set.of() : Set.copyOf(serverIds);
            note = note == null ? "" : note;
        }

        /**
         * @return 关闭灰度的默认策略；作为初始化和回滚兜底值。
         */
        public static GrayPolicy disabled() {
            return new GrayPolicy(false, Set.of(), Set.of(), "");
        }
    }

    // 全局配置源：从 application-*.properties 中读取灰度开关与白名单
    private final LunarCoreProperties properties;
    // 当前生效的灰度策略，采用 volatile 保证多线程可见性
    private volatile GrayPolicy policy = GrayPolicy.disabled();

    /**
     * 构造器注入全局配置，并立即从配置文件初始化一次灰度策略。
     */
    public ConfigGrayRelease(LunarCoreProperties properties) {
        this.properties = properties;
        refreshFromProperties();
    }

    /**
     * 根据 {@link LunarCoreProperties#getConfigGray()} 重新构建当前灰度策略。
     * <p>配置未开启时会切回 disabled，确保读路径默认使用稳定配置。</p>
     */
    public void refreshFromProperties() {
        LunarCoreProperties.ConfigGrayProperties cfg = properties.getConfigGray();
        if (cfg == null || !cfg.isEnabled()) {
            policy = GrayPolicy.disabled();
            return;
        }
        policy = new GrayPolicy(true, cfg.parsedUidTails(), cfg.parsedServerIds(), cfg.getNote());
    }

    /**
     * 直接替换当前灰度策略，供回滚/测试注入使用。
     */
    public void updatePolicy(GrayPolicy next) {
        this.policy = next == null ? GrayPolicy.disabled() : next;
    }

    /**
     * @return 当前生效的灰度策略快照
     */
    public GrayPolicy current() {
        return policy;
    }

    /**
     * 判断某个玩家/区服是否应该看到灰度新配置。
     *
     * <p>判定顺序：
     * <ol>
     *   <li>灰度总开关关闭时，视为全量生效，直接返回 true；</li>
     *   <li>命中 serverId 白名单时返回 true；</li>
     *   <li>若配置了 UID 尾号集合，则按 {@code uid % 10} 命中判定；</li>
     *   <li>若只配置了 serverIds，则未命中白名单返回 false；</li>
     * </ol>
     * </p>
     *
     * @param uid      玩家 uid
     * @param serverId 区服 id，可为空
     * @return true 表示该玩家应使用「灰度新配置」；false 继续用旧全量配置。
     */
    public boolean inGray(long uid, String serverId) {
        GrayPolicy p = policy;
        if (!p.enabled()) {
            // 灰度关闭时视为全量生效
            return true;
        }
        if (serverId != null && !serverId.isBlank() && !p.serverIds().isEmpty()
                && p.serverIds().contains(serverId)) {
            return true;
        }
        if (!p.uidTailDigits().isEmpty()) {
            int tail = (int) Math.floorMod(uid, 10L);
            return p.uidTailDigits().contains(tail);
        }
        return p.serverIds().isEmpty();
    }
}
