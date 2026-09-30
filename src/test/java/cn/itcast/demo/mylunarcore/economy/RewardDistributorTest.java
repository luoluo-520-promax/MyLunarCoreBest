package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RewardDistributor 奖励分发器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code RewardDistributorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("RewardDistributor 奖励分发器测试")
class RewardDistributorTest {

    private static final Logger log = LoggerFactory.getLogger(RewardDistributorTest.class);

    private static final int PLAYER_ID = EconomyTestFixtures.PLAYER_ID;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WalletApplicationService walletApplicationService;
    private ItemRepository itemRepository;
    private PlayerAggregateService playerAggregateService;
    private RewardDistributor distributor;

    @BeforeEach
    void setUp() {
        walletApplicationService = mock(WalletApplicationService.class);
        itemRepository = mock(ItemRepository.class);
        playerAggregateService = mock(PlayerAggregateService.class);
        when(playerAggregateService.commit(anyLong(), any(Function.class))).thenReturn(null);
        distributor = new RewardDistributor(walletApplicationService, itemRepository, playerAggregateService);
        log.info("奖励分发器初始化: playerId={}", PLAYER_ID);
    }

    /**
     * 验证点：任务奖励应发放货币与道具。
     * <p>测试方法 {@code grantQuestRewardsShouldDistributeCurrencyAndItems}：
     * <ul>
     *   <li>{@code when(walletApplicationService.add(PLAYER_ID, 1, 100, "quest:2001"))}</li>
     *   <li>{@code assertTrue(ok);}</li>
     *   <li>{@code verify(walletApplicationService).add(PLAYER_ID, 1, 100, "quest:2001");}</li>
     *   <li>{@code verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 3L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("任务奖励应发放货币与道具")
    void grantQuestRewardsShouldDistributeCurrencyAndItems() {
        QuestConfigRepository.QuestConfig config = new QuestConfigRepository.QuestConfig(
                2001, "首通", "完成首通奖励",
                List.of(),
                List.of(
                        new QuestConfigRepository.RewardConfig(1, 100, null, null),
                        new QuestConfigRepository.RewardConfig(null, null, 101, 3)));
        when(walletApplicationService.add(PLAYER_ID, 1, 100, "quest:2001"))
                .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 600), "quest:2001"));

        boolean ok = distributor.grantQuestRewards(PLAYER_ID, config);

        log.info("任务发奖校验: questId=2001, success={}, currencyId=1 amount=100, itemId=101 count=3",
                ok);
        assertTrue(ok);
        verify(walletApplicationService).add(PLAYER_ID, 1, 100, "quest:2001");
        verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 3L);
    }

    /**
     * 验证点：空奖励列表应视为成功。
     * <p>测试方法 {@code grantQuestRewardsWithNullRewardsShouldSucceed}：
     * <ul>
     *   <li>{@code assertTrue(ok);}</li>
     *   <li>{@code verify(walletApplicationService, never()).add(anyInt(), anyInt(), anyInt(), anyString());}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("空奖励列表应视为成功")
    void grantQuestRewardsWithNullRewardsShouldSucceed() {
        QuestConfigRepository.QuestConfig config = new QuestConfigRepository.QuestConfig(
                2002, "空奖", "无奖励", List.of(), null);

        boolean ok = distributor.grantQuestRewards(PLAYER_ID, config);

        log.info("空奖励校验: questId=2002, success={}, rewardsNull=true", ok);
        assertTrue(ok);
        verify(walletApplicationService, never()).add(anyInt(), anyInt(), anyInt(), anyString());
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }

    /**
     * 验证点：货币加款失败应中止任务发奖。
     * <p>测试方法 {@code grantQuestRewardsShouldStopWhenWalletFails}：
     * <ul>
     *   <li>{@code when(walletApplicationService.add(PLAYER_ID, 1, 10, "quest:2003"))}</li>
     *   <li>{@code assertFalse(ok);}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("货币加款失败应中止任务发奖")
    void grantQuestRewardsShouldStopWhenWalletFails() {
        QuestConfigRepository.QuestConfig config = new QuestConfigRepository.QuestConfig(
                2003, "溢出", "货币溢出",
                List.of(),
                List.of(
                        new QuestConfigRepository.RewardConfig(1, 10, null, null),
                        new QuestConfigRepository.RewardConfig(null, null, 101, 1)));
        when(walletApplicationService.add(PLAYER_ID, 1, 10, "quest:2003"))
                .thenReturn(new WalletApplicationService.WalletChangeResult(false, Map.of(1, Integer.MAX_VALUE), "quest:2003"));

        boolean ok = distributor.grantQuestRewards(PLAYER_ID, config);

        log.info("发奖中止校验: questId=2003, success={}, walletAddSuccess=false, itemGranted={}",
                ok, false);
        assertFalse(ok);
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }

    /**
     * 验证点：邮件附件应发放货币与道具。
     * <p>测试方法 {@code grantMailAttachmentsShouldDistribute}：
     * <ul>
     *   <li>{@code when(walletApplicationService.add(eq(PLAYER_ID), eq(1), eq(50), eq("mail")))}</li>
     *   <li>{@code assertTrue(ok);}</li>
     *   <li>{@code verify(walletApplicationService).add(PLAYER_ID, 1, 50, "mail");}</li>
     *   <li>{@code verify(itemRepository).addSimpleItem(PLAYER_ID, 23001, 3, 2L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("邮件附件应发放货币与道具")
    void grantMailAttachmentsShouldDistribute() {
        List<Map<String, Object>> attachments = List.of(
                Map.of("currencyId", 1, "currencyAmount", 50),
                Map.of("itemId", 23001, "count", 2));
        when(walletApplicationService.add(eq(PLAYER_ID), eq(1), eq(50), eq("mail")))
                .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 550), "mail"));

        boolean ok = distributor.grantMailAttachments(PLAYER_ID, attachments);

        log.info("邮件附件校验: success={}, currencyId=1 currencyAmount=50, itemId=23001 count=2, attachmentCount={}",
                ok, attachments.size());
        assertTrue(ok);
        verify(walletApplicationService).add(PLAYER_ID, 1, 50, "mail");
        verify(itemRepository).addSimpleItem(PLAYER_ID, 23001, 3, 2L);
    }

    /**
     * 验证点：空附件应视为成功。
     * <p>测试方法 {@code grantMailAttachmentsEmptyShouldSucceed}：
     * <ul>
     *   <li>{@code assertTrue(nullOk);}</li>
     *   <li>{@code assertTrue(emptyOk);}</li>
     * </ul>
     */
    @Test
    @DisplayName("空附件应视为成功")
    void grantMailAttachmentsEmptyShouldSucceed() {
        boolean nullOk = distributor.grantMailAttachments(PLAYER_ID, null);
        boolean emptyOk = distributor.grantMailAttachments(PLAYER_ID, List.of());

        log.info("空附件校验: nullAttachmentsSuccess={}, emptyAttachmentsSuccess={}",
                nullOk, emptyOk);
        assertTrue(nullOk);
        assertTrue(emptyOk);
    }

    /**
     * 验证点：parseAttachmentsJson 应解析合法 JSON 并容错非法输入。
     * <p>测试方法 {@code parseAttachmentsJsonShouldParseAndTolerateInvalid}：
     * <ul>
     *   <li>{@code assertEquals(2, parsed.size());}</li>
     *   <li>{@code assertEquals(1, ((Number) parsed.get(0).get("currencyId")).intValue());}</li>
     *   <li>{@code assertEquals(0, blank.size());}</li>
     *   <li>{@code assertEquals(0, invalid.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("parseAttachmentsJson 应解析合法 JSON 并容错非法输入")
    void parseAttachmentsJsonShouldParseAndTolerateInvalid() {
        String json = "[{\"currencyId\":1,\"currencyAmount\":20},{\"itemId\":101,\"count\":5}]";
        List<Map<String, Object>> parsed = RewardDistributor.parseAttachmentsJson(json, MAPPER);
        List<Map<String, Object>> blank = RewardDistributor.parseAttachmentsJson("  ", MAPPER);
        List<Map<String, Object>> invalid = RewardDistributor.parseAttachmentsJson("{bad", MAPPER);

        log.info("附件JSON解析校验: validCount={}, firstCurrencyId={}, blankSize={}, invalidSize={}",
                parsed.size(),
                parsed.get(0).get("currencyId"),
                blank.size(),
                invalid.size());
        assertEquals(2, parsed.size());
        assertEquals(1, ((Number) parsed.get(0).get("currencyId")).intValue());
        assertEquals(0, blank.size());
        assertEquals(0, invalid.size());
    }
}
