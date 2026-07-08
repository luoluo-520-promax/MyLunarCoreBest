// 玩家数据仓储所在包：负责 account/player 及关联子表的 JDBC 访问
package cn.itcast.demo.mylunarcore.repo;

// 玩家领域实体集合（账号、聚合根、子表实体等）
import cn.itcast.demo.mylunarcore.model.*;
// 统一日志门面，按分类输出
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举（业务数据类）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring JDBC 模板，简化 SQL 执行
import org.springframework.jdbc.core.JdbcTemplate;
// ResultSet 行映射函数式接口
import org.springframework.jdbc.core.RowMapper;
// Spring 仓储注解，注册为数据访问 Bean
import org.springframework.stereotype.Repository;

// JDBC ResultSet，读取查询结果列
import java.sql.ResultSet;
// JDBC SQLException，映射列时可能抛出
import java.sql.SQLException;
// JDBC 时间戳类型，对应 DATETIME/TIMESTAMP 列
import java.sql.Timestamp;
// 不可变空集合，初始化 PlayerData 中的空列表
import java.util.Collections;
// 列表类型，承载 query 返回的多行结果
import java.util.List;
// 插入后获取自增主键（如 player.uid）
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * L2：玩家基础数据与关联表（avatar、lineup、item 等）通过 MySQL 持久化，毫秒级磁盘/网络访问。
 * <p>
 * 提供登录、创角、登出时间更新及 {@link PlayerData} 全量装载，供会话与业务层使用。
 */
@Repository // 声明为 Spring 仓储 Bean，由容器管理生命周期
public class PlayerDataRepository {

    // 业务数据分类日志器，便于排查账号/玩家相关 SQL 问题
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, PlayerDataRepository.class);

    // JDBC 模板（构造注入），封装数据源与 SQL 执行
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 自动注入的数据源访问模板
     */
    public PlayerDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用供各方法执行 SQL
    }

    // account 表 → AccountEntity 的行映射器（复用于多处账号查询）
    private final RowMapper<AccountEntity> accountMapper = (rs, rowNum) -> {
        AccountEntity a = new AccountEntity(); // 新建账号实体
        a.setId(rs.getString("id")); // 账号主键（VARCHAR，可能与 player.account_id 类型不一致）
        a.setUsername(rs.getString("username")); // 登录用户名
        a.setPassword(rs.getString("password")); // 密码哈希或明文（依业务而定）
        a.setEmail(rs.getString("email")); // 绑定邮箱
        a.setPhone(rs.getString("phone")); // 绑定手机
        a.setStatus(rs.getInt("status")); // 账号状态（正常/封禁等）
        return a; // 返回映射完成的实体
    };

    // player 表 → PlayerEntity 的行映射器
    private final RowMapper<PlayerEntity> playerMapper = (rs, rowNum) -> {
        PlayerEntity p = new PlayerEntity(); // 新建玩家实体
        p.setUid(rs.getLong("uid")); // 玩家唯一 id（自增主键）
        p.setAccountId(rs.getLong("account_id")); // 关联账号 id（INT UNSIGNED）
        p.setNickname(rs.getString("nickname")); // 游戏内昵称
        p.setLevel(rs.getInt("level")); // 玩家等级
        p.setExp(rs.getLong("exp")); // 当前经验值
        p.setWorldLevel(rs.getInt("world_level")); // 世界等级
        p.setStamina(rs.getInt("stamina")); // 体力值
        p.setCurrencyJson(rs.getString("currency")); // 货币 JSON 快照
        p.setSceneId(rs.getInt("scene_id")); // 当前场景 id

        // 对应 FLOAT 列，用 BigDecimal 承接更稳，避免浮点精度丢失
        p.setPosX(rs.getBigDecimal("pos_x")); // 场景内 X 坐标
        p.setPosY(rs.getBigDecimal("pos_y")); // 场景内 Y 坐标
        p.setPosZ(rs.getBigDecimal("pos_z")); // 场景内 Z 坐标

        p.setLastLogin(rs.getTimestamp("last_login")); // 上次登录时间
        p.setLastLogout(rs.getTimestamp("last_logout")); // 上次登出时间
        return p; // 返回映射完成的实体
    };

    /**
     * 按用户名查询账号行。
     *
     * @param username 登录用户名
     * @return 账号实体；不存在返回 null
     */
    public AccountEntity findAccountByUsername(String username) {
        // 参数化查询，防止 SQL 注入；LIMIT 1 只取首行
        String sql = "SELECT id, username, password, email, phone, status FROM account WHERE username = ? LIMIT 1";
        List<AccountEntity> list = jdbcTemplate.query(sql, accountMapper, username); // 执行查询并映射
        return list.isEmpty() ? null : list.get(0); // 无行则 null，否则取第一条
    }

    /**
     * 与 {@code player.account_id}（无符号整型）对齐，用于从玩家 uid 反查账号。
     *
     * @param accountIdUnsigned 无符号账号 id 数值
     * @return 账号实体；不存在返回 null
     */
    public AccountEntity findAccountByUnsignedAccountId(long accountIdUnsigned) {
        // CAST(id AS UNSIGNED) 将 VARCHAR 主键转为数值再比较
        String sql = "SELECT id, username, password, email, phone, status FROM account WHERE CAST(id AS UNSIGNED) = ? LIMIT 1";
        List<AccountEntity> list = jdbcTemplate.query(sql, accountMapper, accountIdUnsigned);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 通过 username 直接联表拿到 player 核心字段。
     * 因为 account.id 是 VARCHAR，而 player.account_id 是 INT UNSIGNED，这里用 CAST 做对齐。
     *
     * @param username 登录用户名
     * @return 玩家实体；未创角则 null
     */
    public PlayerEntity loadPlayerByUsername(String username) {
        // account 与 player 通过 CAST(a.id AS UNSIGNED) = p.account_id 关联
        String sql =
                "SELECT p.uid, p.account_id, p.nickname, p.level, p.exp, p.world_level, p.stamina, p.currency, " +
                "p.scene_id, p.pos_x, p.pos_y, p.pos_z, p.last_login, p.last_logout " +
                "FROM account a " +
                "JOIN player p ON p.account_id = CAST(a.id AS UNSIGNED) " +
                "WHERE a.username = ? " +
                "LIMIT 1";
        List<PlayerEntity> list = jdbcTemplate.query(sql, playerMapper, username);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 为已存在账号创建默认玩家行（等级 1、原点坐标等），并读回完整实体。
     *
     * @param account 已登录或已注册的账号实体
     * @return 新创建的玩家实体；读回失败则 null
     */
    public PlayerEntity createDefaultPlayerForAccount(AccountEntity account) {
        Long accountIdUnsigned = toUnsignedLong(account.getId()); // 将 account.id 转为数值
        if (accountIdUnsigned == null) {
            // account.id 无法解析为无符号长整型时拒绝创角
            throw new IllegalArgumentException("account.id cannot be converted to unsigned long: " + account.getId());
        }

        // 若 currency 字段允许为 NULL，则这里插入 NULL 更贴近表默认值
        String insert =
                "INSERT INTO player(account_id, nickname, level, exp, world_level, stamina, currency, scene_id, " +
                "pos_x, pos_y, pos_z, rot_x, rot_y, rot_z, last_login, last_logout) " +
                "VALUES(?, NULL, 1, 0, 0, 0, NULL, 0, 0, 0, 0, 0, 0, 0, NULL, NULL)";

        // uid 是 auto_increment，所以用 KeyHolder 拿回新生成的 uid
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(insert, new String[]{"uid"}); // 声明返回 uid 列
            ps.setLong(1, accountIdUnsigned); // 绑定 account_id 参数
            return ps; // 返回预编译语句供框架执行
        }, keyHolder);

        Number uid = keyHolder.getKey(); // 从 KeyHolder 取自增 uid
        if (uid == null) {
            throw new IllegalStateException("Failed to retrieve generated uid for player"); // 未拿到主键则异常
        }

        Long createdUid = uid.longValue(); // 转为 long 供后续查询
        // 按 uid 再查一次，得到数据库中的完整行（含默认值列）
        String loadSql = "SELECT uid, account_id, nickname, level, exp, world_level, stamina, currency, " +
                "scene_id, pos_x, pos_y, pos_z, last_login, last_logout " +
                "FROM player WHERE uid = ? LIMIT 1";

        List<PlayerEntity> list = jdbcTemplate.query(loadSql, playerMapper, createdUid);
        return list.isEmpty() ? null : list.get(0); // 理论上必有一行
    }

    /**
     * 登录成功时更新账号与玩家的最近登录时间与 IP。
     *
     * @param account      当前账号
     * @param uid          玩家 uid
     * @param now          登录时间戳
     * @param lastLoginIp  客户端 IP
     */
    public void updateAccountLogin(AccountEntity account, long uid, Timestamp now, String lastLoginIp) {
        // account.id 是 VARCHAR，WHERE 条件用字符串主键
        String sqlAccount = "UPDATE account SET last_login_at = ?, last_login_ip = ? WHERE id = ?";
        jdbcTemplate.update(sqlAccount, now, lastLoginIp, account.getId()); // 更新账号表

        // player.last_login 记录玩家维度登录时间
        String sqlPlayer = "UPDATE player SET last_login = ? WHERE uid = ?";
        jdbcTemplate.update(sqlPlayer, now, uid); // 更新玩家表
    }

    /**
     * 登出时更新玩家最近登出时间。
     *
     * @param uid 玩家 uid
     * @param now 登出时间戳
     */
    public void updatePlayerLogout(long uid, Timestamp now) {
        String sql = "UPDATE player SET last_logout = ? WHERE uid = ?";
        jdbcTemplate.update(sql, now, uid); // 按 uid 更新单行
    }

    /**
     * 将会话内玩家核心快照定时回写到 player 主表。
     *
     * @param p 内存中的玩家实体快照
     * @return JDBC 受影响行数；参数无效时返回 0
     */
    public int persistPlayerSnapshot(PlayerEntity p) {
        if (p == null || p.getUid() <= 0) {
            return 0; // 无效实体不执行 UPDATE
        }
        // updated_at=NOW() 由数据库自动刷新修改时间
        String sql = "UPDATE player SET nickname=?, level=?, exp=?, world_level=?, stamina=?, currency=?, " +
                "scene_id=?, pos_x=?, pos_y=?, pos_z=?, updated_at=NOW() WHERE uid=?";
        return jdbcTemplate.update(sql,
                p.getNickname(),   // 昵称
                p.getLevel(),      // 等级
                p.getExp(),        // 经验
                p.getWorldLevel(), // 世界等级
                p.getStamina(),    // 体力
                p.getCurrencyJson(), // 货币 JSON
                p.getSceneId(),    // 场景
                p.getPosX(),       // X 坐标
                p.getPosY(),       // Y 坐标
                p.getPosZ(),       // Z 坐标
                p.getUid());       // WHERE 条件：玩家 uid
    }

    /**
     * 仅加载玩家核心数据（player 主表），子表集合初始化为空列表。
     *
     * @param playerId 玩家 uid
     * @return 含 player 与空集合的 PlayerData
     */
    public PlayerData loadCoreData(long playerId) {
        PlayerData data = new PlayerData(); // 新建聚合根
        data.setPlayer(loadPlayerByUid(playerId)); // 加载 player 行
        fillEmptyCollections(data); // 子表列表置为空，避免 NPE
        return data;
    }

    /**
     * 加载非核心子表数据（avatar、lineup、friend 等），不含 player 主表。
     *
     * @param playerId 玩家 uid
     * @return 子表数据已填充、player 为 null 的 PlayerData
     */
    public PlayerData loadNonCoreData(long playerId) {
        PlayerData data = new PlayerData(); // 新建聚合根
        fillEmptyCollections(data); // 先置空，再逐表加载覆盖
        int pid = (int) playerId; // 子表 player_id 列为 INT，强转
        data.setAvatars(loadAvatars(pid));       // 角色列表
        data.setLineups(loadLineups(pid));       // 编队列表
        data.setFriends(loadFriends(pid));       // 好友关系
        data.setChallenges(loadChallenges(pid)); // 挑战进度
        data.setRogues(loadRogues(pid));         // Rogue 进度
        data.setItems(loadItems(pid));           // 背包道具
        return data;
    }

    /**
     * 全量加载：核心 + 所有关联子表。
     *
     * @param playerId 玩家 uid
     * @return 完整 PlayerData；玩家不存在时仅含空集合
     */
    public PlayerData loadAllData(long playerId) {
        PlayerData data = loadCoreData(playerId); // 先加载核心
        if (data.getPlayer() == null) {
            return data; // 无玩家行则不再查子表
        }
        PlayerData nonCore = loadNonCoreData(playerId); // 加载子表
        data.setAvatars(nonCore.getAvatars());       // 合并角色
        data.setLineups(nonCore.getLineups());       // 合并编队
        data.setFriends(nonCore.getFriends());       // 合并好友
        data.setChallenges(nonCore.getChallenges()); // 合并挑战
        data.setRogues(nonCore.getRogues());         // 合并 Rogue
        data.setItems(nonCore.getItems());           // 合并道具
        return data;
    }

    /**
     * 将 PlayerData 中各子表列表初始化为不可变空列表。
     *
     * @param data 待初始化的聚合根
     */
    private void fillEmptyCollections(PlayerData data) {
        data.setAvatars(Collections.emptyList());    // 空角色列表
        data.setLineups(Collections.emptyList());  // 空编队列表
        data.setFriends(Collections.emptyList());  // 空好友列表
        data.setChallenges(Collections.emptyList()); // 空挑战列表
        data.setRogues(Collections.emptyList());   // 空 Rogue 列表
        data.setItems(Collections.emptyList());    // 空道具列表
    }

    /**
     * 按 uid 查询玩家主表单行。
     *
     * @param uid 玩家 uid
     * @return 玩家实体；不存在返回 null
     */
    public PlayerEntity loadPlayerByUid(long uid) {
        String sql =
                "SELECT uid, account_id, nickname, level, exp, world_level, stamina, currency, " +
                "scene_id, pos_x, pos_y, pos_z, last_login, last_logout " +
                "FROM player WHERE uid = ? LIMIT 1";
        List<PlayerEntity> list = jdbcTemplate.query(sql, playerMapper, uid);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 加载玩家拥有的全部角色（avatar 表）。
     *
     * @param playerId 玩家 id（INT）
     * @return 角色实体列表，无数据时为空列表
     */
    public List<AvatarEntity> loadAvatars(int playerId) {
        String sql = "SELECT * FROM avatar WHERE player_id = ?"; // 按玩家过滤
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            AvatarEntity a = new AvatarEntity(); // 新建角色实体
            a.setId(rs.getLong("id")); // 实例主键
            a.setPlayerId(rs.getInt("player_id")); // 所属玩家
            a.setAvatarId(rs.getInt("avatar_id")); // 角色模板 id
            a.setLevel(rs.getInt("level")); // 角色等级
            a.setExp(rs.getLong("exp")); // 角色经验
            a.setPromotion(rs.getInt("promotion")); // 突破阶数
            a.setRank(rs.getInt("rank")); // 星魂/叠影阶
            a.setLocked(rs.getInt("locked") != 0); // TINYINT 非 0 表示锁定
            a.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            a.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return a;
        }, playerId);
    }

    /**
     * 加载玩家全部编队（lineup 表）。
     *
     * @param playerId 玩家 id
     * @return 编队实体列表
     */
    public List<LineupEntity> loadLineups(int playerId) {
        String sql = "SELECT * FROM lineup WHERE player_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            LineupEntity l = new LineupEntity(); // 新建编队实体
            l.setId(rs.getInt("id")); // 编队主键
            l.setPlayerId(rs.getInt("player_id")); // 所属玩家
            l.setName(rs.getString("name")); // 编队名称
            l.setActive(rs.getInt("is_active") != 0); // 是否为当前激活编队
            l.setAvatarsJson(rs.getString("avatars")); // 成员 JSON（角色 id 列表等）
            l.setCreatedAt(rs.getTimestamp("created_at"));
            l.setUpdatedAt(rs.getTimestamp("updated_at"));
            return l;
        }, playerId);
    }

    /**
     * 加载与玩家相关的好友关系（双向：player_id_1 或 player_id_2）。
     *
     * @param playerId 玩家 id
     * @return 好友关系实体列表
     */
    public List<FriendEntity> loadFriends(int playerId) {
        // OR 条件覆盖玩家出现在任一侧的好友行
        String sql = "SELECT * FROM friend WHERE player_id_1 = ? OR player_id_2 = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            FriendEntity f = new FriendEntity(); // 新建好友关系实体
            f.setId(rs.getInt("id")); // 关系主键
            f.setPlayerId1(rs.getInt("player_id_1")); // 发起方或较小 id 侧
            f.setPlayerId2(rs.getInt("player_id_2")); // 接收方或较大 id 侧
            f.setStatus(rs.getInt("status")); // 关系状态（申请中/已确认等）
            f.setCreateTime(rs.getTimestamp("create_time")); // 建立时间
            f.setConfirmTime(rs.getTimestamp("confirm_time")); // 确认时间
            int src = rs.getInt("source"); // 来源字段可能为 NULL
            if (rs.wasNull()) {
                f.setSource(null); // NULL 映射为 Java null
            } else {
                f.setSource(src); // 有值则写入
            }
            f.setRemark(rs.getString("remark")); // 好友备注
            f.setUpdatedAt(rs.getTimestamp("updated_at"));
            return f;
        }, playerId, playerId); // 同一 playerId 绑定两次 OR 两侧
    }

    /**
     * 加载玩家挑战进度（challenge 表）。
     *
     * @param playerId 玩家 id
     * @return 挑战实体列表
     */
    public List<ChallengeEntity> loadChallenges(int playerId) {
        String sql = "SELECT * FROM challenge WHERE player_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChallengeEntity c = new ChallengeEntity(); // 新建挑战进度实体
            c.setId(rs.getLong("id")); // 行主键
            c.setPlayerId(rs.getInt("player_id")); // 所属玩家
            c.setChallengeType(rs.getInt("challenge_type")); // 挑战类型枚举值
            c.setChallengeId(rs.getInt("challenge_id")); // 具体关卡/挑战 id
            c.setProgress(rs.getLong("progress")); // 当前进度
            c.setMaxProgress(rs.getLong("max_progress")); // 目标进度
            c.setStatus(rs.getInt("status")); // 状态（进行中/已完成等）
            c.setStartTime(rs.getTimestamp("start_time")); // 开始时间
            c.setCompleteTime(rs.getTimestamp("complete_time")); // 完成时间
            c.setRewardClaimed(rs.getInt("reward_claimed") != 0); // 是否已领奖
            c.setExtraDataJson(rs.getString("extra_data")); // 扩展 JSON
            c.setCreatedAt(rs.getTimestamp("created_at"));
            c.setUpdatedAt(rs.getTimestamp("updated_at"));
            return c;
        }, playerId);
    }

    /**
     * 加载玩家 Rogue 模式进度（rogue 表）。
     *
     * @param playerId 玩家 id
     * @return Rogue 实体列表
     */
    public List<RogueEntity> loadRogues(int playerId) {
        String sql = "SELECT * FROM rogue WHERE player_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            RogueEntity r = new RogueEntity(); // 新建 Rogue 进度实体
            r.setId(rs.getInt("id")); // 行主键
            r.setPlayerId(rs.getInt("player_id")); // 所属玩家
            r.setRogueId(rs.getInt("rogue_id")); // Rogue 玩法/赛季 id
            r.setCurrentFloor(rs.getInt("current_floor")); // 当前层数
            r.setCurrentWave(rs.getInt("current_wave")); // 当前波次
            r.setDifficulty(rs.getInt("difficulty")); // 难度
            r.setStatus(rs.getInt("status")); // 进行状态
            r.setScore(rs.getLong("score")); // 当前分数
            r.setRewardsClaimedJson(rs.getString("rewards_claimed")); // 已领奖励 JSON
            r.setProgressDataJson(rs.getString("progress_data")); // 进度快照 JSON
            r.setStartTime(rs.getTimestamp("start_time")); // 开局时间
            r.setEndTime(rs.getTimestamp("end_time")); // 结束时间
            r.setCreatedAt(rs.getTimestamp("created_at"));
            r.setUpdatedAt(rs.getTimestamp("updated_at"));
            return r;
        }, playerId);
    }

    /**
     * 加载玩家背包道具（game_item 表，含已丢弃项）。
     *
     * @param playerId 玩家 id
     * @return 道具实体列表
     */
    public List<GameItemEntity> loadItems(int playerId) {
        String sql = "SELECT * FROM game_item WHERE player_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            GameItemEntity it = new GameItemEntity(); // 新建道具实体
            it.setId(rs.getLong("id")); // 道具实例 uid
            it.setPlayerId(rs.getInt("player_id")); // 所属玩家
            it.setItemId(rs.getInt("item_id")); // 道具模板 id
            it.setType(rs.getInt("type")); // 道具类型
            it.setCount(rs.getLong("count")); // 堆叠数量
            it.setLevel(rs.getInt("level")); // 强化等级
            it.setExp(rs.getLong("exp")); // 强化经验
            it.setPromotion(rs.getInt("promotion")); // 突破阶
            it.setRank(rs.getInt("rank")); // 叠影/阶
            it.setLocked(rs.getInt("locked") != 0); // 是否锁定
            it.setDiscarded(rs.getInt("discarded") != 0); // 是否已逻辑删除
            it.setMainAffixId((Integer) getNullableInt(rs, "main_affix_id")); // 主词条（可空）
            it.setSubAffixesJson(rs.getString("sub_affixes")); // 副词条 JSON
            it.setEquipAvatarId((Integer) getNullableInt(rs, "equip_avatar_id")); // 装备角色 id（可空）
            it.setCreatedAt(rs.getTimestamp("created_at"));
            it.setUpdatedAt(rs.getTimestamp("updated_at"));
            return it;
        }, playerId);
    }

    /**
     * 读取可空 INT 列：数据库 NULL 映射为 Java null。
     *
     * @param rs  当前结果集行
     * @param col 列名
     * @return Integer 或 null
     * @throws SQLException 读取列失败时抛出
     */
    private Integer getNullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col); // 先按 int 读（NULL 时值为 0）
        if (rs.wasNull()) {
            return null; // 列实际为 NULL
        }
        return v; // 非 NULL 返回包装类型
    }

    /**
     * 将 account.id 字符串解析为无符号长整型（0~4294967295）。
     *
     * @param s account.id 字符串
     * @return 解析成功返回 Long；空或非法返回 null
     */
    private Long toUnsignedLong(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null; // 空字符串无法转换
        }
        try {
            // account.id 可能是数字字符串（0~4294967295）
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse unsigned account.id: {}", s, e); // 记录解析失败
            return null;
        }
    }
}
