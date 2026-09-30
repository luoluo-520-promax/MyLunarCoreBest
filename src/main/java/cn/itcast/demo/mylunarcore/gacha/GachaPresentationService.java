package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.common.ClientUiParams;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽卡表现层状态机：START → DRAWING → ACK(ENDED)。
 * 保证客户端动画播放与发奖结果对齐，避免「有数据无表现」或重复开抽。
 */
@Service
public class GachaPresentationService {

    public enum State { STARTED, DRAWING, ENDED }

    public record Session(String sessionId, int playerId, int bannerType, int times, State state,
                          ClientUiParams ui) {}

    public record StartResult(boolean ok, int retcode, Session session) {
        public static StartResult fail(int retcode) {
            return new StartResult(false, retcode, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Session> memory = new ConcurrentHashMap<>();

    public GachaPresentationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public StartResult start(int playerId, int bannerType, int times, String clientNonce) {
        if (playerId <= 0 || times <= 0) {
            return StartResult.fail(2);
        }
        Session existing = findOpen(playerId);
        if (existing != null) {
            return new StartResult(true, 0, existing);
        }
        String sid = UUID.randomUUID().toString().replace("-", "");
        ClientUiParams ui = resolveUi(bannerType, times);
        Session session = new Session(sid, playerId, bannerType, times, State.STARTED, ui);
        memory.put(sid, session);
        try {
            jdbc.update("""
                    INSERT INTO gacha_presentation_session
                    (session_id, player_id, banner_type, times, state, client_nonce)
                    VALUES (?, ?, ?, ?, 'STARTED', ?)
                    """, sid, playerId, bannerType, times, clientNonce == null ? "" : clientNonce);
        } catch (Exception ignored) {
            // 表未就绪时仅用内存会话
        }
        return new StartResult(true, 0, session);
    }

    private Session findOpen(int playerId) {
        for (Session s : memory.values()) {
            if (s.playerId() == playerId && s.state() != State.ENDED) {
                return s;
            }
        }
        return null;
    }

    public Session markDrawing(String sessionId, int playerId) {
        Session s = memory.get(sessionId);
        if (s == null || s.playerId() != playerId || s.state() != State.STARTED) {
            return null;
        }
        Session next = new Session(s.sessionId(), s.playerId(), s.bannerType(), s.times(),
                State.DRAWING, s.ui());
        memory.put(sessionId, next);
        updateState(sessionId, "DRAWING");
        return next;
    }

    @Transactional
    public boolean ack(String sessionId, int playerId, boolean skipped) {
        Session s = memory.get(sessionId);
        if (s == null) {
            // 已关闭或不存在：幂等视为成功（动画结束重复 ACK）
            return true;
        }
        if (s.playerId() != playerId) {
            return false;
        }
        if (s.state() == State.ENDED) {
            return true;
        }
        memory.put(sessionId, new Session(s.sessionId(), s.playerId(), s.bannerType(), s.times(),
                State.ENDED, s.ui()));
        try {
            jdbc.update("""
                    UPDATE gacha_presentation_session SET state='ENDED', closed_at=NOW(3)
                    WHERE session_id=? AND player_id=?
                    """, sessionId, playerId);
        } catch (Exception ignored) {
        }
        memory.remove(sessionId);
        return true;
    }

    public ClientUiParams resolveUi(int bannerType, int times) {
        String fx = times >= 10 ? "gacha_ten_pull" : "gacha_single_pull";
        String camera = bannerType >= 11 ? "up_banner_closeup" : "normal_banner";
        return new ClientUiParams(fx, camera,
                Map.of("times", String.valueOf(times), "bannerType", String.valueOf(bannerType)),
                "sfx_gacha_roll", "timeline_gacha_v1");
    }

    private void updateState(String sessionId, String state) {
        try {
            jdbc.update("UPDATE gacha_presentation_session SET state=? WHERE session_id=?", state, sessionId);
        } catch (Exception ignored) {
        }
    }
}
