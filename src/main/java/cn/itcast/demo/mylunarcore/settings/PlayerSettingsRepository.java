package cn.itcast.demo.mylunarcore.settings; // 玩家设置 JDBC 仓储所在包

import com.fasterxml.jackson.core.JsonProcessingException; // JSON 读写失败时捕获
import com.fasterxml.jackson.core.type.TypeReference; // 泛型列表反序列化需要 TypeReference
import com.fasterxml.jackson.databind.ObjectMapper; // Jackson 核心编解码器
import org.springframework.jdbc.core.JdbcTemplate; // Spring JDBC 模板
import org.springframework.stereotype.Repository; // 标注为持久化组件

import java.util.List; // 查询结果列表
import java.util.Optional; // 表示「有设置行 / 尚无设置行」

/**
 * 访问表 player_settings：按玩家 uid 读取四分区 JSON，或 UPSERT 写入。
 */
@Repository // 注册为 Spring Repository，供应用服务注入
public class PlayerSettingsRepository {

    /**
     * 按键列表反序列化类型令牌，避免 List 擦除后无法还原 KeyBinding
     */
    private static final TypeReference<List<PlayerSettings.KeyBinding>> KEYBINDS_TYPE =
            new TypeReference<>() {
            }; // 匿名 TypeReference 保留泛型信息

    /**
     * 执行 SQL 的 JDBC 模板，连接池由 Spring 注入
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * 复用线程安全的 ObjectMapper，避免每次查询新建
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造注入 JdbcTemplate
     */
    public PlayerSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存数据源访问入口
    } // 构造结束

    /**
     * 按玩家 uid 查询设置行；无行返回 empty，有行则反序列化并 sanitize 后返回
     */
    public Optional<PlayerSettings> findByPlayerId(int playerId) {
        String sql = "SELECT display_json, sound_json, keybinds_json, gameplay_json "
                + "FROM player_settings WHERE player_id = ? LIMIT 1"; // 只取该玩家一行四列 JSON
        List<PlayerSettings> list = jdbcTemplate.query(sql, (rs, rowNum) -> { // 行映射为内存对象
            PlayerSettings settings = new PlayerSettings(); // 组装聚合
            settings.setDisplay(read(rs.getString("display_json"), PlayerSettings.DisplaySettings.class,
                    PlayerSettingsDefaults.display())); // 画面 JSON → 对象，失败用默认
            settings.setSound(read(rs.getString("sound_json"), PlayerSettings.SoundSettings.class,
                    PlayerSettingsDefaults.sound())); // 声音 JSON → 对象
            settings.setKeybinds(readList(rs.getString("keybinds_json"))); // 键位 JSON 数组 → List
            settings.setGameplay(read(rs.getString("gameplay_json"), PlayerSettings.GameplaySettings.class,
                    PlayerSettingsDefaults.gameplay())); // 细节 JSON → 对象
            return PlayerSettingsDefaults.sanitize(settings); // 读出后再夹紧，修复历史脏数据
        }, playerId); // 绑定 player_id 参数
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0)); // 无行=未建档，有行取第一条
    } // findByPlayerId 结束

    /**
     * 插入或更新：以 player_id 为主键，存在则覆盖四列 JSON 并刷新 updated_at
     */
    public void upsert(int playerId, PlayerSettings settings) {
        PlayerSettings sanitized = PlayerSettingsDefaults.sanitize(settings); // 落库前强制合法化
        String sql = "INSERT INTO player_settings(player_id, display_json, sound_json, keybinds_json, gameplay_json) "
                + "VALUES(?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON)) "
                + "ON DUPLICATE KEY UPDATE "
                + "display_json = VALUES(display_json), "
                + "sound_json = VALUES(sound_json), "
                + "keybinds_json = VALUES(keybinds_json), "
                + "gameplay_json = VALUES(gameplay_json), "
                + "updated_at = NOW()"; // MySQL UPSERT：冲突则更新四列与时间戳
        jdbcTemplate.update(sql,
                playerId, // 主键：玩家 uid
                toJson(sanitized.getDisplay()), // 画面序列化为 JSON 文本
                toJson(sanitized.getSound()), // 声音 JSON
                toJson(sanitized.getKeybinds()), // 键位数组 JSON
                toJson(sanitized.getGameplay())); // 细节 JSON
    } // upsert 结束

    /**
     * 将单列 JSON 反序列化为指定类型；空串或解析失败时返回 fallback，避免脏行拖垮登录
     */
    private <T> T read(String json, Class<T> type, T fallback) {
        if (json == null || json.isBlank()) { // 列为空或 NULL
            return fallback; // 用出厂默认分区顶替
        } // 空串判断结束
        try { // 捕获 Jackson 解析异常
            T value = objectMapper.readValue(json, type); // 按类型反序列化
            return value == null ? fallback : value; // 解析结果为 null 也回退
        } catch (JsonProcessingException e) { // JSON 损坏或类型不匹配
            return fallback; // 不向外抛，保证读路径健壮
        } // 异常处理结束
    } // read 结束

    /**
     * 将 keybinds_json 反序列化为按键列表；空或失败时返回默认键位表
     */
    private List<PlayerSettings.KeyBinding> readList(String json) {
        if (json == null || json.isBlank()) { // 无键位列内容
            return PlayerSettingsDefaults.keybinds(); // 回退默认 WASD 等
        } // 空串判断结束
        try { // 捕获解析异常
            List<PlayerSettings.KeyBinding> list = objectMapper.readValue(json, KEYBINDS_TYPE); // 按泛型列表解析
            return list == null || list.isEmpty() ? PlayerSettingsDefaults.keybinds() : list; // 空数组同样回退
        } catch (JsonProcessingException e) { // 非法 JSON
            return PlayerSettingsDefaults.keybinds(); // 回退默认键位，保证可操作
        } // 异常处理结束
    } // readList 结束

    /**
     * 将对象序列化为 JSON 字符串；失败抛 IllegalStateException，因落库前数据应已合法
     */
    private String toJson(Object value) {
        try { // 捕获序列化异常
            return objectMapper.writeValueAsString(value); // 转成 JSON 文本交给 CAST(? AS JSON)
        } catch (JsonProcessingException e) { // 理论上 sanitize 后不应失败
            throw new IllegalStateException("serialize player settings failed", e); // 转为非受检异常中断事务
        } // 异常处理结束
    } // toJson 结束
} // PlayerSettingsRepository 结束
