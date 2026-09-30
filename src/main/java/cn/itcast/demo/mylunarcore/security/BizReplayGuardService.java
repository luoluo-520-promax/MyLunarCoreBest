package cn.itcast.demo.mylunarcore.security;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 关键业务防重放服务（MySQL 唯一索引实现）。
 * <p>
 * 与 Redis 版（{@link RedisBizReplayGuard}）互补：本实现依赖数据库表
 * {@code biz_replay_guard} 上 {@code guard_key} 的唯一索引来判定重复请求——
 * 首次 INSERT 成功（说明该 key 从未出现），重复 INSERT 触发
 * {@link DuplicateKeyException}（说明同样的业务唯一标识已被处理过），即视为重放。
 * <p>
 * 使用场景：支付（iap）、抽卡（gacha）等一旦重复执行会造成资产多发/扣错的敏感业务，
 * 必须在入库/发货前调用 {@link #tryAcquire(String, int, String)} 做幂等拦截。
 */
@Service
public class BizReplayGuardService {

    /** JDBC 模板，用于向 biz_replay_guard 表执行幂等 INSERT。 */
    private final JdbcTemplate jdbc;

    /** 业务监控指标，用于统计被拦截的 IAP / 抽卡重放次数。 */
    private final BusinessMetrics metrics;

    public BizReplayGuardService(JdbcTemplate jdbc, BusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /**
     * 尝试为「业务类型 + 玩家 + 流水号」占用一个防重放凭证。
     *
     * @param bizType     业务类型（如 "iap"、"gacha"），作为唯一键前缀
     * @param playerId    玩家 ID，随记录落库便于事后审计
     * @param nonceOrTxId 客户端 nonce 或渠道交易号 channelTx，作为防重放唯一键内容
     * @return true 表示首次见到该 key，可继续执行业务；false 表示重放，应拒绝
     */
    public boolean tryAcquire(String bizType, int playerId, String nonceOrTxId) {
        // 无 nonce 时跳过（兼容旧客户端）；支付路径应强制传入 channelTx
        if (nonceOrTxId == null || nonceOrTxId.isBlank()) {
            return true;
        }
        // 组装唯一键：{bizType}:{流水号}，trim 去除空白避免同一笔请求因前后空格产生不同 key
        String key = bizType + ":" + nonceOrTxId.trim();
        try {
            // INSERT INTO biz_replay_guard (guard_key, biz_type, player_id, created_at) VALUES (?,?,?,?)
            // 依赖 guard_key 列的唯一索引实现幂等：重复插入会抛 DuplicateKeyException。
            // created_at 存 ISO-8601 字符串（Instant.now().toString()），便于查看提交时间。
            jdbc.update("""
                    INSERT INTO biz_replay_guard (guard_key, biz_type, player_id, created_at)
                    VALUES (?, ?, ?, ?)
                    """, key, bizType, playerId, Instant.now().toString());
            return true;
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：说明该业务唯一标识已被处理过 → 判定为重放请求
            if ("iap".equals(bizType) || "gacha".equals(bizType)) {
                // 支付/抽卡被重放属于高危事件，上报监控指标用于告警与审计
                metrics.recordIapReplayRejected();
            }
            return false;
        } catch (Exception e) {
            // 兜底：表未初始化/数据库临时异常时不阻断主流程（开发环境），
            // 以“放行”优先保证可用性；生产环境依赖该表已通过迁移脚本创建
            return true;
        }
    }
}
