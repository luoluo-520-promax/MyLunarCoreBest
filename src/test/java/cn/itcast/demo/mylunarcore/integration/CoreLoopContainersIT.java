package cn.itcast.demo.mylunarcore.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 核心链路 Testcontainers 骨架：MySQL + Redis。
 * 需 Docker 且显式 {@code RUN_TESTCONTAINERS=true}。
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
class CoreLoopContainersIT {

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
    void mysqlAndRedisAreReachable() throws Exception {
        Assumptions.assumeTrue(mysql.isRunning());
        Assumptions.assumeTrue(redis.isRunning());
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS local_tx_log (
                      tx_id VARCHAR(64) PRIMARY KEY,
                      biz_type VARCHAR(64) NOT NULL,
                      player_id INT NOT NULL,
                      payload_json TEXT,
                      status VARCHAR(32) NOT NULL,
                      created_at VARCHAR(40) NOT NULL,
                      updated_at VARCHAR(40) NOT NULL
                    )
                    """);
            st.execute("INSERT INTO local_tx_log VALUES ('t1','gacha',1,'{}','PENDING','now','now')");
            assertTrue(st.executeQuery("SELECT 1 FROM local_tx_log WHERE tx_id='t1'").next());
        }
        assertTrue(redis.getMappedPort(6379) > 0);
    }
}
