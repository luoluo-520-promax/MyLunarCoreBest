package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.PlayerSessionStateMachine;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 匹配编排：分段队列、动态超时、可选补位、成功率指标。
 * <p>
 * 大厅排队不切入 {@link PlayerSessionState#MATCHING}，仅设置 {@link MatchPhase#QUEUED}；
 * 成局后推送 {@code MatchSuccessScNotify}（10 秒 Ready Check），确认准备后才 LOCKED/MATCHING。
 */
@Service
public class MatchmakingService {

    public static final long READY_CHECK_TIMEOUT_MS = 10_000L;

    public record JoinResult(boolean success, int queuePosition, String reason) {
        public JoinResult(boolean success, int queuePosition) {
            this(success, queuePosition, success ? "ok" : "rejected");
        }
    }

    public record ReadyCheckResult(boolean success, int retcode, boolean locked) {
        public static ReadyCheckResult fail(int retcode) {
            return new ReadyCheckResult(false, retcode, false);
        }
    }

    private static final int DEFAULT_TEAM_SIZE = 2;

    private final MatchQueue matchQueue = new MatchQueue();
    private final RoomService roomService;
    private final GameSessionManager sessionManager;
    private final LunarCoreProperties properties;
    private final BusinessMetrics businessMetrics;
    private final cn.itcast.demo.mylunarcore.arena.ArenaRatingService arenaRatingService;
    private final MatchDomainEvents matchDomainEvents;
    private final Map<Long, Long> readyDeadlines = new ConcurrentHashMap<>();
    private final ObjectProvider<MatchObjectPools> poolProvider;

    public MatchmakingService(RoomService roomService,
                              GameSessionManager sessionManager,
                              LunarCoreProperties properties,
                              BusinessMetrics businessMetrics) {
        this(roomService, sessionManager, properties, businessMetrics, null, null, null);
    }

    public MatchmakingService(RoomService roomService,
                              GameSessionManager sessionManager,
                              LunarCoreProperties properties,
                              BusinessMetrics businessMetrics,
                              org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.arena.ArenaRatingService> arenaProvider) {
        this(roomService, sessionManager, properties, businessMetrics, arenaProvider, null, null);
    }

    public MatchmakingService(RoomService roomService,
                              GameSessionManager sessionManager,
                              LunarCoreProperties properties,
                              BusinessMetrics businessMetrics,
                              org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.arena.ArenaRatingService> arenaProvider,
                              org.springframework.beans.factory.ObjectProvider<MatchDomainEvents> matchEventsProvider) {
        this(roomService, sessionManager, properties, businessMetrics, arenaProvider, matchEventsProvider, null);
    }

    public MatchmakingService(RoomService roomService,
                              GameSessionManager sessionManager,
                              LunarCoreProperties properties,
                              BusinessMetrics businessMetrics,
                              org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.arena.ArenaRatingService> arenaProvider,
                              org.springframework.beans.factory.ObjectProvider<MatchDomainEvents> matchEventsProvider,
                              ObjectProvider<MatchObjectPools> poolProvider) {
        this.roomService = roomService;
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.businessMetrics = businessMetrics;
        this.arenaRatingService = arenaProvider == null ? null : arenaProvider.getIfAvailable();
        this.matchDomainEvents = matchEventsProvider == null ? null : matchEventsProvider.getIfAvailable();
        this.poolProvider = poolProvider;
    }

    public JoinResult joinQueue(int playerId, int mode, int level, int power) {
        if (playerId <= 0) {
            return new JoinResult(false, 0, "invalid_player");
        }
        // 竞技场 mode=10：用 ELO 作为 power 分段维度，提升同分段匹配质量
        int effectivePower = power;
        if (mode == cn.itcast.demo.mylunarcore.arena.ArenaRatingService.MATCH_MODE_ARENA
                && arenaRatingService != null) {
            effectivePower = arenaRatingService.getOrCreate(playerId).rating();
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null) {
            PlayerSessionState current = session.getSessionState();
            // 大厅/场景排队：不切 MATCHING，避免锁界面
            if (current == PlayerSessionState.HALL || current == PlayerSessionState.SCENE) {
                session.setMatchPhase(MatchPhase.QUEUED);
            } else if (current == PlayerSessionState.MATCHING) {
                // ChallengeMatchCoordinator 等路径已置 MATCHING，保持并标记排队
                session.setMatchPhase(MatchPhase.QUEUED);
            } else {
                return new JoinResult(false, 0, "illegal_state:" + current);
            }
        }
        LunarCoreProperties.MatchmakingProperties mm = properties.getMatchmaking();
        long now = System.currentTimeMillis();
        MatchQueue.QueueEntry entry = new MatchQueue.QueueEntry(playerId, mode, level, effectivePower, now);
        int position = matchQueue.enqueue(entry, mm.getLevelBand(), mm.getPowerBand());
        businessMetrics.setMatchQueueDepth(matchQueue.queueDepth());
        // 不在入队时全量匹配：由 batchMatchTick 每 100ms 批量处理，抑制对象洪泛
        return new JoinResult(true, position, "ok");
    }

    public boolean cancelQueue(int playerId, int mode) {
        boolean removed = matchQueue.cancel(playerId, mode);
        if (removed) {
            businessMetrics.recordMatchCancel();
            businessMetrics.setMatchQueueDepth(matchQueue.queueDepth());
            GameSession session = sessionManager.getOrNull(playerId);
            if (session != null) {
                session.setMatchPhase(MatchPhase.IDLE);
                if (session.getSessionState() == PlayerSessionState.MATCHING) {
                    PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.HALL);
                }
            }
        }
        return removed;
    }

    public ReadyCheckResult readyCheck(int playerId, long roomId, boolean ready) {
        if (playerId <= 0) {
            return ReadyCheckResult.fail(1);
        }
        Long deadline = readyDeadlines.get(roomId);
        if (deadline != null && System.currentTimeMillis() > deadline) {
            return ReadyCheckResult.fail(3);
        }
        RoomService.Room room = roomService.setReady(playerId, roomId, ready);
        if (room == null) {
            return ReadyCheckResult.fail(2);
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null) {
            if (ready) {
                session.setMatchPhase(MatchPhase.LOCKED);
                PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.MATCHING);
            } else {
                session.setMatchPhase(MatchPhase.READY_CHECK);
            }
        }
        return new ReadyCheckResult(true, 0, ready);
    }

    public List<Integer> drainAllQueues() {
        List<Integer> drained = matchQueue.drainAllPlayerIds();
        businessMetrics.setMatchQueueDepth(0);
        for (Integer playerId : drained) {
            GameSession session = sessionManager.getOrNull(playerId);
            if (session != null) {
                session.setMatchPhase(MatchPhase.IDLE);
                if (session.getSessionState() == PlayerSessionState.MATCHING) {
                    PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.HALL);
                }
            }
        }
        return drained;
    }

    @Scheduled(fixedDelayString = "${lunarcore.matchmaking.batch-interval-ms:100}")
    public void batchMatchTick() {
        // 每 100ms 统一拉队计算，避免每人入队触发全量匹配
        int budget = Math.max(50, properties.getMatchmaking().getBatchSize());
        int processed = 0;
        for (int mode = 1; mode <= 10 && processed < budget; mode++) {
            int before = matchQueue.queueDepth();
            tryMatch(mode);
            int after = matchQueue.queueDepth();
            processed += Math.max(0, before - after);
        }
        businessMetrics.setMatchQueueDepth(matchQueue.queueDepth());
    }

    @Scheduled(fixedDelayString = "${lunarcore.matchmaking.sweep-ms:5000}")
    public void sweepTimeoutsAndRetry() {
        LunarCoreProperties.MatchmakingProperties mm = properties.getMatchmaking();
        long now = System.currentTimeMillis();
        List<MatchQueue.QueueEntry> timedOut = matchQueue.evictTimedOut(now, mm.getQueueTimeoutMs());
        for (MatchQueue.QueueEntry e : timedOut) {
            businessMetrics.recordMatchTimeout();
            GameSession session = sessionManager.getOrNull(e.playerId());
            if (session != null) {
                session.setMatchPhase(MatchPhase.IDLE);
                if (session.getSessionState() == PlayerSessionState.MATCHING) {
                    PlayerSessionStateMachine.tryTransition(session, PlayerSessionState.HALL);
                }
            }
        }
        businessMetrics.setMatchQueueDepth(matchQueue.queueDepth());
        // 对常见 mode 再尝试匹配（1–8）
        for (int mode = 1; mode <= 8; mode++) {
            tryMatch(mode);
        }
    }

    private void tryMatch(int mode) {
        LunarCoreProperties.MatchmakingProperties mm = properties.getMatchmaking();
        boolean preferCompat = properties.getAiAssist().isMatchScoreEnabled();
        boolean expand = mm.isExpandSegmentOnWait();
        List<MatchQueue.QueueEntry> matched = matchQueue.pollMatch(
                mode, DEFAULT_TEAM_SIZE, preferCompat, mm.getLevelBand(), mm.getPowerBand(), expand);
        // 人数不足时：先捞至少 1 名真人，再按战力/ELO 倍率补机器人
        if (matched.isEmpty() && mm.isFillWithBots()) {
            matched = matchQueue.pollMatch(
                    mode, 1, preferCompat, mm.getLevelBand(), mm.getPowerBand(), expand);
            if (!matched.isEmpty()) {
                matched = fillWithBots(matched, mode);
            }
        } else if (matched.size() < DEFAULT_TEAM_SIZE && mm.isFillWithBots() && !matched.isEmpty()) {
            matched = fillWithBots(matched, mode);
        }
        if (matched.size() == DEFAULT_TEAM_SIZE) {
            long wait = System.currentTimeMillis() - matched.get(0).enqueueTime();
            businessMetrics.recordMatchSuccess(wait);
            RoomService.Room room = createRoomPooled(mode, matched);
            long deadline = System.currentTimeMillis() + READY_CHECK_TIMEOUT_MS;
            readyDeadlines.put(room.roomId(), deadline);
            notifyMatchSuccess(room, deadline);
            if (matchDomainEvents != null) {
                List<Integer> ids = matched.stream().map(MatchQueue.QueueEntry::playerId).toList();
                matchDomainEvents.publishMatchFound("m-" + mode + "-" + System.currentTimeMillis(), mode, ids);
            }
            businessMetrics.setMatchQueueDepth(matchQueue.queueDepth());
        }
    }

    private RoomService.Room createRoomPooled(int mode, List<MatchQueue.QueueEntry> matched) {
        MatchObjectPools pools = poolProvider == null ? null : poolProvider.getIfAvailable();
        if (pools == null) {
            return roomService.createRoom(mode, matched);
        }
        MatchObjectPools.PooledRoom pooled = null;
        try {
            pooled = pools.borrowRoom();
            List<RoomService.RoomMember> members = new ArrayList<>(matched.size());
            for (MatchQueue.QueueEntry e : matched) {
                members.add(new RoomService.RoomMember(e.playerId(), false));
            }
            long roomId = pools.nextRoomId();
            pooled.assign(roomId, mode, 1, members);
            RoomService.Room immutable = pooled.toImmutable();
            return roomService.adoptPooledRoom(immutable, matched);
        } catch (Exception e) {
            return roomService.createRoom(mode, matched);
        } finally {
            if (pools != null && pooled != null) {
                pools.returnRoom(pooled);
            }
        }
    }

    private void notifyMatchSuccess(RoomService.Room room, long deadlineMs) {
        MatchmakingSystemProto.MatchSuccessScNotify.Builder builder =
                MatchmakingSystemProto.MatchSuccessScNotify.newBuilder()
                        .setRoomId(room.roomId())
                        .setMode(room.mode())
                        .setReadyTimeoutMs((int) READY_CHECK_TIMEOUT_MS)
                        .setReadyDeadlineMs(deadlineMs);
        for (RoomService.RoomMember member : room.members()) {
            builder.addMembers(MatchmakingSystemProto.MatchRoomMember.newBuilder()
                    .setPlayerId(member.playerId())
                    .setReady(member.ready())
                    .build());
        }
        GamePacket packet = new GamePacket(CmdIds.MATCH_SUCCESS_SC_NOTIFY, builder.build().toByteArray());
        for (RoomService.RoomMember member : room.members()) {
            GameSession session = sessionManager.getOrNull(member.playerId());
            if (session == null) {
                continue;
            }
            session.setMatchPhase(MatchPhase.READY_CHECK);
            session.send(packet);
        }
        pushCountdownInteractive(room, deadlineMs);
    }

    /** Ready Check 10 秒填充迷你交互：待机动作 + 队友养成名片。 */
    private void pushCountdownInteractive(RoomService.Room room, long deadlineMs) {
        MatchmakingSystemProto.MatchCountdownInteractiveScNotify.Builder b =
                MatchmakingSystemProto.MatchCountdownInteractiveScNotify.newBuilder()
                        .setRoomId(room.roomId())
                        .setReadyDeadlineMs(deadlineMs)
                        .setRemainMs((int) Math.max(0L, deadlineMs - System.currentTimeMillis()))
                        .addIdleActions("kick_pebble")
                        .addIdleActions("flourish_weapon")
                        .addIdleActions("wave");
        for (RoomService.RoomMember member : room.members()) {
            GameSession session = sessionManager.getOrNull(member.playerId());
            String nick = session == null || session.getNickname() == null
                    ? "Player" + member.playerId() : session.getNickname();
            int level = session == null ? 1 : Math.max(1, session.getLevel());
            b.addTeammateCards(MatchmakingSystemProto.MatchTeammateCard.newBuilder()
                    .setPlayerId(member.playerId())
                    .setNickname(nick)
                    .setLevel(level)
                    .setAvatarId(0)
                    .setTitle("同行者")
                    .build());
        }
        GamePacket interactive = new GamePacket(CmdIds.MATCH_COUNTDOWN_INTERACTIVE_SC_NOTIFY,
                b.build().toByteArray());
        for (RoomService.RoomMember member : room.members()) {
            GameSession session = sessionManager.getOrNull(member.playerId());
            if (session != null) {
                session.send(interactive);
            }
        }
    }

    private List<MatchQueue.QueueEntry> fillWithBots(List<MatchQueue.QueueEntry> partial, int mode) {
        List<MatchQueue.QueueEntry> filled = new ArrayList<>(partial);
        int need = DEFAULT_TEAM_SIZE - filled.size();
        int base = properties.getMatchmaking().getBotUidBase();
        double ratio = properties.getMatchmaking().getBotPowerRatio();
        int levelOffset = properties.getMatchmaking().getBotLevelOffset();
        long now = System.currentTimeMillis();
        int avgLevel = (int) partial.stream().mapToInt(MatchQueue.QueueEntry::level).average().orElse(1);
        int avgPower = (int) partial.stream().mapToInt(MatchQueue.QueueEntry::power).average().orElse(100);
        int botLevel = Math.max(1, avgLevel + levelOffset);
        int botPower = Math.max(50, (int) Math.round(avgPower * Math.max(0.5, Math.min(1.2, ratio))));
        for (int i = 0; i < need; i++) {
            // 轻微抖动，避免完全同模
            int jitter = (i * 17) % 31;
            filled.add(new MatchQueue.QueueEntry(
                    base - i, mode, botLevel, Math.max(50, botPower - jitter), now));
        }
        return filled;
    }
}
