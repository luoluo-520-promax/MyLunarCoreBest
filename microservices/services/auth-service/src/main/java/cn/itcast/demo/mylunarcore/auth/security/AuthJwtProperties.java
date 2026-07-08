// 认证服务 JWT 与用户配置所在包

// 当前类所属包：cn.itcast.demo.mylunarcore.auth.security
package cn.itcast.demo.mylunarcore.auth.security;



// 将 yml 中 mylunarcore.auth.jwt 前缀绑定到本类

// Spring 框架类
import org.springframework.boot.context.properties.ConfigurationProperties;



// 保持插入顺序的 Map（便于 yml 中用户列表顺序稳定）

// JDK 集合或工具
import java.util.LinkedHashMap;

// 键值对集合

// JDK 集合或工具
import java.util.Map;



/**

 * 认证服务 JWT 配置：密钥、issuer、过期时间、演示用用户表。

 */

@ConfigurationProperties(prefix = "mylunarcore.auth.jwt")

public class AuthJwtProperties {



    // HMAC 签名密钥（须足够长；生产环境用环境变量覆盖）

    private String secret = "change-me-to-a-secure-32-byte-secret";

    // JWT issuer，须与网关 GatewayAuthProperties.jwtIssuer 一致

    private String issuer = "mylunarcore-auth-service";

    // 访问令牌有效时长（分钟）

    private long expireMinutes = 120;

    // 用户名 → 用户凭证（演示用内存用户表）

    private Map<String, UserCredential> users = new LinkedHashMap<>();



    /** @return 签名密钥 */

    public String getSecret() {

        return secret;

    }



    /** @param secret 设置签名密钥 */

    public void setSecret(String secret) {

        this.secret = secret;

    }



    /** @return JWT 签发者 */

    public String getIssuer() {

        return issuer;

    }



    /** @param issuer 设置签发者 */

    public void setIssuer(String issuer) {

        this.issuer = issuer;

    }



    /** @return 令牌过期分钟数 */

    public long getExpireMinutes() {

        return expireMinutes;

    }



    /** @param expireMinutes 设置过期分钟数 */

    public void setExpireMinutes(long expireMinutes) {

        this.expireMinutes = expireMinutes;

    }



    /** @return 配置的用户名到凭证映射 */

    public Map<String, UserCredential> getUsers() {

        return users;

    }



    /** @param users 设置用户表 */

    public void setUsers(Map<String, UserCredential> users) {

        this.users = users;

    }



    /**

     * 单个用户的登录凭证与身份信息（嵌套配置类）。

     */

    public static class UserCredential {

        // 演示用明文密码（生产应存密码哈希）

        private String password;

        // 用户唯一数字 ID

        private Long uid;

        // 角色，默认 player

        private String role = "player";



        /** @return 密码 */

        public String getPassword() {

            return password;

        }



        /** @param password 设置密码 */

        public void setPassword(String password) {

            this.password = password;

        }



        /** @return 用户 ID */

        public Long getUid() {

            return uid;

        }



        /** @param uid 设置用户 ID */

        public void setUid(Long uid) {

            this.uid = uid;

        }



        /** @return 角色 */

        public String getRole() {

            return role;

        }



        /** @param role 设置角色 */

        public void setRole(String role) {

            this.role = role;

        }

    }

}


