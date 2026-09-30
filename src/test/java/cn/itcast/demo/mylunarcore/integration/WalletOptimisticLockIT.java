package cn.itcast.demo.mylunarcore.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钱包乐观锁 CAS 回归：依赖 Docker + {@code RUN_TESTCONTAINERS=true}。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "RUN_TESTCONTAINERS", matches = "true")
@DisplayName("钱包 CAS Testcontainers 集成")
class WalletOptimisticLockIT {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("lunarcore")
            .withUsername("test")
            .withPassword("test");

    @Test
    void optimisticUpdateRequiresMatchingVersion() throws Exception {
        Assumptions.assumeTrue(mysql.isRunning());
        try (Connection c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE player_wallet (
                      player_id INT PRIMARY KEY,
                      currency_json VARCHAR(512) NOT NULL,
                      data_version INT NOT NULL DEFAULT 0
                    )
                    """);
            st.execute("INSERT INTO player_wallet(player_id, currency_json, data_version) VALUES (1, '{\"2\":100}', 0)");

            // 版本匹配应成功
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player_wallet SET currency_json=?, data_version=data_version+1 WHERE player_id=? AND data_version=?")) {
                ps.setString(1, "{\"2\":80}");
                ps.setInt(2, 1);
                ps.setInt(3, 0);
                assertEquals(1, ps.executeUpdate());
            }
            // 旧版本应失败（双扣窗口防护）
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE player_wallet SET currency_json=?, data_version=data_version+1 WHERE player_id=? AND data_version=?")) {
                ps.setString(1, "{\"2\":60}");
                ps.setInt(2, 1);
                ps.setInt(3, 0);
                assertEquals(0, ps.executeUpdate());
            }
            try (ResultSet rs = st.executeQuery("SELECT currency_json, data_version FROM player_wallet WHERE player_id=1")) {
                assertTrue(rs.next());
                assertEquals("{\"2\":80}", rs.getString(1));
                assertEquals(1, rs.getInt(2));
            }
        }
    }
}
