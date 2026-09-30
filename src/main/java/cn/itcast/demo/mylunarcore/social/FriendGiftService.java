package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.repo.FriendRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 好友体力赠送/领取：每天可向每位好友赠送固定体力；领取记录写入 Redis Set 防重复。
 */
@Service
public class FriendGiftService {

    private static final Logger log = LoggerFactory.getLogger(FriendGiftService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String REDIS_CLAIMED_PREFIX = "lunar:friend:stamina:claimed:";

    public record OpResult(boolean ok, int retcode, int amount) {
        public static OpResult fail(int retcode) {
            return new OpResult(false, retcode, 0);
        }
    }

    private final JdbcTemplate jdbc;
    private final FriendRepository friendRepository;
    private final ObjectProvider<StaminaService> staminaProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final PeriodicResetService periodicResetService;
    private final int giftAmount;

    public FriendGiftService(JdbcTemplate jdbc,
                             FriendRepository friendRepository,
                             ObjectProvider<StaminaService> staminaProvider,
                             ObjectProvider<StringRedisTemplate> redisProvider,
                             PeriodicResetService periodicResetService,
                             @Value("${lunarcore.friend.stamina-gift-amount:5}") int giftAmount) {
        this.jdbc = jdbc;
        this.friendRepository = friendRepository;
        this.staminaProvider = staminaProvider;
        this.redisProvider = redisProvider;
        this.periodicResetService = periodicResetService;
        this.giftAmount = Math.max(1, giftAmount);
    }

    @PostConstruct
    public void hookReset() {
        periodicResetService.registerDaily(day -> clearDailyRedis(day.minusDays(1).toString()));
    }

    /** 向所有好友各赠送一次体力（幂等：同日同好友只写一行）。 */
    @Transactional
    public OpResult giftAllFriends(int fromPlayerId) {
        if (fromPlayerId <= 0) {
            return OpResult.fail(2);
        }
        String day = LocalDate.now(ZONE).toString();
        List<FriendEntity> friends = friendRepository.listFriends(fromPlayerId);
        int sent = 0;
        for (FriendEntity f : friends) {
            if (f.getStatus() != 1) {
                continue;
            }
            int to = f.getPlayerId1() == fromPlayerId ? f.getPlayerId2() : f.getPlayerId1();
            try {
                int n = jdbc.update("""
                        INSERT IGNORE INTO friend_stamina_gift_log
                        (gift_day, from_player_id, to_player_id, amount, claimed)
                        VALUES (?, ?, ?, ?, 0)
                        """, day, fromPlayerId, to, giftAmount);
                if (n > 0) {
                    sent++;
                }
            } catch (Exception e) {
                log.debug("gift insert skipped: {}", e.getMessage());
            }
        }
        return new OpResult(true, 0, sent * giftAmount);
    }

    /** 领取某好友今日赠送的体力。 */
    @Transactional
    public OpResult claim(int toPlayerId, int fromPlayerId) {
        if (toPlayerId <= 0 || fromPlayerId <= 0) {
            return OpResult.fail(2);
        }
        String day = LocalDate.now(ZONE).toString();
        String redisKey = REDIS_CLAIMED_PREFIX + day + ":" + toPlayerId;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            Boolean already = redis.opsForSet().isMember(redisKey, String.valueOf(fromPlayerId));
            if (Boolean.TRUE.equals(already)) {
                return OpResult.fail(4); // 已领
            }
        }
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE friend_stamina_gift_log SET claimed=1
                    WHERE gift_day=? AND from_player_id=? AND to_player_id=? AND claimed=0
                    """, day, fromPlayerId, toPlayerId);
        } catch (Exception e) {
            return OpResult.fail(5);
        }
        if (updated == 0) {
            return OpResult.fail(3); // 无可领
        }
        StaminaService stamina = staminaProvider.getIfAvailable();
        if (stamina != null) {
            stamina.addBonus(toPlayerId, giftAmount, "friend_gift:" + fromPlayerId);
        }
        if (redis != null) {
            redis.opsForSet().add(redisKey, String.valueOf(fromPlayerId));
            redis.expire(redisKey, Duration.ofDays(2));
        }
        return new OpResult(true, 0, giftAmount);
    }

    public List<Integer> listClaimableFrom(int toPlayerId) {
        String day = LocalDate.now(ZONE).toString();
        try {
            return jdbc.query("""
                    SELECT from_player_id FROM friend_stamina_gift_log
                    WHERE gift_day=? AND to_player_id=? AND claimed=0
                    """, (rs, i) -> rs.getInt(1), day, toPlayerId);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void clearDailyRedis(String day) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            // 扫描成本高，依赖 TTL；此处仅打日志
            log.info("FriendGift daily reset day={}", day);
        } catch (Exception ignored) {
        }
    }
}
