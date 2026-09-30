package cn.itcast.demo.mylunarcore.repo;

// 邮件实体：标题、正文、附件 JSON、状态与过期时间等
import cn.itcast.demo.mylunarcore.model.MailEntity;
import org.springframework.jdbc.core.JdbcTemplate;
// 用于 INSERT 后取回自增主键 id
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 玩家邮件表 {@code mail} 的 JDBC 仓储。
 * <p>
 * 状态约定（与业务层一致）：{@code status < 3} 视为对玩家可见的有效邮件
 * （通常 0=未读，1=已读，2=已领附件等；3 及以上表示删除/归档，列表与计数均排除）。
 * 写操作均带 {@code player_id} 条件，防止跨玩家篡改。
 */
@Repository
public class MailRepository {

    // 热库 JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate Spring 注入的数据源模板
     */
    public MailRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 统计玩家当前邮箱中可见邮件数量（{@code status < 3}）。
     *
     * @param playerId 玩家 ID
     * @return 可见邮件数；查询结果为 null 时按 0
     */
    public long countMails(int playerId) {
        // status<3：排除已删除/归档邮件，用于邮箱容量或红点计数
        String sql = "SELECT COUNT(*) FROM mail WHERE player_id = ? AND status < 3";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, playerId);
        return count == null ? 0 : count;
    }

    /**
     * 分页列出可见邮件，按 id 降序（新邮件在前）。
     *
     * @param playerId 玩家 ID
     * @param page     页码，从 1 开始；小于 1 时 offset 仍按 0 起算
     * @param pageSize 每页条数，绑定到 LIMIT
     * @return 当前页实体列表
     */
    public List<MailEntity> listMails(int playerId, int page, int pageSize) {
        // page 从 1 开始：offset = (page-1)*pageSize，且不为负
        int offset = Math.max(0, (page - 1) * pageSize);
        String sql = "SELECT * FROM mail WHERE player_id = ? AND status < 3 ORDER BY id DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, pageSize, offset);
    }

    /**
     * 按邮件主键与玩家 ID 查询单封邮件（双重条件防越权）。
     *
     * @param playerId 玩家 ID
     * @param mailId   邮件自增主键
     * @return 实体；不存在或不属于该玩家时返回 null
     */
    public MailEntity findMail(int playerId, long mailId) {
        String sql = "SELECT * FROM mail WHERE id = ? AND player_id = ? LIMIT 1";
        List<MailEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), mailId, playerId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入一封新邮件，初始 {@code status=0}（未读），并返回自增 id。
     *
     * @param playerId        收件玩家
     * @param title           标题
     * @param content         正文
     * @param attachmentsJson 附件 JSON（道具/货币等序列化串，可为 null）
     * @param expireTime      过期时间；到期后由业务层决定是否仍可领取
     * @return 新邮件主键；取键失败时返回 0
     */
    public long insertMail(int playerId, String title, String content, String attachmentsJson, Timestamp expireTime) {
        // status 固定写 0：新建即为未读
        String sql = "INSERT INTO mail(player_id, title, content, status, attachments_json, expire_time) VALUES(?, ?, ?, 0, ?, ?)";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            // 显式声明回填列名为 id，兼容部分 JDBC 驱动
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, playerId);
            ps.setString(2, title);
            ps.setString(3, content);
            ps.setString(4, attachmentsJson);
            ps.setTimestamp(5, expireTime);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        // 驱动未回填主键时退回 0，调用方应视为插入异常
        return key == null ? 0 : key.longValue();
    }

    /**
     * 更新邮件状态（已读/已领/删除等），并刷新 {@code updated_at}。
     *
     * @param mailId   邮件 ID
     * @param playerId 玩家 ID（所有权校验）
     * @param status   目标状态码
     * @return 受影响行数；0 表示邮件不存在或不属于该玩家
     */
    public int updateStatus(long mailId, int playerId, int status) {
        String sql = "UPDATE mail SET status = ?, updated_at = NOW() WHERE id = ? AND player_id = ?";
        return jdbcTemplate.update(sql, status, mailId, playerId);
    }

    /**
     * 将 {@code mail} 表一行映射为 {@link MailEntity}。
     */
    private MailEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        MailEntity m = new MailEntity();
        m.setId(rs.getLong("id")); // 自增主键
        m.setPlayerId(rs.getInt("player_id")); // 收件人
        m.setTitle(rs.getString("title")); // 标题
        m.setContent(rs.getString("content")); // 正文
        m.setStatus(rs.getInt("status")); // 未读/已读/已领/删除等状态码
        m.setAttachmentsJson(rs.getString("attachments_json")); // 附件序列化 JSON
        m.setSendTime(rs.getTimestamp("send_time")); // 发送时间（库默认或触发器）
        m.setExpireTime(rs.getTimestamp("expire_time")); // 过期时间
        m.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
        m.setUpdatedAt(rs.getTimestamp("updated_at")); // 最近状态变更时间
        return m;
    }
}
