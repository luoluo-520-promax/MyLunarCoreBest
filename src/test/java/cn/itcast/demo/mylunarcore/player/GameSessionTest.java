package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GameSession 在线会话测试")
class GameSessionTest {

    private static final Logger log = LoggerFactory.getLogger(GameSessionTest.class);

    private Channel channel;
    private GameSession session;

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class);
        when(channel.writeAndFlush(any())).thenReturn(null);
        session = new GameSession(PlayerTestFixtures.PLAYER_UID, channel, null);
        log.info("会话初始化: uid={}, ukcpPresent={}",
                session.getUid(), session.getUkcp() != null);
    }

    @Test
    @DisplayName("构造后应绑定 uid 与 Channel")
    void constructorShouldBindUidAndChannel() {
        assertEquals(PlayerTestFixtures.PLAYER_UID, session.getUid());
        assertEquals(channel, session.getChannel());
        log.info("构造校验: uid={}, channelBound={}, ukcpNull={}",
                session.getUid(), session.getChannel() == channel, session.getUkcp() == null);
    }

    @Test
    @DisplayName("nextDataLoadVersion 应原子递增")
    void nextDataLoadVersionShouldIncrement() {
        long v1 = session.nextDataLoadVersion();
        long v2 = session.nextDataLoadVersion();
        boolean currentV1 = session.isCurrentDataLoadVersion(v1);
        boolean currentV2 = session.isCurrentDataLoadVersion(v2);

        log.info("加载版本校验: v1={}, v2={}, currentV1={}, currentV2={}", v1, v2, currentV1, currentV2);
        assertEquals(1L, v1);
        assertEquals(2L, v2);
        assertFalse(currentV1);
        assertTrue(currentV2);
    }

    @Test
    @DisplayName("send 应通过 Channel 写出 GamePacket")
    void sendShouldWritePacketToChannel() {
        GamePacket packet = new GamePacket(CmdIds.PLAYER_SYNC_SC_NOTIFY, new byte[]{1, 2});
        session.send(packet);

        log.info("发包校验: cmdId={}, payloadLen={}", packet.getCmdId(), packet.getPayload().length);
        verify(channel).writeAndFlush(packet);
    }

    @Test
    @DisplayName("会话快照字段应可读写")
    void sessionSnapshotFieldsShouldBeMutable() {
        PlayerData data = PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID);
        session.setNickname(PlayerTestFixtures.NICKNAME);
        session.setLevel(45);
        session.setSessionToken("token-abc");
        session.setLastActiveMillis(1_700_000_000L);
        session.setPlayerData(data);

        log.info("快照字段校验: nickname={}, level={}, token={}, lastActive={}, playerUid={}",
                session.getNickname(), session.getLevel(), session.getSessionToken(),
                session.getLastActiveMillis(), session.getPlayerData().getPlayer().getUid());
        assertEquals(PlayerTestFixtures.NICKNAME, session.getNickname());
        assertEquals(45, session.getLevel());
        assertEquals("token-abc", session.getSessionToken());
        assertEquals(1_700_000_000L, session.getLastActiveMillis());
        assertEquals(PlayerTestFixtures.PLAYER_UID, session.getPlayerData().getPlayer().getUid());
    }
}
