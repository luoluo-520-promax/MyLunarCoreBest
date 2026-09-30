// 任务进度应用层：接受、触发同步、提交发奖、放弃任务的状态机编排
package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.economy.RewardDistributor; // 任务奖励统一走货币加款与道具入库
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity; // 任务进度实体：status、objectivesJson
import cn.itcast.demo.mylunarcore.repo.QuestProgressRepository; // quest_progress 表读写
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务进度应用服务。
 * 状态约定：0 未接/可接，1 进行中，2 已完成待提交，3 已提交，4 已放弃。
 */
@Service
public class QuestProgressApplicationService {

    /** 接受任务结果：success + retcode。 */
    public record AcceptResult(boolean success, int retcode) {}

    /** 提交任务结果：success + retcode。 */
    public record SubmitResult(boolean success, int retcode) {}

    /** 放弃任务结果：success + retcode。 */
    public record AbandonResult(boolean success, int retcode) {}

    /** 任务列表视图预留结构（当前 list 直接返回 Entity）。 */
    public record QuestView(int questId, int state, String progress) {}

    /** 任务进度持久化。 */
    private final QuestProgressRepository questProgressRepository;
    /** 任务静态配置（奖励、目标定义）。 */
    private final QuestConfigRepository questConfigRepository;
    /** 提交时发放货币与道具奖励。 */
    private final RewardDistributor rewardDistributor;
    /** 外部触发事件（杀怪等）推进进度。 */
    private final QuestTriggerEngine triggerEngine;

    /** 构造器注入四个依赖。 */
    public QuestProgressApplicationService(QuestProgressRepository questProgressRepository,
                                          QuestConfigRepository questConfigRepository,
                                          RewardDistributor rewardDistributor,
                                          QuestTriggerEngine triggerEngine) {
        this.questProgressRepository = questProgressRepository; // 任务进度读写：接受、提交、放弃、列表
        this.questConfigRepository = questConfigRepository; // 静态配置：奖励定义、目标校验
        this.rewardDistributor = rewardDistributor; // 提交成功时按 config.rewards 发货币与道具
        this.triggerEngine = triggerEngine; // 外部事件触发时推进 objectivesJson 进度
    }

    /** 列出玩家全部任务进度记录（含进行中、已完成未提交等）。 */
    public List<QuestProgressEntity> list(int playerId) {
        return questProgressRepository.list(playerId); // 按 playerId 查 quest_progress 全量
    }

    /**
     * 接受任务：写入 quest_progress，status 变为进行中(1)。
     * retcode：0 成功，2 配置 null，3 已接受且 status≠0。
     */
    @Transactional
    public AcceptResult accept(int playerId, QuestConfigRepository.QuestConfig config) {
        if (config == null) { // 调用方传入空配置（不应发生，防御性校验）
            return new AcceptResult(false, 2); // retcode=2：配置不存在
        }
        QuestProgressEntity current = questProgressRepository.find(playerId, config.questId()); // 查是否已有进度
        if (current != null && current.getStatus() != 0) { // status≠0 表示已接取或已完成，不可重复接
            return new AcceptResult(false, 3); // retcode=3：重复接取
        }
        questProgressRepository.ensureAccepted(playerId, config.questId()); // INSERT 或 UPDATE 为 status=1
        return new AcceptResult(true, 0); // 接取成功
    }

    /** 同步外部系统上报的触发事件，委托 TriggerEngine 更新 objectivesJson。 */
    @Transactional
    public void syncTrigger(int playerId, int triggerType, long p1, long p2, long p3) {
        triggerEngine.onTrigger(playerId, triggerType, p1, p2, p3); // 如杀怪：triggerType=1, p1=monsterId
    }

    /**
     * 提交已完成任务并发放奖励。
     * retcode：0 成功，2 配置不存在，3 非进行中，4 目标未完成，5 发奖失败。
     */
    @Transactional
    public SubmitResult submit(int playerId, int questId) {
        QuestConfigRepository.QuestConfig config = questConfigRepository.find(questId); // 读静态奖励配置
        if (config == null) {
            return new SubmitResult(false, 2); // 任务 ID 在 QuestConfigs.json 中不存在
        }
        QuestProgressEntity progress = questProgressRepository.find(playerId, questId); // 读玩家该任务进度
        if (progress == null || progress.getStatus() != 1) { // 必须 status=1（进行中）才允许提交
            return new SubmitResult(false, 3); // 未接取或状态不对
        }
        if (!questProgressRepository.isCompleted(playerId, questId)) { // 校验 objectivesJson 全部达标
            return new SubmitResult(false, 4); // 目标未完成
        }
        if (!rewardDistributor.grantQuestRewards(playerId, config)) { // 按 config.rewards 发货币和道具
            return new SubmitResult(false, 5); // 发奖写库失败，事务回滚
        }
        questProgressRepository.markSubmitted(playerId, questId); // status 置为已提交(3)
        return new SubmitResult(true, 0); // 提交成功
    }

    /**
     * 放弃进行中的任务，status 置为 4（已放弃）。
     * retcode：0 成功，2 任务不存在，3 非进行中不可放弃。
     */
    @Transactional
    public AbandonResult abandon(int playerId, int questId) {
        QuestProgressEntity progress = questProgressRepository.find(playerId, questId);
        if (progress == null) { // 无进度记录
            return new AbandonResult(false, 2);
        }
        if (progress.getStatus() != 1) { // 只有进行中(1)可放弃；已完成/已提交不可放弃
            return new AbandonResult(false, 3);
        }
        questProgressRepository.update(playerId, questId, 4, progress.getObjectivesJson()); // status=4，进度 JSON 保留
        return new AbandonResult(true, 0);
    }
}
