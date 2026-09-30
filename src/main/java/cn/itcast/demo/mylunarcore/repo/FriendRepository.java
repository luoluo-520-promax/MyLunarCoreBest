package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.FriendEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 好友关系仓储：负责 friend 表的查询、邀请、确认与状态更新。
 * <p>
 * 这张表的核心约束是“两个玩家一条关系”，因此大多数方法都会先把双方玩家 id
 * 规范成小 id 在前、大 id 在后，避免同一对好友因为传参顺序不同而出现重复记录。
 */
@Repository
public class FriendRepository {

    /** JDBC 模板，所有好友关系 SQL 都通过它执行。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入的数据库访问工具
     */
    public FriendRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询某个玩家当前可见的好友关系。
     * <p>
     * 这里只返回状态为 0 或 1 的关系，通常表示“申请中/已确认”，不包含已删除或已拒绝的历史关系。
     *
     * @param playerId 玩家 uid
     * @return 好友关系列表
     */
    public List<FriendEntity> listFriends(int playerId) {
        String sql = "SELECT * FROM friend WHERE (player_id_1 = ? OR player_id_2 = ?) AND status IN (0, 1)";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, playerId);
    }

    /**
     * 根据两个玩家 id 查询双方关系记录。
     * <p>
     * 为了让数据库中的唯一关系顺序稳定，这里会先做 min/max 归一化，再去 friend 表查唯一行。
     *
     * @param playerId1 玩家 A
     * @param playerId2 玩家 B
     * @return 关系实体；不存在返回 null
     */
    public FriendEntity findRelation(int playerId1, int playerId2) {
        int a = Math.min(playerId1, playerId2);
        int b = Math.max(playerId1, playerId2);
        String sql = "SELECT * FROM friend WHERE player_id_1 = ? AND player_id_2 = ? LIMIT 1";
        List<FriendEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), a, b);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 插入一条好友申请记录，状态固定为 0（待确认）。
     *
     * @param requesterId 申请方 uid
     * @param targetId 目标方 uid
     * @param remark 申请附言
     * @return 新插入记录的自增主键；失败时返回 0
     */
    public long insertRequest(int requesterId, int targetId, String remark) {
        int a = Math.min(requesterId, targetId);
        int b = Math.max(requesterId, targetId);
        String sql = "INSERT INTO friend(player_id_1, player_id_2, status, remark) VALUES(?, ?, 0, ?)";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, a); // 小 id 放在前面，保证关系唯一顺序稳定
            ps.setInt(2, b); // 大 id 放在后面
            ps.setString(3, remark);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    /**
     * 更新好友关系状态。
     * <p>
     * 当状态为 1 时代表确认好友，同时写入确认时间；其他状态则只更新状态本身。
     *
     * @param playerId1 玩家 A
     * @param playerId2 玩家 B
     * @param status 目标状态码
     * @return 受影响行数
     */
    public int updateStatus(int playerId1, int playerId2, int status) {
        int a = Math.min(playerId1, playerId2);
        int b = Math.max(playerId1, playerId2);
        String sql = "UPDATE friend SET status = ?, confirm_time = ?, updated_at = NOW() WHERE player_id_1 = ? AND player_id_2 = ?";
        Timestamp now = new Timestamp(System.currentTimeMillis());
        return jdbcTemplate.update(sql, status, status == 1 ? now : null, a, b);
    }

    /**
     * 将 friend 表的一行转换为 {@link FriendEntity}。
     * <p>
     * source 字段是可空值，因此这里先读成 int 再通过 {@code rs.wasNull()} 判断是否真的为 NULL。
     */
    private FriendEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        FriendEntity f = new FriendEntity();
        f.setId(rs.getInt("id"));
        f.setPlayerId1(rs.getInt("player_id_1"));
        f.setPlayerId2(rs.getInt("player_id_2"));
        f.setStatus(rs.getInt("status"));
        f.setCreateTime(rs.getTimestamp("create_time"));
        f.setConfirmTime(rs.getTimestamp("confirm_time"));
        int src = rs.getInt("source");
        f.setSource(rs.wasNull() ? null : src);
        f.setRemark(rs.getString("remark"));
        f.setUpdatedAt(rs.getTimestamp("updated_at"));
        return f;
    }
}
