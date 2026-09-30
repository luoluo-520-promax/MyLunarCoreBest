package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端：与 lunarcore.redis.* 对齐。
 */
@Configuration
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "redisson-enabled", havingValue = "true")
public class RedissonClientConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(LunarCoreProperties properties) {
        LunarCoreProperties.RedisProperties redis = properties.getRedis();
        Config config = new Config();
        String host = redis.getHost() == null || redis.getHost().isBlank() ? "127.0.0.1" : redis.getHost();
        int port = redis.getPort() <= 0 ? 6379 : redis.getPort();
        String address = "redis://" + host + ":" + port;
        var single = config.useSingleServer().setAddress(address).setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
            single.setPassword(redis.getPassword());
        }
        return Redisson.create(config);
    }
}
