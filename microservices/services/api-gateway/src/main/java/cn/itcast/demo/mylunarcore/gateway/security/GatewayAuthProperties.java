// 网关 JWT 鉴权配置所在包
package cn.itcast.demo.mylunarcore.gateway.security;

// 绑定 application.yml 中 mylunarcore.gateway.auth 前缀的配置
import org.springframework.boot.context.properties.ConfigurationProperties;

// 可变列表实现：存放默认空的白名单路径集合
import java.util.ArrayList;
// 列表接口：excludePaths 字段类型
import java.util.List;

/**
 * 网关鉴权配置：开关、白名单路径、JWT 密钥与签发者。
 */
@ConfigurationProperties(prefix = "mylunarcore.gateway.auth") // 绑定 yml 中 mylunarcore.gateway.auth 节点
public class GatewayAuthProperties {

    // 是否启用网关 JWT 校验，默认开启
    private boolean enabled = true;
    // 无需携带 Token 即可访问的路径（如 /auth/login）
    private List<String> excludePaths = new ArrayList<>();
    // HMAC 签名密钥（生产环境务必改为足够长的随机串）
    private String jwtSecret = "change-me-to-a-secure-32-byte-secret!";
    // JWT 签发者（issuer），须与 auth-service 签发时一致
    private String jwtIssuer = "mylunarcore-auth-service";

    /** @return 鉴权是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled 设置是否启用鉴权 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return 鉴权白名单路径 */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /** @param excludePaths 设置白名单路径 */
    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    /** @return JWT HMAC 密钥字符串 */
    public String getJwtSecret() {
        return jwtSecret;
    }

    /** @param jwtSecret 设置 JWT 密钥 */
    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    /** @return JWT 签发者标识 */
    public String getJwtIssuer() {
        return jwtIssuer;
    }

    /** @param jwtIssuer 设置签发者 */
    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }
}
