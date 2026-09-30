package cn.itcast.demo.mylunarcore.infra.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 读写分离 + 影子库路由：默认关闭；开启后按 {@link RoutingDataSourceContext} 选库。
 * <pre>
 * lunarcore.datasource.routing-enabled=true
 * spring.datasource.url=...
 * lunarcore.datasource.replica-url=...
 * lunarcore.datasource.shadow-url=...
 * </pre>
 */
@Configuration
@ConditionalOnProperty(prefix = "lunarcore.datasource", name = "routing-enabled", havingValue = "true")
public class RoutingDataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driver,
            @Value("${lunarcore.datasource.replica-url:}") String replicaUrl,
            @Value("${lunarcore.datasource.shadow-url:}") String shadowUrl,
            @Value("${lunarcore.datasource.fallback-replica-to-primary:true}") boolean fallbackReplica) {
        DataSource primary = hikari("primary", primaryUrl, username, password, driver);
        DataSource replica = (replicaUrl == null || replicaUrl.isBlank())
                ? (fallbackReplica ? primary : hikari("replica", primaryUrl, username, password, driver))
                : hikari("replica", replicaUrl, username, password, driver);
        DataSource shadow = (shadowUrl == null || shadowUrl.isBlank())
                ? primary
                : hikari("shadow", shadowUrl, username, password, driver);

        Map<Object, Object> targets = new HashMap<>();
        targets.put(DataSourceType.PRIMARY, primary);
        targets.put(DataSourceType.REPLICA, replica);
        targets.put(DataSourceType.SHADOW, shadow);

        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return RoutingDataSourceContext.get();
            }
        };
        routing.setDefaultTargetDataSource(primary);
        routing.setTargetDataSources(targets);
        routing.afterPropertiesSet();
        return routing;
    }

    private static DataSource hikari(String poolName, String url, String user, String pass, String driver) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("MyLunarCore-" + poolName);
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);
        ds.setDriverClassName(driver);
        ds.setMaximumPoolSize(10);
        return ds;
    }
}
