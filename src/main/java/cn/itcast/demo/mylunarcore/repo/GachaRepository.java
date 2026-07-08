// 抽卡数据仓储所在包：player_gacha_info / player_gacha_banner_info
package cn.itcast.demo.mylunarcore.repo;

// 玩家单 Banner 保底实体
import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
// 玩家全局抽卡信息实体
import cn.itcast.demo.mylunarcore.model.PlayerGachaInfoEntity;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 数据访问层组件
import org.springframework.stereotype.Repository;

// 列表接口，承载 query 结果
import java.util.List;

/**
 * 抽卡相关表 JDBC 访问：加载/创建玩家卡池信息与各 Banner 保底行。
 */
@Repository // 抽卡相关表 JDBC 访问 Bean
public class GachaRepository {

    // Spring JDBC 模板（构造函数注入）
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public GachaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用
    }

    /**
     * 读取或初始化玩家全局抽卡信息行（300 抽里程碑等）。
     *
     * @param playerId 玩家 id
     * @return 全局抽卡信息实体；异常时可能 null
     */
    public PlayerGachaInfoEntity loadOrCreateGachaInfo(int playerId) {
        ensurePlayerGachaInfoRow(playerId); // 保证 player_gacha_info 存在一行
        String sql = "SELECT id, player_id, ceiling_num, ceiling_claimed, created_at, updated_at " +
                "FROM player_gacha_info WHERE player_id=? LIMIT 1";
        List<PlayerGachaInfoEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            PlayerGachaInfoEntity e = new PlayerGachaInfoEntity(); // 新建实体
            e.setId(rs.getInt("id")); // 行主键
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id
            e.setCeilingNum(rs.getInt("ceiling_num")); // 常驻池累计抽数（300 抽里程碑）
            e.setCeilingClaimed(rs.getInt("ceiling_claimed") != 0); // 是否已兑换 300 抽保底
            e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return e;
        }, playerId);
        return list.isEmpty() ? null : list.get(0); // 取首行或 null
    }

    /**
     * 读取或初始化指定 Banner 类型的玩家保底计数行。
     *
     * @param playerId   玩家 id
     * @param bannerType Banner 类型（限定/常驻等）
     * @return Banner 保底实体；异常时可能 null
     */
    public PlayerGachaBannerInfoEntity loadOrCreateBannerInfo(int playerId, int bannerType) {
        ensurePlayerBannerRow(playerId, bannerType); // 保证该 Banner 行存在
        String sql = "SELECT id, player_id, banner_type, pity_5, pity_4, failed_up_count, created_at, updated_at " +
                "FROM player_gacha_banner_info WHERE player_id=? AND banner_type=? LIMIT 1";
        List<PlayerGachaBannerInfoEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            PlayerGachaBannerInfoEntity e = new PlayerGachaBannerInfoEntity(); // 新建实体
            e.setId(rs.getLong("id")); // 行主键
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id
            e.setBannerType(rs.getInt("banner_type")); // Banner 类型
            e.setPity5(rs.getInt("pity_5")); // 五星保底计数
            e.setPity4(rs.getInt("pity_4")); // 四星保底计数
            e.setFailedUpCount(rs.getInt("failed_up_count")); // UP 歪了次数（大保底相关）
            e.setCreatedAt(rs.getTimestamp("created_at"));
            e.setUpdatedAt(rs.getTimestamp("updated_at"));
            return e;
        }, playerId, bannerType); // 绑定 playerId 与 bannerType
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 确保 player_gacha_info 存在一行（幂等 INSERT）。
     *
     * @param playerId 玩家 id
     */
    private void ensurePlayerGachaInfoRow(int playerId) {
        // ON DUPLICATE KEY UPDATE player_id=player_id：已存在则不修改
        String sql = "INSERT INTO player_gacha_info(player_id, ceiling_num, ceiling_claimed) " +
                "VALUES(?, 0, 0) ON DUPLICATE KEY UPDATE player_id=player_id";
        jdbcTemplate.update(sql, playerId); // 执行 upsert
    }

    /**
     * 确保 player_gacha_banner_info 存在一行（幂等 INSERT）。
     *
     * @param playerId   玩家 id
     * @param bannerType Banner 类型
     */
    private void ensurePlayerBannerRow(int playerId, int bannerType) {
        // 新行 pity 与 failed_up_count 均从 0 开始
        String sql = "INSERT INTO player_gacha_banner_info(player_id, banner_type, pity_5, pity_4, failed_up_count) " +
                "VALUES(?, ?, 0, 0, 0) ON DUPLICATE KEY UPDATE player_id=player_id";
        jdbcTemplate.update(sql, playerId, bannerType);
    }

    /**
     * 更新 Banner 保底计数（五星/四星/UP 歪计数）。
     *
     * @param playerId      玩家 id
     * @param bannerType    Banner 类型
     * @param pity5         五星计数
     * @param pity4         四星计数
     * @param failedUpCount UP 失败计数
     * @return JDBC 受影响行数
     */
    public int updateBannerPity(int playerId, int bannerType, int pity5, int pity4, int failedUpCount) {
        String sql = "UPDATE player_gacha_banner_info SET pity_5=?, pity_4=?, failed_up_count=? WHERE player_id=? AND banner_type=? LIMIT 1";
        return jdbcTemplate.update(sql, pity5, pity4, failedUpCount, playerId, bannerType);
    }

    /**
     * 增量更新常驻池 300 抽里程碑计数。
     *
     * @param playerId 玩家 id
     * @param add      本次增加的抽数（负值会被钳制为 0）
     * @return JDBC 受影响行数
     */
    public int incrementCeilingNum(int playerId, int add) {
        String sql = "UPDATE player_gacha_info SET ceiling_num = ceiling_num + ? WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, Math.max(0, add), playerId); // Math.max 防止负数增量
    }

    /**
     * 标记玩家是否已兑换 300 抽保底。
     *
     * @param playerId 玩家 id
     * @param claimed  true 表示已领取
     * @return JDBC 受影响行数
     */
    public int setCeilingClaimed(int playerId, boolean claimed) {
        String sql = "UPDATE player_gacha_info SET ceiling_claimed=? WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, claimed ? 1 : 0, playerId); // TINYINT 1/0
    }
}
