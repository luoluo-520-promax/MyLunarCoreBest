package cn.itcast.demo.mylunarcore.profile;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家名片服务：头像框、称号、展示阵容与个性签名的读取/装备/持久化。
 * <p>
 * 热路径优先读内存 {@link #mem}；写操作先改内存再 upsert 表 {@code player_card}。
 * 头像框拥有关系存 {@code player_avatar_frame}，默认框 frameId=0 人人可用。
 */
@Service
public class PlayerCardService {

    /** 头像框静态定义：frameId、展示名、稀有度标签（N/R/SR/SSR）。 */
    public record FrameDef(int frameId, String name, String rarity) {}

    /**
     * 名片快照。
     *
     * @param playerId           玩家 ID
     * @param nickname           昵称（可与账号昵称同步）
     * @param level              展示用等级
     * @param frameId            当前装备的头像框 ID，0 为默认
     * @param title              称号文案
     * @param showcaseAvatarIds  展示位角色 ID 列表（最多 4 个）
     * @param signature          个性签名
     */
    public record PlayerCard(int playerId, String nickname, int level, int frameId, String title,
                             List<Integer> showcaseAvatarIds, String signature) {}

    /**
     * 写操作结果。
     *
     * @param success 是否成功
     * @param retcode 业务码：0=成功，2=未拥有该头像框
     * @param card    操作后的名片（失败时也可能带回当前名片）
     */
    public record OpResult(boolean success, int retcode, PlayerCard card) {}

    // 代码内置头像框目录；0 为默认框，其余需 grantFrame 或表中有记录才可装备
    private static final List<FrameDef> FRAMES = List.of(
            new FrameDef(0, "默认", "N"),
            new FrameDef(1001, "星穹列车", "R"),
            new FrameDef(1002, "巡猎之眼", "SR"),
            new FrameDef(2001, "世界BOSS征服者", "SSR")
    );

    // 热库 JDBC：player_card / player_avatar_frame
    private final JdbcTemplate jdbc;
    // playerId → 当前名片缓存，避免每次打开名片都打库
    private final ConcurrentHashMap<Integer, PlayerCard> mem = new ConcurrentHashMap<>();
    // playerId → 已拥有头像框 ID 集合（含默认 0）
    private final ConcurrentHashMap<Integer, java.util.Set<Integer>> ownedFrames = new ConcurrentHashMap<>();

    public PlayerCardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取缓存名片；未命中则从 {@code player_card} 加载，再写入 mem。
     * 库无行时用传入的 nickname/level 构造默认名片（frameId=0、空称号/签名）。
     */
    public PlayerCard getOrDefault(int playerId, String nickname, int level) {
        PlayerCard existing = mem.get(playerId);
        if (existing != null) {
            return existing; // 缓存命中直接返回
        }
        PlayerCard loaded = load(playerId, nickname, level); // 库表或默认值
        mem.put(playerId, loaded);
        return loaded;
    }

    /**
     * 装备头像框：非 0 框必须已拥有；未在内存集合中则先 {@link #loadOwned} 再校验。
     * retcode=2 表示未拥有。
     */
    public OpResult equipFrame(int playerId, int frameId) {
        // 内存未登记且非默认框时，可能是进程重启后未加载拥有列表
        if (!ownedFrames.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).contains(frameId)
                && frameId != 0) {
            loadOwned(playerId); // 从 player_avatar_frame 补齐
            if (frameId != 0 && !ownedFrames.get(playerId).contains(frameId)) {
                return new OpResult(false, 2, mem.get(playerId)); // 仍未拥有
            }
        }
        PlayerCard cur = getOrDefault(playerId, "Trailblazer", 1);
        // 仅替换 frameId，其它展示字段保持不变
        PlayerCard next = new PlayerCard(playerId, cur.nickname(), cur.level(), frameId, cur.title(),
                cur.showcaseAvatarIds(), cur.signature());
        mem.put(playerId, next);
        persist(next); // upsert player_card
        return new OpResult(true, 0, next);
    }

    /**
     * 更新展示阵容、称号与签名。
     * 阵容最多 4 个正 ID；称号最长 24 字、签名最长 60 字（超出截断）。
     */
    public OpResult updateShowcase(int playerId, List<Integer> avatarIds, String title, String signature) {
        PlayerCard cur = getOrDefault(playerId, "Trailblazer", 1);
        // null 当空列表；过滤非法 ID 并限制 4 个展示位
        List<Integer> show = avatarIds == null ? List.of() : avatarIds.stream()
                .filter(id -> id != null && id > 0).limit(4).toList();
        // title 为 null 表示不改；否则 trim 后截断到 24
        String t = title == null ? cur.title() : title.trim();
        if (t.length() > 24) {
            t = t.substring(0, 24);
        }
        // signature 同理，上限 60
        String sig = signature == null ? cur.signature() : signature.trim();
        if (sig.length() > 60) {
            sig = sig.substring(0, 60);
        }
        PlayerCard next = new PlayerCard(playerId, cur.nickname(), cur.level(), cur.frameId(), t, show, sig);
        mem.put(playerId, next);
        persist(next);
        return new OpResult(true, 0, next);
    }

    /**
     * 发放头像框到玩家拥有列表，并尝试插入 {@code player_avatar_frame}。
     * 插入冲突等异常被吞掉（幂等发放场景）。
     */
    public boolean grantFrame(int playerId, int frameId) {
        if (playerId <= 0 || frameId <= 0) {
            return false; // 不允许发放默认框或非法 ID
        }
        ownedFrames.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet()).add(frameId);
        try {
            jdbc.update("""
                    INSERT INTO player_avatar_frame (player_id, frame_id, granted_at) VALUES (?, ?, ?)
                    """, playerId, frameId, Instant.now().toString());
        } catch (Exception ignored) {
            // 唯一键冲突或表缺失时忽略，内存侧已拥有即可装备
        }
        return true;
    }

    /**
     * 列出玩家可见头像框：默认框 + 已拥有的目录项。
     */
    public List<FrameDef> listFrames(int playerId) {
        loadOwned(playerId);
        java.util.Set<Integer> owned = ownedFrames.getOrDefault(playerId, java.util.Set.of(0));
        List<FrameDef> out = new ArrayList<>();
        for (FrameDef f : FRAMES) {
            if (f.frameId() == 0 || owned.contains(f.frameId())) {
                out.add(f); // 默认框始终可见
            }
        }
        return out;
    }

    /** 将名片转为协议/JSON 友好的有序 Map（字段名与客户端约定一致）。 */
    public Map<String, Object> toMap(PlayerCard card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("playerId", card.playerId());
        m.put("nickname", card.nickname());
        m.put("level", card.level());
        m.put("frameId", card.frameId());
        m.put("title", card.title());
        m.put("showcaseAvatarIds", card.showcaseAvatarIds());
        m.put("signature", card.signature());
        return m;
    }

    /**
     * 从 {@code player_card} 读一行；失败或无行时用参数构造默认名片。
     * showcase_json 用简易解析（非严格 JSON），兼容 {@code [1, 2, 3]} 形式。
     */
    private PlayerCard load(int playerId, String nickname, int level) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT frame_id, title, showcase_json, signature, nickname, level FROM player_card WHERE player_id=?",
                    playerId);
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                List<Integer> show = parseShow(String.valueOf(r.get("showcase_json")));
                // 库中昵称/等级优先，空则回退入参
                String nick = r.get("nickname") != null ? String.valueOf(r.get("nickname")) : nickname;
                int lv = r.get("level") != null ? ((Number) r.get("level")).intValue() : level;
                return new PlayerCard(playerId, nick, lv,
                        ((Number) r.get("frame_id")).intValue(),
                        String.valueOf(r.getOrDefault("title", "")),
                        show,
                        String.valueOf(r.getOrDefault("signature", "")));
            }
        } catch (Exception ignored) {
            // 表不存在等：走下方默认名片
        }
        return new PlayerCard(playerId, nickname == null ? "Trailblazer" : nickname,
                Math.max(1, level), 0, "", List.of(), "");
    }

    /**
     * 先 UPDATE；影响 0 行再 INSERT，实现简易 upsert。
     * showcase 存 List.toString() 形态，与 {@link #parseShow} 对称。
     */
    private void persist(PlayerCard card) {
        try {
            String showJson = card.showcaseAvatarIds().toString();
            int u = jdbc.update("""
                    UPDATE player_card SET frame_id=?, title=?, showcase_json=?, signature=?,
                    nickname=?, level=?, updated_at=? WHERE player_id=?
                    """, card.frameId(), card.title(), showJson, card.signature(),
                    card.nickname(), card.level(), Instant.now().toString(), card.playerId());
            if (u == 0) {
                jdbc.update("""
                        INSERT INTO player_card
                        (player_id, frame_id, title, showcase_json, signature, nickname, level, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, card.playerId(), card.frameId(), card.title(), showJson, card.signature(),
                        card.nickname(), card.level(), Instant.now().toString());
            }
        } catch (Exception ignored) {
            // 持久化失败不回滚内存，下次再试；避免写库抖动阻断改名片
        }
    }

    /** 将 DB 中的 frame_id 并入内存集合，并始终确保拥有默认框 0。 */
    private void loadOwned(int playerId) {
        java.util.Set<Integer> set = ownedFrames.computeIfAbsent(playerId, id -> ConcurrentHashMap.newKeySet());
        set.add(0); // 默认框
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT frame_id FROM player_avatar_frame WHERE player_id=?", playerId);
            for (Map<String, Object> row : rows) {
                set.add(((Number) row.get("frame_id")).intValue());
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 解析展示阵容字符串：去掉方括号后按逗号拆整数；非法片段跳过。
     */
    private static List<Integer> parseShow(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        String s = json.replace("[", "").replace("]", "").trim();
        if (s.isEmpty()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }
}
