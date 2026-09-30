package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QuestConfigRepository 任务配置仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestConfigRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("QuestConfigRepository 任务配置仓储测试")
class QuestConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(QuestConfigRepositoryTest.class);

    private ConfigFileService configFileService;
    private QuestConfigRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        configFileService = mock(ConfigFileService.class);
        repository = new QuestConfigRepository(configFileService);

        QuestConfigRepository.QuestConfig q1 = QuestTestFixtures.questConfig(10001);
        QuestConfigRepository.QuestConfig q2 = new QuestConfigRepository.QuestConfig(
                10002, "清理威胁", "触发一次战斗",
                List.of(QuestTestFixtures.objective(1, 4, 1, 1)),
                List.of(QuestTestFixtures.currencyReward(1, 300)));
        stubConfigs(List.of(q1, q2));
        repository.reload();
        log.info("任务配置加载: questCount=2, firstQuestId={}, firstTitle={}, firstRewardCurrencyAmount={}",
                q1.questId(), q1.title(), q1.rewards().get(0).amount());
    }

    @SuppressWarnings("unchecked")
    private void stubConfigs(List<QuestConfigRepository.QuestConfig> configs) throws Exception {
        when(configFileService.readJsonList(eq("QuestConfigs.json"), any(TypeReference.class)))
                .thenReturn(configs);
    }

    /**
     * 验证点：find 应按 questId 返回配置。
     * <p>测试方法 {@code findShouldReturnConfigById}：
     * <ul>
     *   <li>{@code assertNotNull(found);}</li>
     *   <li>{@code assertEquals("测试任务", found.title());}</li>
     *   <li>{@code assertEquals(2, found.objectives().size());}</li>
     *   <li>{@code assertEquals(2, found.rewards().size());}</li>
     *   <li>{@code assertNull(missing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("find 应按 questId 返回配置")
    void findShouldReturnConfigById() {
        QuestConfigRepository.QuestConfig found = repository.find(10001);
        QuestConfigRepository.QuestConfig missing = repository.find(99999);

        log.info("任务配置查询校验: questId=10001 found={}, title={}, objectiveCount={}, rewardCount={}, missingQuestId=99999 isNull={}",
                found != null,
                found != null ? found.title() : null,
                found != null ? found.objectives().size() : 0,
                found != null ? found.rewards().size() : 0,
                missing == null);
        assertNotNull(found);
        assertEquals("测试任务", found.title());
        assertEquals(2, found.objectives().size());
        assertEquals(2, found.rewards().size());
        assertNull(missing);
    }

    /**
     * 验证点：listAll 应返回全部任务配置。
     * <p>测试方法 {@code listAllShouldReturnAllConfigs}：
     * <ul>
     *   <li>{@code assertEquals(2, all.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("listAll 应返回全部任务配置")
    void listAllShouldReturnAllConfigs() {
        List<QuestConfigRepository.QuestConfig> all = repository.listAll();

        log.info("任务全量列表校验: size={}, contains10001={}, contains10002={}",
                all.size(),
                all.stream().anyMatch(c -> c.questId() == 10001),
                all.stream().anyMatch(c -> c.questId() == 10002));
        assertEquals(2, all.size());
    }

    /**
     * 验证点：reload 应整体替换配置索引。
     * <p>测试方法 {@code reloadShouldReplaceIndex}：
     * <ul>
     *   <li>{@code assertNull(oldGone);}</li>
     *   <li>{@code assertNotNull(loaded);}</li>
     *   <li>{@code assertEquals("新任务", loaded.title());}</li>
     *   <li>{@code assertEquals(1, loaded.objectives().get(0).targetType());}</li>
     *   <li>{@code assertEquals(23001, loaded.rewards().get(0).itemId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("reload 应整体替换配置索引")
    void reloadShouldReplaceIndex() throws Exception {
        QuestConfigRepository.QuestConfig only = new QuestConfigRepository.QuestConfig(
                20001, "新任务", "热更后",
                List.of(QuestTestFixtures.objective(1, 1, 101, 3)),
                List.of(QuestTestFixtures.itemReward(23001, 1)));
        stubConfigs(List.of(only));
        repository.reload();

        QuestConfigRepository.QuestConfig oldGone = repository.find(10001);
        QuestConfigRepository.QuestConfig loaded = repository.find(20001);

        log.info("热更替换校验: oldQuestId=10001 present={}, newQuestId=20001 present={}, title={}, objectiveTargetType={}, itemId={}",
                oldGone != null,
                loaded != null,
                loaded != null ? loaded.title() : null,
                loaded != null ? loaded.objectives().get(0).targetType() : -1,
                loaded != null ? loaded.rewards().get(0).itemId() : null);
        assertNull(oldGone);
        assertNotNull(loaded);
        assertEquals("新任务", loaded.title());
        assertEquals(1, loaded.objectives().get(0).targetType());
        assertEquals(23001, loaded.rewards().get(0).itemId());
    }
}
