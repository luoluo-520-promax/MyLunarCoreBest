// 网关限流配置属性所在包
package cn.itcast.demo.mylunarcore.gateway.ratelimit;

// 将 application.yml 中带指定前缀的配置绑定到本类字段
import org.springframework.boot.context.properties.ConfigurationProperties;

// 可变列表实现：默认可修改的排除路径集合
import java.util.ArrayList;
// 列表接口：excludePaths 字段类型
import java.util.List;

/**
 * 网关限流配置：对应配置项前缀 {@code mylunarcore.gateway.ratelimit}。
 * <p>可在 application.yml 中覆盖默认值。</p>
 */
@ConfigurationProperties(prefix = "mylunarcore.gateway.ratelimit")
public class GatewayRateLimitProperties {

    // 是否启用限流，默认开启
    private boolean enabled = true;
    // 全站每秒最大请求数（QPS）
    private int globalQps = 500;
    // 单个用户（或 IP）每秒最大请求数
    private int userQps = 50;
    // 不参与限流的路径模式列表（Ant 风格，如 /auth/**）
    private List<String> excludePaths = new ArrayList<>();

    /** @return 限流功能是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 设置是否启用限流 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 全站每秒请求上限 */
    public int getGlobalQps() {
        return globalQps;
    }

    /** @param globalQps 设置全站 QPS 上限 */
    public void setGlobalQps(int globalQps) {
        this.globalQps = globalQps;
    }

    /** @return 单用户每秒请求上限 */
    public int getUserQps() {
        return userQps;
    }

    /** @param userQps 设置单用户 QPS 上限 */
    public void setUserQps(int userQps) {
        this.userQps = userQps;
    }

    /** @return 限流白名单路径列表 */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /** @param excludePaths 设置白名单路径列表 */
    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }
}
