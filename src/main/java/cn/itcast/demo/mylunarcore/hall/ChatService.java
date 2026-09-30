// 大厅聊天服务：世界频道广播、私聊双向推送与内存历史缓存
package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.social.PlayerReportBlockService;
import io.netty.channel.Channel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大厅聊天：世界/私聊 Pub/Sub；离线私聊落库，上线后 {@link #flushOffline(int)} 推送。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public record ChatRecord(int channelType, int senderId, String senderName, int targetId, String content, long sendTime) {}

    private static final int MAX_HISTORY = 100;
    private static final char SEP = '\u001f';

    private final Map<String, Deque<ChatRecord>> histories = new ConcurrentHashMap<>();
    private final Map<Integer, Set<Long>> channelSubscribers = new ConcurrentHashMap<>();

    private final GameSessionManager sessionManager;
    private final LunarCoreProperties properties;
    private final StringRedisTemplate redis;
    private final RedisMessageListenerContainer listenerContainer;
    private final JdbcTemplate jdbc;
    private final ChatDomainEvents chatDomainEvents;
    private final ChatSensitiveWordFilter sensitiveWordFilter;
    private final ChatModerationService moderationService;
    private final ReliablePrivateChatDelivery reliableDelivery;
    private final PlayerReportBlockService reportBlockService;

    public ChatService(GameSessionManager sessionManager,
                       LunarCoreProperties properties,
                       ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectProvider<RedisMessageListenerContainer> listenerContainerProvider,
                       JdbcTemplate jdbc,
                       ObjectProvider<ChatDomainEvents> chatEventsProvider,
                       ObjectProvider<ChatSensitiveWordFilter> wordFilterProvider,
                       ObjectProvider<ChatModerationService> moderationProvider,
                       ObjectProvider<ReliablePrivateChatDelivery> reliableDeliveryProvider) {
        this(sessionManager, properties, redisProvider, listenerContainerProvider, jdbc,
                chatEventsProvider, wordFilterProvider, moderationProvider, reliableDeliveryProvider, null);
    }

    public ChatService(GameSessionManager sessionManager,
                       LunarCoreProperties properties,
                       ObjectProvider<StringRedisTemplate> redisProvider,
                       ObjectProvider<RedisMessageListenerContainer> listenerContainerProvider,
                       JdbcTemplate jdbc,
                       ObjectProvider<ChatDomainEvents> chatEventsProvider,
                       ObjectProvider<ChatSensitiveWordFilter> wordFilterProvider,
                       ObjectProvider<ChatModerationService> moderationProvider,
                       ObjectProvider<ReliablePrivateChatDelivery> reliableDeliveryProvider,
                       ObjectProvider<PlayerReportBlockService> reportBlockProvider) {
        this.sessionManager = sessionManager;
        this.properties = properties;
        this.redis = redisProvider.getIfAvailable();
        this.listenerContainer = listenerContainerProvider.getIfAvailable();
        this.jdbc = jdbc;
        this.chatDomainEvents = chatEventsProvider == null ? null : chatEventsProvider.getIfAvailable();
        this.sensitiveWordFilter = wordFilterProvider == null ? null : wordFilterProvider.getIfAvailable();
        this.moderationService = moderationProvider == null ? null : moderationProvider.getIfAvailable();
        this.reliableDelivery = reliableDeliveryProvider == null ? null : reliableDeliveryProvider.getIfAvailable();
        this.reportBlockService = reportBlockProvider == null ? null : reportBlockProvider.getIfAvailable();
    }

    @PostConstruct
    void subscribeChannels() {
        if (redis == null || listenerContainer == null) {
            return;
        }
        subscribe(properties.getRedis().getChatWorldChannel(), 0);
        subscribe(properties.getRedis().getChatPrivateChannel(), 1);
        log.info("Chat Pub/Sub subscribed: world={}, private={}",
                properties.getRedis().getChatWorldChannel(),
                properties.getRedis().getChatPrivateChannel());
    }

    private void subscribe(String channel, int expectedType) {
        listenerContainer.addMessageListener((Message message, byte[] pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            ChatRecord record = decode(body);
            if (record == null || record.channelType() != expectedType) {
                return;
            }
            deliverLocal(record);
        }, new ChannelTopic(channel));
    }

    /**
     * @return 0 成功，2 内容为空，3 禁言，4 敏感词
     */
    public int send(int senderId, String senderName, int channelType, int targetId, String content) {
        if (content == null || content.isBlank()) {
            return 2;
        }
        if (moderationService != null && moderationService.isMuted(senderId)) {
            return 3;
        }
        if (channelType == 1 && reportBlockService != null
                && reportBlockService.isBlockedEitherWay(senderId, targetId)) {
            return 5; // 屏蔽中
        }
        if (sensitiveWordFilter != null && sensitiveWordFilter.containsSensitive(content)) {
            if (moderationService != null) {
                moderationService.audit(senderId, targetId, "blocked_sensitive", content);
            }
            return 4;
        }
        ChatRecord record = new ChatRecord(channelType, senderId, senderName, targetId, content.trim(),
                System.currentTimeMillis());
        if (moderationService != null) {
            moderationService.audit(senderId, targetId, "send", "ch=" + channelType);
        }
        if (chatDomainEvents != null) {
            if (channelType == 1) {
                chatDomainEvents.publishPrivate(senderId, targetId, record.content());
            } else {
                chatDomainEvents.publishWorld(senderId, record.content());
            }
        }
        archive(record);
        long reliableMsgId = 0L;
        long reliableSeq = 0L;
        if (channelType == 1 && reliableDelivery != null) {
            ReliablePrivateChatDelivery.PendingMessage pending =
                    reliableDelivery.enqueue(senderId, targetId, record.content());
            reliableMsgId = pending.msgId();
            reliableSeq = pending.seq();
        }
        if (channelType == 1) {
            GameSession target = sessionManager.getOrNull(targetId);
            if (target == null || target.getChannel() == null) {
                persistOffline(record);
            }
        }
        if (redis != null) {
            String channel = channelType == 1
                    ? properties.getRedis().getChatPrivateChannel()
                    : properties.getRedis().getChatWorldChannel();
            if (channelType == 0 || channelType == 1) {
                try {
                    String payload = channelType == 1 && reliableMsgId > 0
                            ? encodeReliable(record, reliableMsgId, reliableSeq)
                            : encode(record);
                    redis.convertAndSend(channel, payload);
                    return 0;
                } catch (Exception e) {
                    log.warn("chat redis publish failed, fallback local, sender={}, type={}", senderId, channelType, e);
                }
            }
        }
        deliverLocal(record);
        return 0;
    }

    /** 客户端 ACK 跨服私聊投递。 */
    public boolean ackPrivateMessage(long msgId, int playerId) {
        return reliableDelivery != null && reliableDelivery.ack(msgId, playerId);
    }

    /** 定时重试未 ACK 的在线私聊（由调度器调用）。 */
    public int retryUnackedPrivate(long nowMs) {
        if (reliableDelivery == null) {
            return 0;
        }
        int n = 0;
        for (ReliablePrivateChatDelivery.PendingMessage pm : reliableDelivery.drainRetries(nowMs)) {
            ChatRecord record = new ChatRecord(1, pm.senderId(), "", pm.targetId(), pm.content(), nowMs);
            if (redis != null) {
                try {
                    redis.convertAndSend(properties.getRedis().getChatPrivateChannel(),
                            encodeReliable(record, pm.msgId(), pm.seq()));
                    n++;
                    continue;
                } catch (Exception ignored) {
                    // fall through local
                }
            }
            deliverLocal(record);
            n++;
        }
        return n;
    }

    /** 玩家上线后拉取并推送未读离线私聊（邮箱化投递 + 在线 Push 透传双保障）。 */
    public int flushOffline(int playerId) {
        List<ChatRecord> pending = loadOffline(playerId);
        for (ChatRecord record : pending) {
            archive(record);
            deliverLocal(record);
            markDelivered(record);
        }
        return pending.size();
    }

    /** 离线消息邮箱未读数（跨服私聊不丢失）。 */
    public int mailboxUnreadCount(int playerId) {
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM offline_chat_message
                    WHERE target_id = ? AND delivered = 0
                    """, Integer.class, playerId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 私聊：在线走 Redis Pub/Sub Push 透传；离线写入邮箱表，上线 {@link #flushOffline} 投递。
     */

    public List<ChatRecord> history(int channelType, int targetId, int limit) {
        Deque<ChatRecord> deque = histories.get(historyKey(channelType, targetId));
        if (deque == null) {
            return List.of();
        }
        int n = Math.min(Math.max(limit, 1), MAX_HISTORY);
        synchronized (deque) {
            List<ChatRecord> all = new ArrayList<>(deque);
            int from = Math.max(0, all.size() - n);
            return all.subList(from, all.size());
        }
    }

    public void subscribeWorld(long uid) {
        channelSubscribers.computeIfAbsent(0, k -> ConcurrentHashMap.newKeySet()).add(uid);
    }

    public void replyAsAssistant(int targetPlayerId, String content) {
        if (targetPlayerId <= 0 || content == null || content.isBlank()) {
            return;
        }
        ChatRecord record = new ChatRecord(1, 0, "游戏助手", targetPlayerId, content.trim(),
                System.currentTimeMillis());
        archive(record);
        GameSession target = sessionManager.getOrNull(targetPlayerId);
        if (target == null || target.getChannel() == null) {
            persistOffline(record);
        } else {
            deliverLocal(record);
        }
    }

    private void persistOffline(ChatRecord record) {
        try {
            jdbc.update("""
                    INSERT INTO offline_chat_message
                      (channel_type, sender_id, sender_name, target_id, content, send_time_ms, delivered)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """, record.channelType(), record.senderId(),
                    record.senderName() == null ? "" : record.senderName(),
                    record.targetId(), record.content(), record.sendTime());
        } catch (Exception e) {
            log.debug("offline chat persist skipped: {}", e.getMessage());
        }
    }

    private List<ChatRecord> loadOffline(int playerId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id, channel_type, sender_id, sender_name, target_id, content, send_time_ms
                    FROM offline_chat_message
                    WHERE target_id = ? AND delivered = 0
                    ORDER BY id ASC LIMIT 200
                    """, playerId);
            List<ChatRecord> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                out.add(new ChatRecord(
                        ((Number) row.get("channel_type")).intValue(),
                        ((Number) row.get("sender_id")).intValue(),
                        String.valueOf(row.get("sender_name")),
                        ((Number) row.get("target_id")).intValue(),
                        String.valueOf(row.get("content")),
                        ((Number) row.get("send_time_ms")).longValue()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void markDelivered(ChatRecord record) {
        try {
            jdbc.update("""
                    UPDATE offline_chat_message SET delivered = 1
                    WHERE target_id = ? AND sender_id = ? AND send_time_ms = ? AND delivered = 0
                    """, record.targetId(), record.senderId(), record.sendTime());
        } catch (Exception ignored) {
            // ignore
        }
    }

    private void archive(ChatRecord record) {
        histories.computeIfAbsent(historyKey(record.channelType(), record.targetId()), k -> new ArrayDeque<>());
        Deque<ChatRecord> deque = histories.get(historyKey(record.channelType(), record.targetId()));
        synchronized (deque) {
            deque.addLast(record);
            while (deque.size() > MAX_HISTORY) {
                deque.pollFirst();
            }
        }
    }

    private void deliverLocal(ChatRecord record) {
        HallSystemProto.ChatMessageScNotify notify = HallSystemProto.ChatMessageScNotify.newBuilder()
                .setMessage(toProto(record))
                .build();
        byte[] payload = notify.toByteArray();
        if (record.channelType() == 1) {
            GameSession target = sessionManager.getOrNull(record.targetId());
            if (target != null && target.getChannel() != null) {
                target.getChannel().writeAndFlush(new GamePacket(CmdIds.CHAT_MESSAGE_SC_NOTIFY, payload));
            }
            GameSession sender = sessionManager.getOrNull(record.senderId());
            if (sender != null && sender.getChannel() != null) {
                sender.getChannel().writeAndFlush(new GamePacket(CmdIds.CHAT_MESSAGE_SC_NOTIFY, payload));
            }
            return;
        }
        for (GameSession session : sessionManager.snapshotSessions()) {
            Channel ch = session.getChannel();
            if (ch != null) {
                ch.writeAndFlush(new GamePacket(CmdIds.CHAT_MESSAGE_SC_NOTIFY, payload));
            }
        }
    }

    public static HallSystemProto.ChatMessage toProto(ChatRecord record) {
        return HallSystemProto.ChatMessage.newBuilder()
                .setChannelType(record.channelType())
                .setSenderId(record.senderId())
                .setSenderName(record.senderName() == null ? "" : record.senderName())
                .setTargetId(record.targetId())
                .setContent(record.content())
                .setSendTime(record.sendTime())
                .build();
    }

    private static String historyKey(int channelType, int targetId) {
        return channelType + ":" + targetId;
    }

    public static String encode(ChatRecord r) {
        return String.valueOf(r.channelType()) + SEP
                + r.senderId() + SEP
                + safe(r.senderName()) + SEP
                + r.targetId() + SEP
                + safe(r.content()) + SEP
                + r.sendTime();
    }

    /** 可靠私聊载荷：原 encode + msgId + seq。 */
    public static String encodeReliable(ChatRecord r, long msgId, long seq) {
        return encode(r) + SEP + msgId + SEP + seq;
    }

    public static ChatRecord decode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String[] parts = body.split(String.valueOf(SEP), -1);
        if (parts.length < 6) {
            return null;
        }
        try {
            return new ChatRecord(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts[2],
                    Integer.parseInt(parts[3]),
                    parts[4],
                    Long.parseLong(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace(SEP, ' ');
    }
}
