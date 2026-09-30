// 可选 Redis 基础设施装配类所在包
package cn.itcast.demo.mylunarcore.config;

// SLF4J 日志门面接口
import org.slf4j.Logger;
// SLF4J 日志工厂
import org.slf4j.LoggerFactory;
// 条件装配：仅当指定配置属性满足条件时才创建本配置类中的 Bean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
// 声明 Bean 的工厂方法
import org.springframework.context.annotation.Bean;
// 声明本类是 Spring 配置类
import org.springframework.context.annotation.Configuration;
// Redis 密码包装：避免以明文 String 直接暴露给连接层
import org.springframework.data.redis.connection.RedisPassword;
// Redis 单机连接配置（host/port/database/password）
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
// Lettuce 客户端连接工厂：Spring Data Redis 默认基于 Netty 的异步客户端
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
// 以 String 为键值类型的 Redis 模板：大部分业务（SET/ZSET/PubSub）只需字符串
import org.springframework.data.redis.core.StringRedisTemplate;
// Redis Pub/Sub 消息监听容器：负责把订阅到的频道消息分发给监听器
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 可选 Redis 装配：仅 {@code lunarcore.redis.enabled=true} 时生效。
 * <p>
 * 设计说明：Redis 在单机/联调环境默认关闭，此时迁移票据、在线表、排行榜等
 * 全部回退到进程内存实现；只有需要多节点共享状态（center.mode=remote）时才开启。
 * 因此这里用 {@code @ConditionalOnProperty} 整体跳过 Bean 创建，避免无效连接占用资源。
 */
@Configuration // 声明为 Spring 配置类
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "enabled", havingValue = "true")
// 条件注解：仅当配置 lunarcore.redis.enabled=true 时，本类中的 @Bean 才会被创建
public class LunarRedisConfiguration {

    // 本类专用日志对象：记录 Redis 连接装配结果，便于启动排查
    private static final Logger log = LoggerFactory.getLogger(LunarRedisConfiguration.class);

    /**
     * 创建 Lettuce 连接工厂 Bean。
     * <p>
     * 通过 {@link LunarCoreProperties.RedisProperties} 中的 host/port/database/password
     * 组装单机连接配置：
     * <ul>
     *   <li>{@code database} 用 {@code Math.max(0, ...)} 兜底，避免配置成负数导致连接失败；</li>
     *   <li>password 非空时才设置 {@link RedisPassword}，空密码不调用（免认证模式）。</li>
     * </ul>
     * 返回的工厂被 {@link #stringRedisTemplate} 与 {@link #redisMessageListenerContainer} 共用，
     * 保证整个应用共享同一连接池。
     */
    @Bean // 注册为 Spring Bean，方法名为 Bean 名（lunarRedisConnectionFactory）
    public LettuceConnectionFactory lunarRedisConnectionFactory(LunarCoreProperties properties) {
        // 取出 yml 中 lunarcore.redis.* 配置段（对象始终非 null，字段有默认值兜底）
        LunarCoreProperties.RedisProperties redis = properties.getRedis();
        // 组装单机连接参数：主机 + 端口
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
        // 指定逻辑库编号；负数按 0 处理，防止非法库号
        config.setDatabase(Math.max(0, redis.getDatabase()));
        // 密码非空时设置认证凭据；空白视为免密，不写入配置
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            config.setPassword(RedisPassword.of(redis.getPassword()));
        }
        // 打印连接目标（不打印密码，避免敏感信息进日志）
        log.info("Redis enabled: host={}, port={}, db={}", redis.getHost(), redis.getPort(), redis.getDatabase());
        // 基于上面配置创建连接工厂（懒连接：首次使用时才真正建立连接）
        return new LettuceConnectionFactory(config);
    }

    /**
     * 创建字符串 Redis 模板 Bean。
     * <p>
     * 使用 String→String 模板的原因：本项目的 Redis 用途（票据、在线集合、排行榜、
     * Pub/Sub 消息）均以字符串编码即可表达，无需引入 Java 对象序列化带来的
     * 兼容性与安全风险（反序列化漏洞）。
     */
    @Bean // 注册为 Spring Bean
    public StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory lunarRedisConnectionFactory) {
        // 复用同一个连接工厂，保证与监听容器共享连接池
        return new StringRedisTemplate(lunarRedisConnectionFactory);
    }

    /**
     * 创建 Redis Pub/Sub 消息监听容器 Bean。
     * <p>
     * 用于跨节点世界/私聊消息路由：各游戏节点订阅同一频道，收到消息后在本节点
     * 查找在线会话并推送。容器只配置了连接工厂，具体频道与监听器由业务侧通过
     * {@code addMessageListener} 动态注册。
     */
    @Bean // 注册为 Spring Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            LettuceConnectionFactory lunarRedisConnectionFactory) {
        // 实例化监听容器（用于管理订阅关系与消息分发线程）
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        // 指定连接来源：与 StringRedisTemplate 共享同一连接工厂
        container.setConnectionFactory(lunarRedisConnectionFactory);
        return container;
    }
}
