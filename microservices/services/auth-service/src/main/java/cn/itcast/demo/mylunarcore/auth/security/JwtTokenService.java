// JWT 签发服务所在包

// 当前类所属包：cn.itcast.demo.mylunarcore.auth.security
package cn.itcast.demo.mylunarcore.auth.security;



// JJWT：构建与签名 JWT

// JJWT 令牌库
import io.jsonwebtoken.Jwts;

// 从密钥字节生成 HMAC SecretKey

// JJWT 令牌库
import io.jsonwebtoken.security.Keys;

// 标记为 Spring 单例服务 Bean

// Spring 框架类
import org.springframework.stereotype.Service;



// 对称加密密钥接口

// JDK 加密
import javax.crypto.SecretKey;

// UTF-8 字符集

// JDK NIO
import java.nio.charset.StandardCharsets;

// 时间点（用于 iat/exp）

// JDK 日期时间
import java.time.Instant;

// 时间单位：分钟

// JDK 日期时间
import java.time.temporal.ChronoUnit;

// 旧版 Date，JJWT 部分 API 仍使用

// JDK 集合或工具
import java.util.Date;

// JWT 自定义 claims 键值对

// JDK 集合或工具
import java.util.Map;



/**

 * 根据配置中的密钥与 issuer 签发 HMAC-SHA JWT 访问令牌。

 */

@Service // 注册为 Spring Bean，供 AuthController 注入

public class JwtTokenService {



    // JWT 密钥、issuer、过期分钟数等

    private final AuthJwtProperties properties;

    // 由 properties.secret 生成的验签/签名密钥

    private final SecretKey secretKey;



    /**

     * 构造器：保存配置并初始化 SecretKey。

     */

    public JwtTokenService(AuthJwtProperties properties) {

        this.properties = properties;

        this.secretKey = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));

    }



    /**

     * 为指定用户创建访问令牌。

     *

     * @param uid      用户数字 ID，写入 claim 与 subject

     * @param username 用户名

     * @param role     角色（如 player、admin）

     * @return 令牌字符串与过期时间戳（毫秒）

     */

    public TokenResult createToken(Long uid, String username, String role) {

        Instant now = Instant.now(); // 签发时刻

        Instant expireAt = now.plus(properties.getExpireMinutes(), ChronoUnit.MINUTES); // 过期时刻



        String token = Jwts.builder()

                .issuer(properties.getIssuer()) // 签发者，网关校验时需一致

                .subject(String.valueOf(uid)) // 标准 subject 字段存 uid 字符串

                .issuedAt(Date.from(now)) // 签发时间 iat

                .expiration(Date.from(expireAt)) // 过期时间 exp

                .claims(Map.of(

                        "uid", uid,

                        "username", username,

                        "role", role

                )) // 自定义业务 claims

                .signWith(secretKey) // HMAC 签名

                .compact(); // 生成最终 JWT 字符串

        return new TokenResult(token, expireAt.toEpochMilli()); // 返回令牌与过期毫秒时间戳

    }



    /**

     * 签发结果：不可变 record。

     *

     * @param token    JWT 字符串

     * @param expireAt 过期时间（Unix 毫秒）

     */

    public record TokenResult(String token, long expireAt) {

    }

}


