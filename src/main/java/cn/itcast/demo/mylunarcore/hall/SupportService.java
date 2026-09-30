package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.repo.FriendRepository;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 好友助战：设置外借角色快照；开战前拉取好友支援；含日限/冷却/双方奖励水位。
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record SupportSnapshot(int ownerPlayerId, long avatarInstanceId, int avatarId,
                                  int level, int promotion, int rank, String snapshotJson) {}

    public record OpResult(boolean ok, int retcode) {}

    public record BorrowResult(SupportSnapshot snapshot, int retcode) {
        public static BorrowResult fail(int retcode) {
            return new BorrowResult(null, retcode);
        }
    }

    private final JdbcTemplate jdbc;
    private final FriendRepository friendRepository;
    private final PlayerDataRepository playerDataRepository;
    private final ObjectMapper objectMapper;
    private final int dailyBorrowLimit;
    private final long borrowCooldownMs;
    private final int borrowerRewardCurrencyId;
    private final int borrowerRewardAmount;
    private final int lenderRewardCurrencyId;
    private final int lenderRewardAmount;

    /** 进程内冷却兜底：key=requester:friend */
    private final Map<String, Long> lastBorrowAt = new ConcurrentHashMap<>();
    private final PeriodicResetService periodicResetService;

    public SupportService(JdbcTemplate jdbc,
                          FriendRepository friendRepository,
                          PlayerDataRepository playerDataRepository,
                          ObjectMapper objectMapper) {
        this(jdbc, friendRepository, playerDataRepository, objectMapper, null, 5, 300, 0, 0, 0, 0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SupportService(JdbcTemplate jdbc,
                          FriendRepository friendRepository,
                          PlayerDataRepository playerDataRepository,
                          ObjectMapper objectMapper,
                          ObjectProvider<PeriodicResetService> periodicResetProvider,
                          @Value("${lunarcore.support.daily-borrow-limit:5}") int dailyBorrowLimit,
                          @Value("${lunarcore.support.borrow-cooldown-seconds:300}") long borrowCooldownSeconds,
                          @Value("${lunarcore.support.borrower-reward-currency-id:0}") int borrowerRewardCurrencyId,
                          @Value("${lunarcore.support.borrower-reward-amount:0}") int borrowerRewardAmount,
                          @Value("${lunarcore.support.lender-reward-currency-id:0}") int lenderRewardCurrencyId,
                          @Value("${lunarcore.support.lender-reward-amount:0}") int lenderRewardAmount) {
        this.jdbc = jdbc;
        this.friendRepository = friendRepository;
        this.playerDataRepository = playerDataRepository;
        this.objectMapper = objectMapper;
        this.periodicResetService = periodicResetProvider == null ? null : periodicResetProvider.getIfAvailable();
        this.dailyBorrowLimit = Math.max(0, dailyBorrowLimit);
        this.borrowCooldownMs = Math.max(0L, borrowCooldownSeconds) * 1000L;
        this.borrowerRewardCurrencyId = borrowerRewardCurrencyId;
        this.borrowerRewardAmount = borrowerRewardAmount;
        this.lenderRewardCurrencyId = lenderRewardCurrencyId;
        this.lenderRewardAmount = lenderRewardAmount;
        if (this.periodicResetService != null) {
            this.periodicResetService.registerDaily(this::onDailyReset);
        }
    }

    /** 兼容旧测试构造 */
    public SupportService(JdbcTemplate jdbc,
                          FriendRepository friendRepository,
                          PlayerDataRepository playerDataRepository,
                          ObjectMapper objectMapper,
                          int dailyBorrowLimit,
                          long borrowCooldownSeconds,
                          int borrowerRewardCurrencyId,
                          int borrowerRewardAmount,
                          int lenderRewardCurrencyId,
                          int lenderRewardAmount) {
        this(jdbc, friendRepository, playerDataRepository, objectMapper, null,
                dailyBorrowLimit, borrowCooldownSeconds,
                borrowerRewardCurrencyId, borrowerRewardAmount,
                lenderRewardCurrencyId, lenderRewardAmount);
    }

    @Transactional
    public OpResult setSupportUnit(int playerId, long avatarInstanceId) {
        if (playerId <= 0 || avatarInstanceId <= 0) {
            return new OpResult(false, 2);
        }
        AvatarEntity avatar = findAvatar(playerId, avatarInstanceId);
        if (avatar == null) {
            return new OpResult(false, 3);
        }
        try {
            Map<String, Object> snap = new HashMap<>();
            snap.put("avatarId", avatar.getAvatarId());
            snap.put("level", avatar.getLevel());
            snap.put("promotion", avatar.getPromotion());
            snap.put("rank", avatar.getRank());
            snap.put("equippedSkinId", avatar.getEquippedSkinId());
            String json = objectMapper.writeValueAsString(snap);
            jdbc.update("""
                    INSERT INTO support_unit
                    (player_id, avatar_instance_id, avatar_id, level, promotion, rank_val, snapshot_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE avatar_instance_id=VALUES(avatar_instance_id),
                      avatar_id=VALUES(avatar_id), level=VALUES(level), promotion=VALUES(promotion),
                      rank_val=VALUES(rank_val), snapshot_json=VALUES(snapshot_json),
                      updated_at=NOW(3)
                    """, playerId, avatarInstanceId, avatar.getAvatarId(), avatar.getLevel(),
                    avatar.getPromotion(), avatar.getRank(), json);
            return new OpResult(true, 0);
        } catch (Exception e) {
            log.warn("setSupportUnit failed: {}", e.toString());
            return new OpResult(false, 5);
        }
    }

    public SupportSnapshot getOwnSupport(int playerId) {
        return loadSupport(playerId);
    }

    /**
     * 开战前借用好友助战。
     * retcode: 0成功 2参数 3非好友 4无外借 5日限 6冷却。
     */
    public SupportSnapshot borrowFriendSupport(int requesterId, int friendPlayerId) {
        BorrowResult r = borrowFriendSupportDetailed(requesterId, friendPlayerId);
        return r.snapshot();
    }

    public BorrowResult borrowFriendSupportDetailed(int requesterId, int friendPlayerId) {
        if (requesterId <= 0 || friendPlayerId <= 0 || requesterId == friendPlayerId) {
            return BorrowResult.fail(2);
        }
        FriendEntity rel = friendRepository.findRelation(requesterId, friendPlayerId);
        if (rel == null || rel.getStatus() != 1) {
            return BorrowResult.fail(3);
        }
        if (dailyBorrowLimit > 0 && countTodayBorrows(requesterId) >= dailyBorrowLimit) {
            return BorrowResult.fail(5);
        }
        // 同一好友助战角色每天只能用一次（凌晨 4 点日切后刷新）
        if (countLenderUsedToday(friendPlayerId, requesterId) > 0) {
            return BorrowResult.fail(7);
        }
        String cdKey = requesterId + ":" + friendPlayerId;
        long now = System.currentTimeMillis();
        Long last = lastBorrowAt.get(cdKey);
        if (borrowCooldownMs > 0 && last != null && now - last < borrowCooldownMs) {
            return BorrowResult.fail(6);
        }
        SupportSnapshot snap = loadSupport(friendPlayerId);
        if (snap == null) {
            return BorrowResult.fail(4);
        }
        lastBorrowAt.put(cdKey, now);
        recordBorrow(requesterId, friendPlayerId);
        recordLenderDailyUsage(friendPlayerId, requesterId);
        return new BorrowResult(snap, 0);
    }

    /** 日切清理：删除过期助战使用记录（由 PeriodicReset 触发）。 */
    public void onDailyReset(LocalDate day) {
        String keep = day.minusDays(1).toString();
        try {
            jdbc.update("DELETE FROM support_daily_usage WHERE usage_day < ?", keep);
            jdbc.update("DELETE FROM support_borrow_log WHERE borrow_day < ?", keep);
            lastBorrowAt.clear();
            log.info("SupportService daily reset cleared usage before={}", keep);
        } catch (Exception e) {
            log.debug("support daily reset skipped: {}", e.getMessage());
        }
    }

    /** 手动刷新自身助战快照（重读角色属性写回）。 */
    public OpResult refreshOwnSupport(int playerId) {
        SupportSnapshot cur = loadSupport(playerId);
        if (cur == null) {
            return new OpResult(false, 4);
        }
        return setSupportUnit(playerId, cur.avatarInstanceId());
    }

    public Map<String, Integer> rewardHints() {
        Map<String, Integer> m = new HashMap<>();
        m.put("borrowerCurrencyId", borrowerRewardCurrencyId);
        m.put("borrowerAmount", borrowerRewardAmount);
        m.put("lenderCurrencyId", lenderRewardCurrencyId);
        m.put("lenderAmount", lenderRewardAmount);
        m.put("dailyBorrowLimit", dailyBorrowLimit);
        return m;
    }

    public List<SupportSnapshot> listFriendSupports(int playerId) {
        return friendRepository.listFriends(playerId).stream()
                .filter(f -> f.getStatus() == 1)
                .map(f -> {
                    int other = f.getPlayerId1() == playerId ? f.getPlayerId2() : f.getPlayerId1();
                    return loadSupport(other);
                })
                .filter(s -> s != null)
                .toList();
    }

    private void recordBorrow(int requesterId, int friendPlayerId) {
        String day = LocalDate.now(ZONE).toString();
        try {
            jdbc.update("""
                    INSERT INTO support_borrow_log (requester_id, lender_id, borrow_day, created_at)
                    VALUES (?, ?, ?, NOW(3))
                    """, requesterId, friendPlayerId, day);
        } catch (Exception e) {
            log.debug("support borrow log skipped: {}", e.getMessage());
        }
    }

    private void recordLenderDailyUsage(int lenderId, int borrowerId) {
        String day = LocalDate.now(ZONE).toString();
        try {
            jdbc.update("""
                    INSERT INTO support_daily_usage (usage_day, lender_id, borrower_id, use_count)
                    VALUES (?, ?, ?, 1)
                    ON DUPLICATE KEY UPDATE use_count = use_count + 1
                    """, day, lenderId, borrowerId);
        } catch (Exception e) {
            log.debug("support daily usage skipped: {}", e.getMessage());
        }
    }

    private int countLenderUsedToday(int lenderId, int borrowerId) {
        String day = LocalDate.now(ZONE).toString();
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT use_count FROM support_daily_usage
                    WHERE usage_day=? AND lender_id=? AND borrower_id=?
                    """, Integer.class, day, lenderId, borrowerId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private int countTodayBorrows(int requesterId) {
        String day = LocalDate.now(ZONE).toString();
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM support_borrow_log WHERE requester_id=? AND borrow_day=?
                    """, Integer.class, requesterId, day);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private SupportSnapshot loadSupport(int playerId) {
        try {
            List<SupportSnapshot> list = jdbc.query("""
                    SELECT player_id, avatar_instance_id, avatar_id, level, promotion, rank_val, snapshot_json
                    FROM support_unit WHERE player_id = ?
                    """, (rs, i) -> new SupportSnapshot(
                    rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getInt(4),
                    rs.getInt(5), rs.getInt(6), rs.getString(7)), playerId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private AvatarEntity findAvatar(int playerId, long avatarInstanceId) {
        try {
            List<AvatarEntity> avatars = playerDataRepository.loadAvatars(playerId);
            if (avatars == null) {
                return null;
            }
            for (AvatarEntity a : avatars) {
                if (a.getId() == avatarInstanceId) {
                    return a;
                }
            }
        } catch (Exception e) {
            log.debug("findAvatar failed: {}", e.getMessage());
        }
        return null;
    }
}
