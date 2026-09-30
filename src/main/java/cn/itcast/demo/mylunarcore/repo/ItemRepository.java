// 背包道具数据仓储所在包：game_item 表 CRUD
package cn.itcast.demo.mylunarcore.repo;

// 背包道具领域实体
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 行映射函数式接口
import org.springframework.jdbc.core.RowMapper;
// 插入后获取自增主键
import org.springframework.jdbc.support.GeneratedKeyHolder;
// 仓储注解
import org.springframework.stereotype.Repository;

// 预编译语句
import java.sql.PreparedStatement;
// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;
// 列表接口
import java.util.List;

/**
 * 背包物品表 {@code game_item} 访问：查询、统计与增删改，仅返回 {@link GameItemEntity}。
 */
@Repository // 注册为数据访问 Bean
public class ItemRepository {

    private final JdbcTemplate jdbcTemplate; // 执行 SQL 的 JDBC 模板
    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public ItemRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 统计玩家背包中未丢弃的道具数量（可按类型过滤）。
     *
     * @param playerId   玩家 id
     * @param typeFilter 道具类型；≤0 表示不过滤类型
     * @return 符合条件的道具行数
     */
    public long countBagItems(int playerId, int typeFilter) {
        String where = " WHERE player_id=? AND discarded=0 "; // 基础条件：所属玩家且未丢弃
        if (typeFilter > 0) {
            where += " AND type=? "; // 追加类型过滤
        }
        String sql = "SELECT COUNT(*) FROM game_item " + where;
        if (typeFilter > 0) {
            return jdbcTemplate.queryForObject(sql, Long.class, playerId, typeFilter); // 两个绑定参数
        }
        return jdbcTemplate.queryForObject(sql, Long.class, playerId); // 仅 playerId
    }

    /**
     * 分页查询背包列表。
     *
     * @param playerId   玩家 id
     * @param typeFilter 类型过滤；≤0 忽略
     * @param page       页码（从 1 起）
     * @param pageSize   每页条数
     * @return 道具实体列表
     */
    public List<GameItemEntity> listBagItems(int playerId, int typeFilter, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);

        String sql = "SELECT id, player_id, item_id, type, count, level, exp, promotion, rank, locked, " +
                "main_affix_id, sub_affixes, equip_avatar_id " +
                "FROM game_item " +
                "WHERE player_id=? AND discarded=0 " +
                (typeFilter > 0 ? "AND type=? " : "") +
                "ORDER BY id DESC LIMIT ? OFFSET ?";

        Object[] args;
        if (typeFilter > 0) {
            args = new Object[]{playerId, typeFilter, pageSize, offset};
        } else {
            args = new Object[]{playerId, pageSize, offset};
        }

        return jdbcTemplate.query(sql, this::mapItem, args);
    }

    /**
     * 将查询结果集的一行映射为 {@link GameItemEntity}（不含 discarded 字段，固定 false）。
     *
     * @param rs     结果集
     * @param rowNum 行号
     * @return 道具实体
     * @throws SQLException 读列失败
     */
    private GameItemEntity mapItem(ResultSet rs, int rowNum) throws SQLException {
        GameItemEntity it = new GameItemEntity(); // 新建实体
        it.setId(rs.getLong("id")); // 道具实例 uid（主键）
        it.setPlayerId(rs.getInt("player_id")); // 所属玩家
        it.setItemId(rs.getInt("item_id")); // 道具模板 id
        it.setType(rs.getInt("type")); // 道具类型（武器/材料等）
        it.setCount(rs.getLong("count")); // 堆叠数量
        it.setLevel(rs.getInt("level")); // 强化等级
        it.setExp(rs.getLong("exp")); // 强化经验
        it.setPromotion(rs.getInt("promotion")); // 突破阶数
        it.setRank(rs.getInt("rank")); // 叠影/阶
        it.setLocked(rs.getInt("locked") != 0); // TINYINT 非 0 表示锁定
        it.setMainAffixId((Integer) rs.getObject("main_affix_id")); // 主词条 id（可 NULL）
        it.setSubAffixesJson(rs.getString("sub_affixes")); // 副词条 JSON 原文
        it.setEquipAvatarId((Integer) rs.getObject("equip_avatar_id")); // 当前装备的角色 id（可 NULL）
        it.setDiscarded(false); // 列表查询已过滤 discarded=0，实体侧固定 false
        return it; // 返回映射结果
    }

    /**
     * 按玩家与道具实例 uid 查询单行（含 discarded 状态）。
     *
     * @param playerId 玩家 id
     * @param uid      道具实例 id
     * @return 实体；不存在 null
     */
    public GameItemEntity findItemByUid(int playerId, long uid) {
        String sql = "SELECT id, player_id, item_id, type, count, level, exp, promotion, rank, locked, " +
                "main_affix_id, sub_affixes, equip_avatar_id, discarded " +
                "FROM game_item WHERE player_id=? AND id=? LIMIT 1";
        List<GameItemEntity> list = jdbcTemplate.query(sql, this::mapItemWithDiscarded, playerId, uid);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 映射含 discarded 字段的完整行（单条查询用）。
     *
     * @param rs     结果集
     * @param rowNum 行号
     * @return 含 discarded 的实体
     * @throws SQLException 读列失败
     */
    private GameItemEntity mapItemWithDiscarded(ResultSet rs, int rowNum) throws SQLException {
        GameItemEntity it = new GameItemEntity(); // 新建实体
        it.setId(rs.getLong("id"));
        it.setPlayerId(rs.getInt("player_id"));
        it.setItemId(rs.getInt("item_id"));
        it.setType(rs.getInt("type"));
        it.setCount(rs.getLong("count"));
        it.setLevel(rs.getInt("level"));
        it.setExp(rs.getLong("exp"));
        it.setPromotion(rs.getInt("promotion"));
        it.setRank(rs.getInt("rank"));
        it.setLocked(rs.getInt("locked") != 0);
        it.setMainAffixId((Integer) rs.getObject("main_affix_id"));
        it.setSubAffixesJson(rs.getString("sub_affixes"));
        it.setEquipAvatarId((Integer) rs.getObject("equip_avatar_id"));
        it.setDiscarded(rs.getInt("discarded") != 0); // 读取真实 discarded 状态
        return it;
    }

    /**
     * 更新数量或标记丢弃；锁定道具不可改（WHERE locked=0）。
     *
     * @param playerId 玩家 id
     * @param uid      道具 uid
     * @param newCount 新数量（discard 时忽略）
     * @param discard  true 则逻辑删除
     * @return 受影响行数（0 表示锁定或不存在）
     */
    public int updateItemCountAndDiscard(int playerId, long uid, long newCount, boolean discard) {
        if (discard) {
            // 丢弃：清零数量、标记 discarded、卸下装备
            String sql = "UPDATE game_item SET count=0, discarded=1, equip_avatar_id=NULL, updated_at=NOW() WHERE player_id=? AND id=? AND locked=0";
            return jdbcTemplate.update(sql, playerId, uid);
        }
        // 更新数量并确保 discarded=0
        String sql = "UPDATE game_item SET count=?, discarded=0, updated_at=NOW() WHERE player_id=? AND id=? AND locked=0";
        return jdbcTemplate.update(sql, newCount, playerId, uid);
    }

    /**
     * 设置锁定状态。
     *
     * @param playerId 玩家 id
     * @param uid      道具 uid
     * @param locked   是否锁定
     * @return 受影响行数
     */
    public int setLocked(int playerId, long uid, boolean locked) {
        String sql = "UPDATE game_item SET locked=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, locked ? 1 : 0, playerId, uid); // TINYINT 1/0
    }

    /**
     * 装备到角色或卸下（avatarId 为 null 表示卸下）。
     *
     * @param playerId         玩家 id
     * @param uid              道具 uid
     * @param avatarIdNullable 角色 id 或 null
     * @return 受影响行数
     */
    public int setEquipAvatarId(int playerId, long uid, Integer avatarIdNullable) {
        String sql = "UPDATE game_item SET equip_avatar_id=? , updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        if (avatarIdNullable == null) {
            return jdbcTemplate.update(sql, null, playerId, uid); // JDBC setNull
        }
        return jdbcTemplate.update(sql, avatarIdNullable, playerId, uid);
    }

    /**
     * 强化：更新等级与经验。
     *
     * @param playerId  玩家 id
     * @param targetUid 目标道具 uid
     * @param newLevel  新等级
     * @param newExp    新经验
     * @return 受影响行数
     */
    public int applyEnhance(int playerId, long targetUid, int newLevel, long newExp) {
        String sql = "UPDATE game_item SET level=?, exp=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, newLevel, newExp, playerId, targetUid);
    }

    /**
     * 强化后回写副词条 JSON（升级触发的副词条成长/追加）。
     */
    public int updateSubAffixes(int playerId, long uid, String subAffixesJson) {
        String sql = "UPDATE game_item SET sub_affixes=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, subAffixesJson, playerId, uid);
    }

    /**
     * 写入主词条 + 副词条（新掉落遗器/光锥初始化）。
     */
    public int updateAffixes(int playerId, long uid, Integer mainAffixId, String subAffixesJson) {
        String sql = "UPDATE game_item SET main_affix_id=?, sub_affixes=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, mainAffixId, subAffixesJson, playerId, uid);
    }

    /**
     * 查询已装备在指定角色上的光锥/遗器。
     */
    public List<GameItemEntity> listEquippedOnAvatar(int playerId, int avatarId) {
        String sql = "SELECT id, player_id, item_id, type, count, level, exp, promotion, rank, locked, " +
                "main_affix_id, sub_affixes, equip_avatar_id " +
                "FROM game_item WHERE player_id=? AND discarded=0 AND equip_avatar_id=?";
        return jdbcTemplate.query(sql, this::mapItem, playerId, avatarId);
    }

    /**
     * 逻辑删除：标记 discarded 并清零数量、卸下装备。
     *
     * @param playerId 玩家 id
     * @param uid      道具 uid
     * @return 受影响行数
     */
    public int markDiscarded(int playerId, long uid) {
        String sql = "UPDATE game_item SET discarded=1, count=0, equip_avatar_id=NULL, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, playerId, uid);
    }

    /**
     * 晋升（突破）：更新 promotion 阶数。
     *
     * @param playerId     玩家 id
     * @param uid          道具 uid
     * @param newPromotion 新突破阶
     * @return 受影响行数
     */
    public int updatePromotion(int playerId, long uid, int newPromotion) {
        String sql = "UPDATE game_item SET promotion=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, newPromotion, playerId, uid);
    }

    /**
     * 升阶/叠影：更新 rank。
     *
     * @param playerId 玩家 id
     * @param baseUid  基底道具 uid
     * @param newRank  新阶数
     * @return 受影响行数
     */
    public int updateRank(int playerId, long baseUid, int newRank) {
        String sql = "UPDATE game_item SET rank=?, updated_at=NOW() WHERE player_id=? AND id=? LIMIT 1";
        return jdbcTemplate.update(sql, newRank, playerId, baseUid);
    }

    /**
     * 是否仍存在未丢弃的同名模板道具（用于唯一性校验等）。
     *
     * @param playerId 玩家 id
     * @param itemId   道具模板 id
     * @return true 表示至少有一行 active
     */
    public boolean existsActiveItemByItemId(int playerId, int itemId) {
        String sql = "SELECT COUNT(*) FROM game_item WHERE player_id=? AND item_id=? AND discarded=0";
        Long cnt = jdbcTemplate.queryForObject(sql, Long.class, playerId, itemId);
        return cnt != null && cnt > 0; // COUNT 非 null 且 >0
    }

    /**
     * 插入简单堆叠道具并返回自增主键 id。
     *
     * @param playerId 玩家 id
     * @param itemId   模板 id
     * @param type     类型
     * @param count    数量（至少 1）
     * @return 新行 id；失败时 0
     */
    public long addSimpleItem(int playerId, int itemId, int type, long count) {
        // 默认值：level=1，无词条，未锁定未丢弃
        String sql = "INSERT INTO game_item(player_id, item_id, type, count, level, exp, promotion, rank, locked, discarded, " +
                "main_affix_id, sub_affixes, equip_avatar_id, created_at, updated_at) " +
                "VALUES(?, ?, ?, ?, 1, 0, 0, 0, 0, 0, NULL, NULL, NULL, NOW(), NOW())";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder(); // 接收自增 id
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"}); // 声明返回 id
            ps.setInt(1, playerId);   // 玩家 id
            ps.setInt(2, itemId);     // 模板 id
            ps.setInt(3, type);       // 类型
            ps.setLong(4, Math.max(1L, count)); // 数量至少 1
            return ps;
        }, keyHolder);
        Number id = keyHolder.getKey(); // 取生成的主键
        return id == null ? 0L : id.longValue(); // 失败返回 0
    }

    /**
     * 按模板 itemId 汇总未丢弃数量。
     */
    public long sumCountByItemId(int playerId, int itemId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(count),0) FROM game_item WHERE player_id=? AND item_id=? AND discarded=0",
                Long.class, playerId, itemId);
        return sum == null ? 0L : sum;
    }

    /**
     * 强制按模板 itemId 扣除数量（可跨多堆叠行），运维负向发放用。
     */
    public SubtractResult forceSubtractByItemId(int playerId, int itemId, long need) {
        if (need <= 0) {
            return new SubtractResult(0, sumCountByItemId(playerId, itemId), 0, List.of());
        }
        String sql = "SELECT id, player_id, item_id, type, count, level, exp, promotion, rank, locked, " +
                "main_affix_id, sub_affixes, equip_avatar_id, discarded " +
                "FROM game_item WHERE player_id=? AND item_id=? AND discarded=0 ORDER BY id ASC";
        List<GameItemEntity> rows = jdbcTemplate.query(sql, this::mapItemWithDiscarded, playerId, itemId);
        long remainNeed = need;
        long subtracted = 0;
        int affected = 0;
        List<GameItemEntity> changed = new java.util.ArrayList<>();
        for (GameItemEntity row : rows) {
            if (remainNeed <= 0) {
                break;
            }
            long have = row.getCount();
            if (have <= 0) {
                continue;
            }
            long take = Math.min(have, remainNeed);
            long next = have - take;
            boolean discard = next <= 0;
            // 运维强制扣除：忽略 locked 限制
            if (discard) {
                jdbcTemplate.update(
                        "UPDATE game_item SET count=0, discarded=1, equip_avatar_id=NULL, updated_at=NOW() WHERE player_id=? AND id=?",
                        playerId, row.getId());
            } else {
                jdbcTemplate.update(
                        "UPDATE game_item SET count=?, discarded=0, updated_at=NOW() WHERE player_id=? AND id=?",
                        next, playerId, row.getId());
            }
            row.setCount(Math.max(0, next));
            row.setDiscarded(discard);
            changed.add(row);
            subtracted += take;
            remainNeed -= take;
            affected++;
        }
        return new SubtractResult(subtracted, sumCountByItemId(playerId, itemId), affected, changed);
    }

    public record SubtractResult(long subtracted, long remain, int affectedRows, List<GameItemEntity> affectedItems) {}
}
