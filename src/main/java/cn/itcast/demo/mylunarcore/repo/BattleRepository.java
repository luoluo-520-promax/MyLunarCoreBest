// 战斗相关数据库访问层所在包：battle 主表 CRUD
package cn.itcast.demo.mylunarcore.repo;

// Spring JDBC 模板，执行 INSERT/UPDATE
import org.springframework.jdbc.core.JdbcTemplate;
// 插入后获取数据库生成的自增主键
import org.springframework.jdbc.support.GeneratedKeyHolder;
// 主键持有者接口，统一 getKey() 访问
import org.springframework.jdbc.support.KeyHolder;
// 声明为 Spring 数据访问层 Bean
import org.springframework.stereotype.Repository;

// 预编译语句，绑定参数防注入
import java.sql.PreparedStatement;
// JDBC 时间戳，对应 battle 表 start_time/end_time
import java.sql.Timestamp;

/**
 * 战斗主表 {@code battle} 的 JDBC 访问：创建战局记录并生成自增战斗 ID，供 {@link cn.itcast.demo.mylunarcore.battle.BattleNettyService} 使用。
 */
@Repository // 注册为 Spring 仓储 Bean
public class BattleRepository {

    // Spring JDBC 模板（数据源由容器注入）
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 自动注入
     */
    public BattleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用供 insert/update 使用
    }

    /**
     * 插入新战斗记录并返回自增 id。
     *
     * @param playerId       参战玩家 id
     * @param lineupId       使用的编队 id
     * @param battleStageId  关卡/阶段 id
     * @param startTime      战斗开始时间
     * @return 新生成的 battle.id
     */
    public long insertBattle(int playerId, int lineupId, int battleStageId, Timestamp startTime) {
        // end_status 初始 0 表示进行中；statistics 结束时再写入 JSON
        String sql = "INSERT INTO battle(player_id, lineup_id, battle_stage_id, start_time, end_status, statistics) " +
                "VALUES(?, ?, ?, ?, ?, ?)";

        KeyHolder keyHolder = new GeneratedKeyHolder(); // 用于接收自增 id
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"}); // 声明需要返回 id 列
            ps.setInt(1, playerId);           // 玩家 id
            ps.setInt(2, lineupId);           // 编队 id
            ps.setInt(3, battleStageId);      // 关卡 id
            ps.setTimestamp(4, startTime);    // 开始时间
            ps.setInt(5, 0);                  // 0 表示战斗进行中
            ps.setString(6, null);            // 统计 JSON 战斗结束时再写
            return ps; // 返回预编译语句
        }, keyHolder);
        Number key = keyHolder.getKey(); // 取数据库生成的 id
        if (key == null) {
            throw new IllegalStateException("Failed to retrieve generated battle.id"); // 未拿到主键则失败
        }
        return key.longValue(); // 转为 long 返回给业务层
    }

    /**
     * 战斗结束时更新结束时间、状态与统计 JSON。
     *
     * @param battleId        战斗 id
     * @param endStatus       结束状态（胜利/失败/逃跑等）
     * @param statisticsJson  战斗统计 JSON 字符串
     * @param endTime         结束时间
     */
    public void updateBattleResult(long battleId, int endStatus, String statisticsJson, Timestamp endTime) {
        String sql = "UPDATE battle SET end_time=?, end_status=?, statistics=? WHERE id=?";
        jdbcTemplate.update(sql, endTime, endStatus, statisticsJson, battleId); // 按主键更新单行
    }
}
