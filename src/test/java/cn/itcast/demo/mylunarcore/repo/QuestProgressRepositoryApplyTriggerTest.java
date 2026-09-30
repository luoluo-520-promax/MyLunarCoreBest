package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository.ObjectiveConfig;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository.QuestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
/**
 * QuestProgressRepository.applyTrigger。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestProgressRepositoryApplyTriggerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("QuestProgressRepository.applyTrigger")
class QuestProgressRepositoryApplyTriggerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private QuestConfigRepository questConfigRepository;

    private QuestProgressRepository repository;

    @BeforeEach
    void setUp() {
        repository = new QuestProgressRepository(jdbcTemplate, questConfigRepository);
    }

    /**
     * 验证点：杀怪触发应递增 objectives 并在达标后置 status=2。
     * <p>测试方法 {@code monsterKillShouldCompleteObjective}：
     * <ul>
     *   <li>{@code when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1)))}</li>
     *   <li>{@code when(questConfigRepository.find(10001)).thenReturn(new QuestConfig(}</li>
     *   <li>{@code verify(jdbcTemplate, atLeastOnce()).update(}</li>
     *   <li>{@code assertEquals(2, statusCap.getValue());}</li>
     *   <li>{@code assertTrue(jsonCap.getValue().contains("\"1\":1") || jsonCap.getValue().contains("\"1\": 1"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("杀怪触发应递增 objectives 并在达标后置 status=2")
    void monsterKillShouldCompleteObjective() {
        QuestProgressEntity progress = new QuestProgressEntity();
        progress.setPlayerId(1);
        progress.setQuestId(10001);
        progress.setStatus(1);
        progress.setObjectivesJson("{\"1\":0}");

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(1)))
                .thenReturn(List.of(progress));
        when(questConfigRepository.find(10001)).thenReturn(new QuestConfig(
                10001, "t", "d",
                List.of(new ObjectiveConfig(1, 1, 101, 1)),
                List.of()));

        repository.applyTrigger(1, 1, 101, 0, 0);

        ArgumentCaptor<Integer> statusCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).update(
                anyString(), statusCap.capture(), jsonCap.capture(), eq(1), eq(10001));
        assertEquals(2, statusCap.getValue());
        assertTrue(jsonCap.getValue().contains("\"1\":1") || jsonCap.getValue().contains("\"1\": 1"));
    }
}
