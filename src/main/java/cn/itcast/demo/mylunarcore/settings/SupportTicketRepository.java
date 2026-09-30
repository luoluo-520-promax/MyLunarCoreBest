package cn.itcast.demo.mylunarcore.settings; // 客服工单 JDBC 仓储所在包

import org.springframework.jdbc.core.JdbcTemplate; // 执行增删改查
import org.springframework.jdbc.support.GeneratedKeyHolder; // 取 INSERT 自增主键
import org.springframework.stereotype.Repository; // 持久化组件

import java.sql.PreparedStatement; // 预编译语句，绑定参数并声明回填列
import java.util.List; // 分页查询结果
import java.util.Optional; // 按 id 查单条

/**
 * 访问表 support_ticket：玩家提交、分页列表、客服回复与状态更新。
 */
@Repository // 注册为 Spring Repository
public class SupportTicketRepository {

    /**
     * JDBC 模板，复用数据源连接池
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造注入 JdbcTemplate
     */
    public SupportTicketRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存 SQL 执行入口
    } // 构造结束

    /**
     * 插入新工单，初始 status=0（待处理）；返回自增 id，失败返回 0
     *
     * @param playerId 提交者 uid
     * @param category 已归一化的分类
     * @param subject  标题
     * @param content  正文
     * @return 新工单主键
     */
    public long insert(int playerId, String category, String subject, String content) {
        String sql = "INSERT INTO support_ticket(player_id, category, subject, content, status) VALUES(?, ?, ?, ?, 0)"; // 固定待处理
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder(); // 承接自增 id
        jdbcTemplate.update(con -> { // 使用回调以便指定回填列名 id
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"}); // 声明返回 id 列
            ps.setInt(1, playerId); // 绑定玩家
            ps.setString(2, category); // 绑定分类
            ps.setString(3, subject); // 绑定标题
            ps.setString(4, content); // 绑定正文
            return ps; // 交给模板执行
        }, keyHolder); // 执行后 keyHolder 填入主键
        Number key = keyHolder.getKey(); // 读取生成的数字主键
        return key == null ? 0L : key.longValue(); // 驱动未回填时返回 0 表示失败
    } // insert 结束

    /**
     * 统计某玩家历史工单总数（含已关闭），用于防刷上限判断
     */
    public int countByPlayer(int playerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM support_ticket WHERE player_id = ?", Long.class, playerId); // 按玩家计数
        return count == null ? 0 : count.intValue(); // NULL 理论上不应出现，防御为 0
    } // countByPlayer 结束

    /**
     * 玩家侧分页列表：仅本人工单，按 id 倒序（新单在前）；page 从 1 起，pageSize 限制在 1–50
     */
    public List<SupportTicketEntity> listByPlayer(int playerId, int page, int pageSize) {
        int safePage = Math.max(1, page); // 页码至少为 1
        int safeSize = Math.max(1, Math.min(50, pageSize)); // 单页最多 50，防止一次拉全表
        int offset = (safePage - 1) * safeSize; // 计算 OFFSET
        String sql = "SELECT * FROM support_ticket WHERE player_id = ? ORDER BY id DESC LIMIT ? OFFSET ?"; // 本人倒序分页
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, safeSize, offset); // 行映射为实体
    } // listByPlayer 结束

    /**
     * 客服后台分页：status=null 查全部状态；否则按状态过滤；pageSize 上限 100
     */
    public List<SupportTicketEntity> listByStatus(Integer status, int page, int pageSize) {
        int safePage = Math.max(1, page); // 页码下限
        int safeSize = Math.max(1, Math.min(100, pageSize)); // 后台可略大
        int offset = (safePage - 1) * safeSize; // OFFSET
        if (status == null) { // 未指定状态=全部工单
            return jdbcTemplate.query(
                    "SELECT * FROM support_ticket ORDER BY id DESC LIMIT ? OFFSET ?",
                    (rs, rowNum) -> mapRow(rs), safeSize, offset); // 全表倒序分页
        } // 全量分支结束
        return jdbcTemplate.query(
                "SELECT * FROM support_ticket WHERE status = ? ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapRow(rs), status, safeSize, offset); // 按状态筛选
    } // listByStatus 结束

    /**
     * 按主键查单条工单；不存在返回 empty
     */
    public Optional<SupportTicketEntity> findById(long ticketId) {
        List<SupportTicketEntity> list = jdbcTemplate.query(
                "SELECT * FROM support_ticket WHERE id = ? LIMIT 1",
                (rs, rowNum) -> mapRow(rs), ticketId); // 主键精确查
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0)); // 0 或 1 条
    } // findById 结束

    /**
     * 写入客服回复与新状态，并刷新 updated_at；返回受影响行数（0=工单不存在）
     */
    public int reply(long ticketId, String adminReply, int status) {
        String sql = "UPDATE support_ticket SET admin_reply = ?, status = ?, updated_at = NOW() WHERE id = ?"; // 回复+改状态
        return jdbcTemplate.update(sql, adminReply, status, ticketId); // 按 id 更新
    } // reply 结束

    /**
     * 将当前 ResultSet 游标行映射为 SupportTicketEntity
     */
    private SupportTicketEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        SupportTicketEntity e = new SupportTicketEntity(); // 新建实体
        e.setId(rs.getLong("id")); // 主键
        e.setPlayerId(rs.getInt("player_id")); // 玩家
        e.setCategory(rs.getString("category")); // 分类
        e.setSubject(rs.getString("subject")); // 标题
        e.setContent(rs.getString("content")); // 正文
        e.setStatus(rs.getInt("status")); // 状态码
        e.setAdminReply(rs.getString("admin_reply")); // 客服回复，可能为 null
        e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
        e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
        return e; // 返回映射结果
    } // mapRow 结束
} // SupportTicketRepository 结束
