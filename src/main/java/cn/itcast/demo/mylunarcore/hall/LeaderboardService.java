package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 排行榜：Redis ZSET（可选）或进程内存；只保留历史最高分。
 * 支持按 boardType + 赛季/活动后缀动态开榜（见 {@link LeaderboardBoardTypes}）。
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    public record Entry(int rank, int playerId, String nickname, long score) {}

    private final BoardStore store;

    public LeaderboardService() {
        this.store = new MemoryBoardStore();
    }

    @Autowired
    public LeaderboardService(ObjectProvider<StringRedisTemplate> redisProvider,
                              LunarCoreProperties properties) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (properties.getRedis().isEnabled() && redis != null) {
            this.store = new RedisBoardStore(redis, properties.getRedis().getLeaderboardKeyPrefix());
            log.info("LeaderboardService using Redis ZSET prefix={}", properties.getRedis().getLeaderboardKeyPrefix());
        } else {
            this.store = new MemoryBoardStore();
            log.info("LeaderboardService using in-memory boards");
        }
    }

    public void updateScore(int boardType, int playerId, String nickname, long score) {
        store.update(Integer.toString(boardType), playerId, nickname == null ? "" : nickname, score);
    }

    public void updateScore(int boardType, String seasonOrActivity, int playerId, String nickname, long score) {
        store.update(LeaderboardBoardTypes.boardKey(boardType, seasonOrActivity),
                playerId, nickname == null ? "" : nickname, score);
    }

    public List<Entry> topN(int boardType, int topN) {
        return store.topN(Integer.toString(boardType), topN);
    }

    public List<Entry> topN(int boardType, String seasonOrActivity, int topN) {
        return store.topN(LeaderboardBoardTypes.boardKey(boardType, seasonOrActivity), topN);
    }

    interface BoardStore {
        void update(String boardKey, int playerId, String nickname, long score);

        List<Entry> topN(String boardKey, int topN);
    }

    static final class MemoryBoardStore implements BoardStore {
        private final Map<String, Map<Integer, AtomicLong>> boards = new ConcurrentHashMap<>();
        private final Map<String, Map<Integer, String>> nicknames = new ConcurrentHashMap<>();

        @Override
        public void update(String boardKey, int playerId, String nickname, long score) {
            boards.computeIfAbsent(boardKey, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(playerId, k -> new AtomicLong(0))
                    .updateAndGet(prev -> Math.max(prev, score));
            if (nickname != null && !nickname.isBlank()) {
                nicknames.computeIfAbsent(boardKey, k -> new ConcurrentHashMap<>()).put(playerId, nickname);
            }
        }

        @Override
        public List<Entry> topN(String boardKey, int topN) {
            Map<Integer, AtomicLong> board = boards.getOrDefault(boardKey, Map.of());
            Map<Integer, String> names = nicknames.getOrDefault(boardKey, Map.of());
            List<Entry> entries = new ArrayList<>();
            board.forEach((playerId, score) ->
                    entries.add(new Entry(0, playerId,
                            names.getOrDefault(playerId, "Player" + playerId), score.get())));
            entries.sort(Comparator.comparingLong(Entry::score).reversed());
            int limit = Math.min(Math.max(topN, 1), 100);
            List<Entry> result = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                Entry e = entries.get(i);
                result.add(new Entry(i + 1, e.playerId(), e.nickname(), e.score()));
            }
            return result;
        }
    }

    static final class RedisBoardStore implements BoardStore {
        private final StringRedisTemplate redis;
        private final String keyPrefix;

        RedisBoardStore(StringRedisTemplate redis, String keyPrefix) {
            this.redis = redis;
            this.keyPrefix = (keyPrefix == null || keyPrefix.isBlank()) ? "lunar:lb:" : keyPrefix;
        }

        private String key(String boardKey) {
            return keyPrefix + boardKey;
        }

        @Override
        public void update(String boardKey, int playerId, String nickname, long score) {
            String member = Integer.toString(playerId);
            Double current = redis.opsForZSet().score(key(boardKey), member);
            if (current == null || score > current) {
                redis.opsForZSet().add(key(boardKey), member, score);
                if (nickname != null && !nickname.isBlank()) {
                    redis.opsForHash().put(key(boardKey) + ":nick", member, nickname);
                }
            }
        }

        @Override
        public List<Entry> topN(String boardKey, int topN) {
            int limit = Math.min(Math.max(topN, 1), 100);
            Set<ZSetOperations.TypedTuple<String>> tuples =
                    redis.opsForZSet().reverseRangeWithScores(key(boardKey), 0, limit - 1L);
            List<Entry> result = new ArrayList<>();
            if (tuples == null) {
                return result;
            }
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null || t.getScore() == null) {
                    continue;
                }
                int playerId = Integer.parseInt(t.getValue());
                Object nick = redis.opsForHash().get(key(boardKey) + ":nick", t.getValue());
                String nickname = nick == null ? "Player" + playerId : nick.toString();
                result.add(new Entry(rank++, playerId, nickname, t.getScore().longValue()));
            }
            return result;
        }
    }
}
