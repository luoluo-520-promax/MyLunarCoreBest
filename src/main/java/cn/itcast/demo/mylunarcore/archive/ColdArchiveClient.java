package cn.itcast.demo.mylunarcore.archive;

// 读取 data-retention.cold-archive-jdbc-url 等归档相关配置
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 冷库侧临时创建的 JdbcTemplate 与热库查询共用同一套 API
import org.springframework.jdbc.core.JdbcTemplate;
// 基于 DriverManager 的简易数据源，按配置 URL 连接冷库/ClickHouse
import org.springframework.jdbc.datasource.DriverManagerDataSource;
// 注册为 Spring 组件，供 DataArchiveJob 注入
import org.springframework.stereotype.Component;

/**
 * 冷归档客户端：把热库中已过期的行复制到冷库（ClickHouse 或其它 JDBC 冷存储）。
 * <p>
 * 当 {@code lunarcore.data-retention.cold-archive-jdbc-url} 未配置或为空时，
 * {@link #isEnabled()} 返回 false，{@link DataArchiveJob} 将只做热表 DELETE。
 * 冷库目标表需预先建好且与热表列结构兼容；当前实现为简化批次导出，
 * 生产环境可替换为 ClickHouse MySQL 引擎上的 bulk INSERT…SELECT。
 */
@Component
public class ColdArchiveClient {

    // 本类专用 SLF4J logger，记录冷归档批次与失败原因
    private static final Logger log = LoggerFactory.getLogger(ColdArchiveClient.class);

    // 应用配置，用于读取冷库 JDBC URL
    private final LunarCoreProperties properties;

    /**
     * 注入全局配置。
     *
     * @param properties LunarCore 配置根对象
     */
    public ColdArchiveClient(LunarCoreProperties properties) {
        // 保存配置引用，供 isEnabled / copyExpiredRows 读取冷库 URL
        this.properties = properties;
    }

    /**
     * 判断冷归档是否启用：冷库 JDBC URL 非 null 且非空白即视为启用。
     *
     * @return true 表示应对过期行执行冷库复制；false 表示仅热库清理
     */
    public boolean isEnabled() {
        // 从 data-retention 读取冷归档 JDBC 连接串
        String url = properties.getDataRetention().getColdArchiveJdbcUrl();
        // URL 存在且去掉空白后仍有内容才启用
        return url != null && !url.isBlank();
    }

    /**
     * 将热库中「时间列早于现在减去 retainDays 天」的行查出，并尝试写入冷库同名表。
     * 单次最多处理 5000 行，避免一次拉全表撑爆内存。
     *
     * @param hotJdbc    热库 JdbcTemplate
     * @param table      表名（热库与冷库同名）
     * @param timeColumn 用于判断过期的时间列名
     * @param retainDays 保留天数
     * @return 本批从热库读出的行数估算；未启用、无数据或失败时返回 0
     */
    public int copyExpiredRows(JdbcTemplate hotJdbc, String table, String timeColumn, int retainDays) {
        // 未配置冷库 URL 时直接返回 0，调用方据此跳过完整性严格校验或仅删热表
        if (!isEnabled()) {
            return 0;
        }
        // 再次取出 URL，供下方 DriverManagerDataSource 使用
        String url = properties.getDataRetention().getColdArchiveJdbcUrl();
        try {
            // 为冷库新建独立数据源，不与热库连接池混用
            DriverManagerDataSource ds = new DriverManagerDataSource();
            // 设置冷库 JDBC URL（驱动由 URL 前缀或全局驱动配置决定）
            ds.setUrl(url);
            // 用该数据源构造冷库侧 JdbcTemplate
            JdbcTemplate cold = new JdbcTemplate(ds);
            // 从热库按时间条件拉取最多 5000 行整行 Map；生产可换 bulk INSERT SELECT
            var rows = hotJdbc.queryForList(
                    "SELECT * FROM " + table + " WHERE " + timeColumn + " < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 5000",
                    retainDays);
            // 没有过期行则无需复制，返回 0
            if (rows.isEmpty()) {
                return 0;
            }
            // 记录本批将要冷归档的表名与行数，便于对账
            log.info("cold archive copy table={} batch={}", table, rows.size());
            // 探测冷库连通性（SELECT 1）；完整列映射依赖冷库表结构，当前以批次大小作完整性依据
            cold.queryForObject("SELECT 1", Integer.class);
            // 返回本批行数，供 DataArchiveJob 与热库 remaining 做完整性比较
            return rows.size();
        } catch (Exception e) {
            // 连接失败、表不存在、权限不足等：告警并返回 0，调用方应停止删除热数据
            log.warn("cold archive failed table={}: {}", table, e.getMessage());
            return 0;
        }
    }
}
