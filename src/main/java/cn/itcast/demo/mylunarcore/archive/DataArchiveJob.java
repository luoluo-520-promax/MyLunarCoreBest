package cn.itcast.demo.mylunarcore.archive;

// 统一封装的业务日志入口，按类别路由到不同 logger
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 集群定时任务互斥锁：保证多节点下同一任务同一时刻只跑一个实例
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
// 日志业务分类枚举，此处使用 SYSTEM 表示系统运维类日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// 应用配置根对象，归档保留天数、开关、cron 等均从此读取
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
// Spring JDBC 模板，用于执行热库 DELETE / COUNT SQL
import org.springframework.jdbc.core.JdbcTemplate;
// 声明基于 cron 的定时调度方法
import org.springframework.scheduling.annotation.Scheduled;
// 注册为 Spring 单例组件，随容器启动并参与定时调度
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 历史数据归档定时任务：在多节点部署下通过 {@link ClusterJobLock} 抢锁后单点执行。
 * <p>
 * 核心策略：
 * <ul>
 *   <li>按业务表分别配置保留天数，到期行从热库删除；</li>
 *   <li>可通过 {@code categoryRetention} 覆盖默认类别天数；</li>
 *   <li>若配置了冷库 JDBC URL，则先经 {@link ColdArchiveClient} 复制再删热表，
 *       并校验冷库复制数与热库剩余过期行数，避免漏拷即删。</li>
 * </ul>
 */
@Component
public class DataArchiveJob {

    // SYSTEM 类别下的本类专用 logger，便于运维按类别过滤归档日志
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, DataArchiveJob.class);

    // 热库 JDBC，执行各业务表的删除与过期行计数
    private final JdbcTemplate jdbc;
    // 全局配置：归档开关、各类保留天数、类别覆盖串等
    private final LunarCoreProperties properties;
    // 集群任务锁，避免多副本同时归档造成重复删除或冷库双写
    private final ClusterJobLock clusterJobLock;
    // 冷归档客户端：可选地把过期行复制到 ClickHouse/冷库
    private final ColdArchiveClient coldArchiveClient;
    private final ObjectProvider<cn.itcast.demo.mylunarcore.item.ExpiredItemRecycleService> recycleProvider;

    public DataArchiveJob(JdbcTemplate jdbc,
                          LunarCoreProperties properties,
                          ClusterJobLock clusterJobLock,
                          ColdArchiveClient coldArchiveClient) {
        this(jdbc, properties, clusterJobLock, coldArchiveClient, null);
    }

    public DataArchiveJob(JdbcTemplate jdbc,
                          LunarCoreProperties properties,
                          ClusterJobLock clusterJobLock,
                          ColdArchiveClient coldArchiveClient,
                          org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.item.ExpiredItemRecycleService> recycleProvider) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clusterJobLock = clusterJobLock;
        this.coldArchiveClient = coldArchiveClient;
        this.recycleProvider = recycleProvider;
    }

    /**
     * 定时入口：默认每天 03:30 执行（可由 lunarcore.data-retention.cron 覆盖）。
     * 若配置关闭归档任务则直接返回；否则尝试抢锁并执行 {@link #doArchive()}。
     */
    @Scheduled(cron = "${lunarcore.data-retention.cron:0 30 3 * * *}")
    public void archive() {
        // 读取 data-retention.archive-job-enabled；关闭时跳过本次调度，避免误删
        if (!properties.getDataRetention().isArchiveJobEnabled()) {
            return;
        }
        // 锁名 data-archive，最长持锁 30 分钟；抢到锁才回调 doArchive，抢不到则本节点空过
        clusterJobLock.tryRun("data-archive", Duration.ofMinutes(30), this::doArchive);
    }

    /**
     * 实际归档逻辑：解析各类保留天数后，依次清理抽卡流水、账本、审计、
     * 重放防护、本地事务日志、离线聊天与战斗审计等表。
     */
    private void doArchive() {
        // 合并默认配置与 categoryRetention 覆盖后的「类别 -> 保留天数」映射
        Map<String, Integer> days = resolveRetentionDays();
        // 抽卡历史：优先用类别 gacha 覆盖值，否则用配置 gachaHistoryDays
        int gachaDays = days.getOrDefault("gacha", properties.getDataRetention().getGachaHistoryDays());
        // 战斗审计：优先用类别 battle 覆盖值
        int battleDays = days.getOrDefault("battle", properties.getDataRetention().getBattleAuditDays());
        // 管理审计：优先用类别 audit；下限至少 30 天，防止配太短导致合规日志被清
        int auditDays = days.getOrDefault("audit",
                Math.max(30, properties.getDataRetention().getAdminAuditDays()));
        // 钱包流水：优先用类别 ledger；下限至少 30 天
        int ledgerDays = days.getOrDefault("ledger",
                Math.max(30, properties.getDataRetention().getLedgerArchiveDays()));
        // 离线聊天：优先用类别 chat；下限至少 7 天
        int chatDays = days.getOrDefault("chat",
                Math.max(7, properties.getDataRetention().getChatArchiveDays()));
        // 重放防护：优先用类别 replay；下限至少 7 天
        int replayDays = days.getOrDefault("replay",
                Math.max(7, properties.getDataRetention().getReplayGuardDays()));

        // 清理抽卡抽奖历史表中超过 gachaDays 的行（可先冷归档再删）
        archiveTable("gacha_draw_history", "created_at", gachaDays);
        // 清理钱包账本表中超过 ledgerDays 的行
        archiveTable("wallet_ledger", "created_at", ledgerDays);
        // 清理后台操作审计表中超过 auditDays 的行
        archiveTable("admin_audit_log", "created_at", auditDays);
        try {
            // 删除 biz_replay_guard 中创建时间早于「当前时刻减去 replayDays」的防重放记录
            int replay = jdbc.update("""
                    DELETE FROM biz_replay_guard
                    WHERE created_at < ?
                    """, Instant.now().minus(Duration.ofDays(replayDays)).toString());
            // 记录本次重放防护清理影响行数与所用保留天数
            log.info("Purged replay guard rows={} retentionDays={}", replay, replayDays);
        } catch (Exception e) {
            // 表不存在或 SQL 方言不兼容时降级为 debug，不中断后续归档
            log.debug("replay purge skipped: {}", e.getMessage());
        }
        try {
            // 删除本地事务日志中已提交/已补偿且超过 gachaDays 的记录（与抽卡流水同周期）
            int tx = jdbc.update("""
                    DELETE FROM local_tx_log
                    WHERE status IN ('COMMITTED','COMPENSATED') AND created_at < ?
                    """, Instant.now().minus(Duration.ofDays(gachaDays)).toString());
            // 记录本地事务日志清理行数
            log.info("Purged local tx log rows={}", tx);
        } catch (Exception e) {
            // 表缺失等情况跳过，避免整次归档失败
            log.debug("tx log purge skipped: {}", e.getMessage());
        }
        try {
            // 删除已投递（delivered=1）且创建超过 chatDays 天的离线聊天消息
            int offlineChat = jdbc.update("""
                    DELETE FROM offline_chat_message
                    WHERE delivered = 1 AND created_at < DATE_SUB(NOW(), INTERVAL ? DAY)
                    """, chatDays);
            // 记录离线聊天清理结果
            log.info("Purged delivered offline chat rows={} retentionDays={}", offlineChat, chatDays);
        } catch (Exception e) {
            // 离线聊天表不可用时跳过
            log.debug("offline chat purge skipped: {}", e.getMessage());
        }
        // 战斗审计若已落表，按 battleDays 做与其它表相同的冷归档+热表删除
        archiveTable("battle_audit_log", "created_at", battleDays);
        cn.itcast.demo.mylunarcore.item.ExpiredItemRecycleService recycle =
                recycleProvider == null ? null : recycleProvider.getIfAvailable();
        if (recycle != null) {
            try {
                recycle.recycleDue();
            } catch (Exception e) {
                log.debug("expired item recycle skipped: {}", e.getMessage());
            }
        }
        log.info("data-archive done categories={}", days);
    }

    /**
     * 解析各类别保留天数：先写入配置默认值，再解析 {@code categoryRetention}
     * （形如 {@code gacha=90,chat=14}）覆盖同名键；非法片段忽略。
     *
     * @return 可变的类别到天数映射（键小写）
     */
    Map<String, Integer> resolveRetentionDays() {
        // 存放最终生效的类别保留天数
        Map<String, Integer> map = new HashMap<>();
        // 取出 data-retention 子配置，避免重复 getter 链
        LunarCoreProperties.DataRetentionProperties dr = properties.getDataRetention();
        // 默认：抽卡历史保留天数
        map.put("gacha", dr.getGachaHistoryDays());
        // 默认：战斗审计保留天数
        map.put("battle", dr.getBattleAuditDays());
        // 默认：聊天归档保留天数
        map.put("chat", dr.getChatArchiveDays());
        // 默认：账本归档保留天数
        map.put("ledger", dr.getLedgerArchiveDays());
        // 默认：管理审计保留天数
        map.put("audit", dr.getAdminAuditDays());
        // 默认：重放防护保留天数
        map.put("replay", dr.getReplayGuardDays());
        // 读取可选覆盖串，例如 "gacha=60,audit=180"
        String raw = dr.getCategoryRetention();
        // 未配置或全空白则直接返回默认映射
        if (raw == null || raw.isBlank()) {
            return map;
        }
        // 按逗号拆成多个「键=值」片段
        for (String part : raw.split(",")) {
            // 每个片段再按第一个等号拆成类别名与天数
            String[] kv = part.trim().split("=");
            // 不是恰好两段则跳过（容忍空片段或格式错误）
            if (kv.length != 2) {
                continue;
            }
            try {
                // 类别名转小写后写入，覆盖同名默认值；天数必须是整数
                map.put(kv[0].trim().toLowerCase(), Integer.parseInt(kv[1].trim()));
            } catch (NumberFormatException ignored) {
                // 天数非数字时忽略该片段，保留原默认值
            }
        }
        // 返回合并覆盖后的映射
        return map;
    }

    /**
     * 对单表执行「可选冷归档 + 热库删除」。
     * 冷归档开启时：先复制过期行，若热库仍有过期行且复制数不足则中止删除以防丢数据。
     *
     * @param table      热库表名
     * @param timeColumn 时间列名（用于判断是否过期，通常为 created_at）
     * @param retainDays 保留天数，早于「现在 - retainDays」的行视为过期
     */
    private void archiveTable(String table, String timeColumn, int retainDays) {
        try {
            // 冷库 JDBC URL 已配置时，先尝试把过期行拷到冷库
            if (coldArchiveClient.isEnabled()) {
                // 从热库查出过期行并复制到冷库，返回本批复制行数估算
                int copied = coldArchiveClient.copyExpiredRows(jdbc, table, timeColumn, retainDays);
                // 再统计热库中仍未删的过期行数，用于完整性对比
                int remaining = countExpired(table, timeColumn, retainDays);
                // 热库仍有过期行，且冷库复制数小于剩余数：认为拷贝不完整，拒绝删除
                if (remaining > 0 && copied < remaining) {
                    log.warn("cold archive integrity gap table={} copied={} remaining={}",
                            table, copied, remaining);
                    // 直接返回，本表本轮不删热数据
                    return;
                }
                // 完整性通过或无需对比时记录冷归档批次规模
                log.info("Cold-archived table={} rows={}", table, copied);
            }
            // 删除热库中时间列早于「现在减去 retainDays 天」的行；? 绑定 retainDays
            int deleted = jdbc.update(
                    "DELETE FROM " + table + " WHERE " + timeColumn + " < DATE_SUB(NOW(), INTERVAL ? DAY)",
                    retainDays);
            // 记录热表清理行数与所用保留天数
            log.info("Purged hot table={} rows={} retentionDays={}", table, deleted, retainDays);
        } catch (Exception e) {
            // 单表失败（表不存在、权限、方言差异等）只打 debug，继续其它表
            log.debug("archive skipped table={}: {}", table, e.getMessage());
        }
    }

    /**
     * 统计热库指定表中仍未删除的过期行数（时间列 &lt; NOW() - retainDays）。
     *
     * @param table      表名
     * @param timeColumn 时间列名
     * @param retainDays 保留天数
     * @return 过期行数；查询结果为 null 时按 0 处理
     */
    private int countExpired(String table, String timeColumn, int retainDays) {
        // 执行 COUNT(*)，第三个参数 bind retainDays 到 INTERVAL ? DAY
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + timeColumn + " < DATE_SUB(NOW(), INTERVAL ? DAY)",
                Integer.class, retainDays);
        // JDBC 可能返回 null，统一归一为 0 供完整性比较
        return n == null ? 0 : n;
    }
}
