package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.QuestSystemProto;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestNettyService 任务协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("QuestNettyService 任务协议服务测试")
class QuestNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QuestNettyServiceTest.class);

    private static final int PLAYER_ID = QuestTestFixtures.PLAYER_ID;
    private static final long PLAYER_UID = QuestTestFixtures.PLAYER_UID;
    private static final int QUEST_ID = QuestTestFixtures.QUEST_ID;

    private QuestProgressApplicationService questProgressApplicationService;
    private QuestConfigRepository questConfigRepository;
    private PlayerContextResolver contextResolver;
    private QuestNettyService service;

    @BeforeEach
    void setUp() {
        questProgressApplicationService = mock(QuestProgressApplicationService.class);
        questConfigRepository = mock(QuestConfigRepository.class);
        contextResolver = mock(PlayerContextResolver.class);
        service = new QuestNettyService(
                questProgressApplicationService, questConfigRepository, contextResolver);
        log.info("任务协议服务初始化: playerId={}, uid={}, questId={}", PLAYER_ID, PLAYER_UID, QUEST_ID);
    }

    /**
     * 验证点：GetQuestList 成功应返回任务列表。
     * <p>测试方法 {@code handleGetQuestListShouldReturnQuests}：
     * <ul>
     *   <li>{@code when(questProgressApplicationService.list(PLAYER_ID)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(2, rsp.getQuestsCount());}</li>
     *   <li>{@code assertEquals(QUEST_ID, rsp.getQuests(0).getQuestId());}</li>
     *   <li>{@code assertEquals(1, rsp.getQuests(0).getStatus());}</li>
     *   <li>{@code assertEquals(3, rsp.getQuests(1).getStatus());}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetQuestList 成功应返回任务列表")
    void handleGetQuestListShouldReturnQuests() {
        when(questProgressApplicationService.list(PLAYER_ID)).thenReturn(List.of(
                QuestTestFixtures.progress(QUEST_ID, 1, "{}"),
                QuestTestFixtures.progress(10002, 3, "{}")));

        QuestSystemProto.GetQuestListScRsp rsp = service.handleGetQuestList(
                QuestSystemProto.GetQuestListCsReq.newBuilder().build(),
                loggedInChannel());

        log.info("任务列表协议校验: retcode={}, questCount={}, firstQuestId={}, firstStatus={}, secondStatus={}",
                rsp.getRetcode(), rsp.getQuestsCount(),
                rsp.getQuests(0).getQuestId(), rsp.getQuests(0).getStatus(),
                rsp.getQuests(1).getStatus());
        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getQuestsCount());
        assertEquals(QUEST_ID, rsp.getQuests(0).getQuestId());
        assertEquals(1, rsp.getQuests(0).getStatus());
        assertEquals(3, rsp.getQuests(1).getStatus());
    }

    /**
     * 验证点：未登录查询任务列表应返回 retcode=1。
     * <p>测试方法 {@code handleGetQuestListWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(questProgressApplicationService, never()).list(anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录查询任务列表应返回 retcode=1")
    void handleGetQuestListWithoutLoginShouldFail() {
        QuestSystemProto.GetQuestListScRsp rsp = service.handleGetQuestList(
                QuestSystemProto.GetQuestListCsReq.newBuilder().build(),
                loggedOutChannel());

        log.info("未登录列表校验: retcode={}, questCount={}", rsp.getRetcode(), rsp.getQuestsCount());
        assertEquals(1, rsp.getRetcode());
        verify(questProgressApplicationService, never()).list(anyInt());
    }

    /**
     * 验证点：AcceptQuest 配置不存在应返回 retcode=2。
     * <p>测试方法 {@code handleAcceptQuestShouldFailWhenConfigMissing}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     *   <li>{@code verify(questProgressApplicationService, never()).accept(anyInt(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AcceptQuest 配置不存在应返回 retcode=2")
    void handleAcceptQuestShouldFailWhenConfigMissing() {
        when(questConfigRepository.find(QUEST_ID)).thenReturn(null);

        QuestSystemProto.AcceptQuestScRsp rsp = service.handleAcceptQuest(
                QuestSystemProto.AcceptQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("接取缺配置协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
        verify(questProgressApplicationService, never()).accept(anyInt(), any());
    }

    /**
     * 验证点：AcceptQuest 成功应返回 retcode=0。
     * <p>测试方法 {@code handleAcceptQuestShouldSucceed}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(config);}</li>
     *   <li>{@code when(questProgressApplicationService.accept(PLAYER_ID, config))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AcceptQuest 成功应返回 retcode=0")
    void handleAcceptQuestShouldSucceed() {
        QuestConfigRepository.QuestConfig config = QuestTestFixtures.questConfig(QUEST_ID);
        when(questConfigRepository.find(QUEST_ID)).thenReturn(config);
        when(questProgressApplicationService.accept(PLAYER_ID, config))
                .thenReturn(new QuestProgressApplicationService.AcceptResult(true, 0));

        QuestSystemProto.AcceptQuestScRsp rsp = service.handleAcceptQuest(
                QuestSystemProto.AcceptQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("接取成功协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(0, rsp.getRetcode());
    }

    /**
     * 验证点：AcceptQuest 业务失败应透传 retcode。
     * <p>测试方法 {@code handleAcceptQuestShouldPassBusinessRetcode}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(config);}</li>
     *   <li>{@code when(questProgressApplicationService.accept(PLAYER_ID, config))}</li>
     *   <li>{@code assertEquals(3, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AcceptQuest 业务失败应透传 retcode")
    void handleAcceptQuestShouldPassBusinessRetcode() {
        QuestConfigRepository.QuestConfig config = QuestTestFixtures.questConfig(QUEST_ID);
        when(questConfigRepository.find(QUEST_ID)).thenReturn(config);
        when(questProgressApplicationService.accept(PLAYER_ID, config))
                .thenReturn(new QuestProgressApplicationService.AcceptResult(false, 3));

        QuestSystemProto.AcceptQuestScRsp rsp = service.handleAcceptQuest(
                QuestSystemProto.AcceptQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("重复接取协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
    }

    /**
     * 验证点：SubmitQuest 成功应由 commit 推送，协议层不再二次 sync。
     * <p>测试方法 {@code handleSubmitQuestShouldSucceedWithoutProtocolNotify}：
     * <ul>
     *   <li>{@code when(questProgressApplicationService.submit(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("SubmitQuest 成功应由 commit 推送，协议层不再二次 sync")
    void handleSubmitQuestShouldSucceedWithoutProtocolNotify() {
        when(questProgressApplicationService.submit(PLAYER_ID, QUEST_ID))
                .thenReturn(new QuestProgressApplicationService.SubmitResult(true, 0));

        QuestSystemProto.SubmitQuestScRsp rsp = service.handleSubmitQuest(
                QuestSystemProto.SubmitQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("提交成功协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(0, rsp.getRetcode());
    }

    /**
     * 验证点：SubmitQuest 失败应返回业务 retcode。
     * <p>测试方法 {@code handleSubmitQuestShouldNotNotifyOnFailure}：
     * <ul>
     *   <li>{@code when(questProgressApplicationService.submit(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertEquals(4, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("SubmitQuest 失败应返回业务 retcode")
    void handleSubmitQuestShouldNotNotifyOnFailure() {
        when(questProgressApplicationService.submit(PLAYER_ID, QUEST_ID))
                .thenReturn(new QuestProgressApplicationService.SubmitResult(false, 4));

        QuestSystemProto.SubmitQuestScRsp rsp = service.handleSubmitQuest(
                QuestSystemProto.SubmitQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("提交失败协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(4, rsp.getRetcode());
    }

    /**
     * 验证点：未登录提交应返回 retcode=1。
     * <p>测试方法 {@code handleSubmitQuestWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(questProgressApplicationService, never()).submit(anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录提交应返回 retcode=1")
    void handleSubmitQuestWithoutLoginShouldFail() {
        QuestSystemProto.SubmitQuestScRsp rsp = service.handleSubmitQuest(
                QuestSystemProto.SubmitQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedOutChannel());

        log.info("未登录提交校验: retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
        verify(questProgressApplicationService, never()).submit(anyInt(), anyInt());
    }

    /**
     * 验证点：AbandonQuest 成功应回显 questId。
     * <p>测试方法 {@code handleAbandonQuestShouldSucceed}：
     * <ul>
     *   <li>{@code when(questProgressApplicationService.abandon(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(QUEST_ID, rsp.getQuestId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AbandonQuest 成功应回显 questId")
    void handleAbandonQuestShouldSucceed() {
        when(questProgressApplicationService.abandon(PLAYER_ID, QUEST_ID))
                .thenReturn(new QuestProgressApplicationService.AbandonResult(true, 0));

        QuestSystemProto.AbandonQuestScRsp rsp = service.handleAbandonQuest(
                QuestSystemProto.AbandonQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("放弃成功协议校验: retcode={}, questId={}", rsp.getRetcode(), rsp.getQuestId());
        assertEquals(0, rsp.getRetcode());
        assertEquals(QUEST_ID, rsp.getQuestId());
    }

    /**
     * 验证点：AbandonQuest 业务失败应透传 retcode。
     * <p>测试方法 {@code handleAbandonQuestShouldPassBusinessRetcode}：
     * <ul>
     *   <li>{@code when(questProgressApplicationService.abandon(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertEquals(3, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(QUEST_ID, rsp.getQuestId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AbandonQuest 业务失败应透传 retcode")
    void handleAbandonQuestShouldPassBusinessRetcode() {
        when(questProgressApplicationService.abandon(PLAYER_ID, QUEST_ID))
                .thenReturn(new QuestProgressApplicationService.AbandonResult(false, 3));

        QuestSystemProto.AbandonQuestScRsp rsp = service.handleAbandonQuest(
                QuestSystemProto.AbandonQuestCsReq.newBuilder().setQuestId(QUEST_ID).build(),
                loggedInChannel());

        log.info("放弃失败协议校验: questId={}, retcode={}", QUEST_ID, rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
        assertEquals(QUEST_ID, rsp.getQuestId());
    }

    private Channel loggedInChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(PLAYER_ID);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.of(PLAYER_UID));
        log.info("模拟登录 Channel: uid={}, playerId={}", PLAYER_UID, PLAYER_ID);
        return channel;
    }

    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());
        log.info("模拟未登录 Channel: playerId=0");
        return channel;
    }
}
