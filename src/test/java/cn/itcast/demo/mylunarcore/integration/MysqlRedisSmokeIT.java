package cn.itcast.demo.mylunarcore.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testcontainers 冒烟：MySQL JDBC + Redis 端口可达。
 * 设置 {@code RUN_TESTCONTAINERS=true} 且本机 Docker 可用时执行。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@DisplayName("MySQL/Redis Testcontainers 冒烟")
class MysqlRedisSmokeIT {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("lunarcore")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Test
    void mysqlAndRedisReachable() throws Exception {
        Assumptions.assumeTrue(mysql.isRunning());
        Assumptions.assumeTrue(redis.isRunning());
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(redis.getHost(), redis.getMappedPort(6379)), 3000);
            assertTrue(socket.isConnected());
        }
    }
}
