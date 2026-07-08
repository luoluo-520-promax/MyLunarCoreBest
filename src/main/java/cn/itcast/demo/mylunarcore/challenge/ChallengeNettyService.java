// 挑战玩法 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.challenge;

// 战斗关卡波次配置（复用于组装 EnemyInfo）
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
// 挑战组奖励实体
import cn.itcast.demo.mylunarcore.model.ChallengeGroupRewardEntity;
// 挑战历史实体
import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
// 挑战组奖励仓储
import cn.itcast.demo.mylunarcore.repo.ChallengeGroupRewardRepository;
// 挑战历史仓储
import cn.itcast.demo.mylunarcore.repo.ChallengeHistoryRepository;
// 运行时管理器（分配/缓存 ChallengeRuntime）
import cn.itcast.demo.mylunarcore.challenge.ChallengeManager;
// 单次挑战内存状态
import cn.itcast.demo.mylunarcore.challenge.ChallengeRuntime;
// 战斗系统 Protobuf（EnemyInfo）
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
// 挑战系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.ChallengeSystemProto;
// Netty 客户端连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Channel 自定义属性键
import io.netty.util.AttributeKey;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring @Service 业务 Bean
import org.springframework.stereotype.Service;

// 可变数组列表
import java.util.ArrayList;
// 不可变空集合工厂
import java.util.Collections;
// 列表接口
import java.util.List;
// Optional 容器
import java.util.Optional;

/**
 * 挑战玩法 Netty 业务：开战、选波、结算与奖励，协调 {@link ChallengeManager}、战斗波次配置与历史仓储。
 */
@Service // Spring Bean：挑战玩法 Netty 消息分发入口
public class ChallengeNettyService {

    // 本类日志记录器（挑战业务分类）
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_CHALLENGE, ChallengeNettyService.class); // 绑定挑战业务分类 SLF4J 日志

    // Channel 上绑定玩家 uid 的属性键
    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid"); // 定义 Channel 属性键名 playerUid

    // 运行时索引：challengeUid ↔ ChallengeRuntime
    private final ChallengeManager challengeManager; // 挑战运行时索引 challengeUid→状态
    // 关卡波次配置加载（EnemyInfo）
    private final BattleMonsterWaveRepository waveRepository; // 关卡怪物波次配置仓储
    // 挑战历史持久化
    private final ChallengeHistoryRepository historyRepository; // 挑战历史最佳成绩仓储
    // 挑战组领奖进度持久化
    private final ChallengeGroupRewardRepository groupRewardRepository; // 挑战组星级领奖掩码仓储

    /**
     * 构造器注入挑战相关依赖。
     */
    public ChallengeNettyService(ChallengeManager challengeManager, BattleMonsterWaveRepository waveRepository,
                                 ChallengeHistoryRepository historyRepository,
                                 ChallengeGroupRewardRepository groupRewardRepository) {
        this.challengeManager = challengeManager; // 保存运行时管理器引用
        this.waveRepository = waveRepository; // 保存波次配置仓储引用
        this.historyRepository = historyRepository; // 保存挑战历史仓储引用
        this.groupRewardRepository = groupRewardRepository; // 保存组奖励仓储引用
    }

    /**
     * 开始挑战：加载波次、分配 challengeUid 并返回关卡信息与敌方占位列表。
     */
    public ChallengeSystemProto.StartChallengeScRsp handleStartChallenge(ChallengeSystemProto.StartChallengeCsReq req, Channel channel) { // 处理开始挑战请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法开战
            return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        int challengeType = (int) req.getChallengeType(); // 读取挑战类型枚举值
        int challengeId = (int) req.getChallengeId(); // 读取挑战关卡配置 ID
        int lineupId = (int) req.getLineupId(); // 读取出战阵容 ID
        if (challengeType <= 0 || challengeId <= 0 || lineupId <= 0) { // 任一关键参数非正则非法
            return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(2).build(); // retcode=2：参数非法
        }

        int groupId = deriveGroupId(challengeId); // 由 challengeId 推导挑战组 ID
        int stageId = deriveStageId(challengeId); // 映射为加载波次用的 stageId
        long nowSeconds = System.currentTimeMillis() / 1000L; // 记录开战 Unix 时间戳（秒）

        List<BattleMonsterWaveRepository.WaveConfig> waves; // 声明波次配置列表，在 try 块内加载
        try { // 捕获波次配置加载异常，避免整局失败
            waves = waveRepository.loadWavesByStageId(stageId); // 按 stageId 从数据库加载怪物波次
        } catch (Exception e) { // 加载失败时记录日志并降级为空列表
            log.warn("loadWavesByStageId failed, stageId={}", stageId, e); // 记录波次加载异常便于排查
            waves = Collections.emptyList(); // 无波次配置时仍允许创建挑战实例
        }

        List<BattleSystemProto.EnemyInfo> enemyInfo = buildEnemyInfoFromWaves(waves); // 将波次配置转为协议 EnemyInfo 列表

        long challengeUid = challengeManager.nextUid(); // 分配全局唯一 challengeUid
        ChallengeRuntime runtime = new ChallengeRuntime( // 创建本局挑战内存状态对象
                challengeUid, // 挑战实例 UID
                playerId, // 所属玩家 ID
                challengeType, // 挑战类型
                challengeId, // 挑战关卡 ID
                groupId, // 挑战组 ID
                stageId, // 波次 stageId
                nowSeconds, // 开战时间戳（秒）
                Math.max(0, waves.size()), // 总波次数（无配置则为 0）
                enemyInfo // 各波敌方信息快照
        );
        challengeManager.put(runtime); // 注册运行时供后续查询与结算

        int roundsLeft = challengeType == 1 ? 5 : 0; // 类型 1 演示固定剩余 5 回合，其余类型为 0
        ChallengeSystemProto.ChallengeStageInfo stageInfo = ChallengeSystemProto.ChallengeStageInfo.newBuilder() // 组装关卡摘要
                .setStageId(stageId) // 写入映射后的 stageId
                .setWaveCount(runtime.getWaveCount()) // 写入总波次数
                .setRoundsLeft(roundsLeft) // 写入剩余回合数
                .build(); // 完成 ChallengeStageInfo 构建

        return ChallengeSystemProto.StartChallengeScRsp.newBuilder() // 组装开始挑战成功响应
                .setRetcode(0) // retcode=0：成功
                .setChallengeUid(challengeUid) // 回写挑战实例 UID
                .setChallengeType(challengeType) // 回写挑战类型
                .setChallengeId(challengeId) // 回写挑战关卡 ID
                .setStageInfo(stageInfo) // 写入关卡摘要
                .addAllEnemyInfo(enemyInfo) // 写入各波敌方列表
                .setStartTime(nowSeconds) // 写入开战时间戳
                .build(); // 完成响应构建
    }

    /**
     * 查询指定 challengeUid 的进行中详情。
     */
    public ChallengeSystemProto.GetChallengeInfoScRsp handleGetChallengeInfo(ChallengeSystemProto.GetChallengeInfoCsReq req, // 处理查询挑战详情请求
                                                                            Channel channel) { // Netty 连接通道
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法查询
            return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        long uid = req.getChallengeUid(); // 请求中的挑战实例 UID
        ChallengeRuntime runtime = challengeManager.get(uid); // 按 UID 查找进行中的挑战
        if (runtime == null || runtime.getPlayerId() != playerId) { // 挑战不存在或不属于当前玩家
            return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder() // 组装查询失败响应
                    .setRetcode(2) // retcode=2：挑战不存在或无权限
                    .setChallengeUid(uid) // 回写请求的 UID 便于客户端对齐
                    .build(); // 完成响应构建
        }

        return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder() // 组装查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setChallengeUid(uid) // 回写挑战实例 UID
                .setStatus(runtime.getStatus()) // 写入当前状态（进行中/胜/负）
                .setCurrentStage(runtime.getCurrentStage()) // 写入当前阶段序号
                .setRoundsUsed(runtime.getRoundsUsed()) // 写入已消耗回合数
                .setCurrentScore(runtime.getCurrentScore()) // 写入当前得分
                .setCurrentStars(runtime.getCurrentStarsMask()) // 写入当前星级位掩码
                .addAllEnemyInfo(runtime.getEnemyInfo()) // 写入各波敌方快照
                .build(); // 完成响应构建
    }

    /**
     * 上报挑战结算结果：刷新 DB 最佳记录并移除运行时。
     */
    public ChallengeSystemProto.ReportChallengeResultScRsp handleReportResult(ChallengeSystemProto.ReportChallengeResultCsReq req, // 处理上报挑战结果请求
                                                                             Channel channel) { // Netty 连接通道
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法上报
            return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }

        long uid = req.getChallengeUid(); // 请求中的挑战实例 UID
        ChallengeRuntime runtime = challengeManager.get(uid); // 按 UID 查找进行中的挑战
        if (runtime == null || runtime.getPlayerId() != playerId) { // 挑战不存在或不属于当前玩家
            return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder() // 组装上报失败响应
                    .setRetcode(2) // retcode=2：挑战不存在或无权限
                    .setChallengeUid(uid) // 回写请求的 UID
                    .build(); // 完成响应构建
        }

        boolean win = req.getIsWin(); // 客户端上报是否通关
        int finalScore = (int) req.getFinalScore(); // 本局最终得分
        int finalStars = (int) req.getFinalStars(); // 本局星级位掩码
        int roundsUsed = (int) req.getRoundsUsed(); // 本局已消耗回合数

        runtime.markSettled(win, finalScore, finalStars, roundsUsed); // 将结算结果写入运行时状态
        challengeManager.remove(uid); // 结算后从内存移除，防止重复上报

        boolean isNewRecord = false; // 默认非新纪录，胜利后再与历史比较
        int bestStars = finalStars; // 初始最佳星取本局结果
        int bestScore = Math.max(0, finalScore); // 初始最佳分取本局非负值

        if (win) { // 仅胜利时更新历史最佳成绩
            Optional<ChallengeHistoryEntity> old = historyRepository.findByPlayerAndChallenge(playerId, runtime.getChallengeId()); // 查库中该关卡历史最佳
            if (old.isPresent()) { // 存在历史记录则合并比较
                int oldStars = old.get().getStars(); // 历史星级位掩码
                int oldScore = old.get().getScore(); // 历史最高得分
                int mergedStars = oldStars | finalStars; // 星级位掩码按位或合并（保留历史达成星）
                int mergedScore = Math.max(oldScore, bestScore); // 得分取历史与本局较大值
                isNewRecord = mergedStars != oldStars || mergedScore != oldScore; // 星或分有提升则视为新纪录
                bestStars = mergedStars; // 更新合并后的最佳星级
                bestScore = mergedScore; // 更新合并后的最佳得分
            } else { // 无历史记录
                isNewRecord = true; // 首次通关即新纪录
            }

            try { // 持久化最佳成绩，失败则返回 retcode=3
                historyRepository.upsertBestResult(playerId, runtime.getChallengeId(), runtime.getGroupId(), bestStars, bestScore); // 写入合并后的最佳成绩
            } catch (Exception e) { // 数据库写入失败
                log.warn("upsertBestResult failed, playerId={}, challengeId={}", playerId, runtime.getChallengeId(), e); // 记录持久化异常
                return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder() // 组装持久化失败响应
                        .setRetcode(3) // retcode=3：数据库更新失败
                        .setChallengeUid(uid) // 回写挑战 UID
                        .build(); // 完成响应构建
            }
        }

        return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder() // 组装上报成功响应
                .setRetcode(0) // retcode=0：成功
                .setChallengeUid(uid) // 回写挑战 UID
                .setIsNewRecord(isNewRecord) // 告知是否刷新纪录
                .setBestStars(bestStars) // 回写合并后最佳星级
                .setBestScore(bestScore) // 回写合并后最佳得分
                .addAllRewards(Collections.emptyList()) // 演示实现：不发具体道具奖励
                .build(); // 完成响应构建
    }

    /**
     * 领取挑战组星级档位奖励（简化实现：更新掩码，不发具体道具）。
     */
    public ChallengeSystemProto.ClaimChallengeGroupRewardScRsp handleClaimGroupReward(ChallengeSystemProto.ClaimChallengeGroupRewardCsReq req, // 处理领取挑战组奖励请求
                                                                                     Channel channel) { // Netty 连接通道
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法领奖
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        int groupId = (int) req.getGroupId(); // 挑战组 ID
        int starCount = (int) req.getStarCount(); // 要领奖的星级档位（累计星数阈值）
        if (groupId <= 0 || starCount <= 0 || starCount > 31) { // 组 ID 或星级档位参数非法
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder() // 组装参数非法响应
                    .setRetcode(2) // retcode=2：参数非法
                    .setGroupId(groupId) // 回写组 ID
                    .setStarCount(starCount) // 回写星级档位
                    .build(); // 完成响应构建
        }

        int availableStars = computeAvailableStars(playerId, groupId); // 统计组内已获得的可用总星数
        if (availableStars < starCount) { // 可用星数不足无法领取该档
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder() // 组装星数不足响应
                    .setRetcode(3) // retcode=3：可用星数不足
                    .setGroupId(groupId) // 回写组 ID
                    .setStarCount(starCount) // 回写星级档位
                    .addAllRewards(Collections.emptyList()) // 演示不发具体道具奖励
                    .setUpdatedTakenStars(0) // 掩码未变更
                    .build(); // 完成响应构建
        }

        int claimMask = (1 << starCount) - 1; // 生成待领取档位的位掩码（低 starCount 位为 1）
        int takenMask = groupRewardRepository.find(playerId, groupId) // 查询已领取记录
                .map(ChallengeGroupRewardEntity::getTakenStars) // 提取已领取星级位图
                .orElse(0); // 无记录则掩码为 0

        int newMask = takenMask | claimMask; // 合并本次领取位到总掩码
        if (newMask == takenMask) { // 掩码未变说明该档已领过
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder() // 组装重复领取响应
                    .setRetcode(4) // retcode=4：该档奖励已领取
                    .setGroupId(groupId) // 回写组 ID
                    .setStarCount(starCount) // 回写星级档位
                    .addAllRewards(Collections.emptyList()) // 演示不发具体道具奖励
                    .setUpdatedTakenStars(takenMask) // 回写原掩码
                    .build(); // 完成响应构建
        }

        try { // 持久化领取掩码，失败则返回 retcode=5
            groupRewardRepository.updateTakenStars(playerId, groupId, newMask); // 写回合并后的领取掩码
        } catch (Exception e) { // 数据库写入失败
            log.warn("updateTakenStars failed, playerId={}, groupId={}", playerId, groupId, e); // 记录持久化异常
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder() // 组装持久化失败响应
                    .setRetcode(5) // retcode=5：数据库更新失败
                    .setGroupId(groupId) // 回写组 ID
                    .setStarCount(starCount) // 回写星级档位
                    .build(); // 完成响应构建
        }

        return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder() // 组装领取成功响应
                .setRetcode(0) // retcode=0：成功
                .setGroupId(groupId) // 回写组 ID
                .setStarCount(starCount) // 回写星级档位
                .addAllRewards(Collections.emptyList()) // 演示不发具体道具奖励
                .setUpdatedTakenStars(newMask) // 回写更新后的领取掩码
                .build(); // 完成响应构建
    }

    /**
     * 分页查询玩家挑战历史列表。
     */
    public ChallengeSystemProto.GetChallengeHistoryScRsp handleGetHistory(ChallengeSystemProto.GetChallengeHistoryCsReq req, // 处理查询挑战历史请求
                                                                         Channel channel) { // Netty 连接通道
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法查询
            return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        int groupId = (int) req.getGroupId(); // 挑战组 ID（0 表示不限组）
        int challengeId = (int) req.getChallengeId(); // 挑战关卡 ID（0 表示不限关卡）
        int page = req.getPage() <= 0 ? 1 : (int) req.getPage(); // 分页页码，默认第 1 页
        int pageSize = req.getPageSize() <= 0 ? 20 : (int) req.getPageSize(); // 每页条数，默认 20
        pageSize = Math.min(100, pageSize); // 限制单页最多 100 条防滥用
        int offset = (page - 1) * pageSize; // 计算 SQL 分页偏移量

        int total; // 历史总条数
        List<ChallengeHistoryEntity> list; // 当前页历史实体列表
        try { // 捕获数据库查询异常
            total = historyRepository.countHistory(playerId, groupId, challengeId); // 统计符合条件的总条数
            list = historyRepository.listHistory(playerId, groupId, challengeId, offset, pageSize); // 分页查询历史列表
        } catch (Exception e) { // 查询失败
            log.warn("getHistory failed, playerId={}, groupId={}, challengeId={}", playerId, groupId, challengeId, e); // 记录查询异常
            return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder().setRetcode(2).build(); // retcode=2：查询失败
        }

        List<ChallengeSystemProto.ChallengeHistoryInfo> infos = new ArrayList<>(); // 协议层历史信息列表
        for (ChallengeHistoryEntity h : list) { // 逐条将实体转为 Protobuf
            long completeSeconds = h.getUpdatedAt() == null ? 0L : (h.getUpdatedAt().getTime() / 1000L); // 完成时间转 Unix 秒
            infos.add(ChallengeSystemProto.ChallengeHistoryInfo.newBuilder() // 组装单条历史记录
                    .setChallengeId(h.getChallengeId()) // 写入挑战关卡 ID
                    .setGroupId(h.getGroupId()) // 写入挑战组 ID
                    .setStars(h.getStars()) // 写入星级位掩码
                    .setScore(h.getScore()) // 写入最高得分
                    .setCompleteTime(completeSeconds) // 写入完成时间戳
                    .build()); // 完成单条构建并加入列表
        }

        return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder() // 组装历史查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setTotalCount(total) // 写入总条数
                .addAllHistories(infos) // 写入当前页历史列表
                .build(); // 完成响应构建
    }

    /**
     * 查询某挑战组的领奖掩码与可用星数汇总。
     */
    public ChallengeSystemProto.GetChallengeGroupRewardScRsp handleGetGroupRewardState(ChallengeSystemProto.GetChallengeGroupRewardCsReq req, // 处理查询挑战组奖励状态请求
                                                                                      Channel channel) { // Netty 连接通道
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法查询
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(1).build(); // retcode=1：未登录
        }
        int groupId = (int) req.getGroupId(); // 挑战组 ID
        if (groupId <= 0) { // 组 ID 必须为正
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(2).setGroupId(groupId).build(); // retcode=2：组 ID 非法
        }

        int takenMask; // 已领取星级位掩码
        try { // 捕获数据库查询异常
            takenMask = groupRewardRepository.find(playerId, groupId).map(ChallengeGroupRewardEntity::getTakenStars).orElse(0); // 读取已领取位图，无记录为 0
        } catch (Exception e) { // 查询失败
            log.warn("load group reward failed, playerId={}, groupId={}", playerId, groupId, e); // 记录查询异常
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(3).setGroupId(groupId).build(); // retcode=3：加载失败
        }

        int availableStars = computeAvailableStars(playerId, groupId); // 统计组内可用总星数
        return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder() // 组装查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setGroupId(groupId) // 回写组 ID
                .setTakenStarsMask(takenMask) // 写入已领取掩码
                .setAvailableStars(availableStars) // 写入可用星数
                .build(); // 完成响应构建
    }

    /**
     * 从 Channel 读取当前玩家 ID；未登录返回 null。
     */
    private Integer getPlayerId(Channel channel) { // 解析 Channel 绑定的玩家 ID
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return null; // 未登录时返回 null
        }
        return (int) (uid.longValue() & 0xffffffffL); // uid 低 32 位映射为 playerId
    }

    /**
     * 统计玩家在某组已获得的星数总和（按历史位掩码 popcount 累加）。
     */
    private int computeAvailableStars(int playerId, int groupId) { // 计算组内可用总星数
        try { // 捕获数据库查询异常，失败时返回 0
            List<ChallengeHistoryEntity> histories = historyRepository.listAllInGroup(playerId, groupId); // 查询组内全部历史记录
            int total = 0; // 累计星数
            for (ChallengeHistoryEntity h : histories) { // 遍历每条历史
                total += Integer.bitCount(h.getStars()); // 单条记录达成星数（位掩码 popcount）累加
            }
            return Math.max(0, total); // 保证返回非负
        } catch (Exception e) { // 查询失败
            log.warn("computeAvailableStars failed, playerId={}, groupId={}", playerId, groupId, e); // 记录统计异常
            return 0; // 失败时视为 0 星
        }
    }

    /**
     * 由 challengeId 推导组 ID（演示规则）。
     */
    private static int deriveGroupId(int challengeId) { // 由 challengeId 推导挑战组 ID
        return (challengeId / 1000) * 100; // challengeId 千位取整 ×100 作为 groupId
    }

    /**
     * 由 challengeId 推导用于加载波次的 stageId。
     */
    private static int deriveStageId(int challengeId) { // 由 challengeId 映射 stageId
        return 1000 + challengeId; // 演示规则：stageId = 1000 + challengeId
    }

    /**
     * 将波次配置列表转换为协议 EnemyInfo（怪物列表可为空演示）。
     */
    private static List<BattleSystemProto.EnemyInfo> buildEnemyInfoFromWaves(List<BattleMonsterWaveRepository.WaveConfig> waves) { // 波次配置转 EnemyInfo
        // 现有波次表 monsters 字段为 json，本 demo 仅返回空怪物列表但保留波次数结构
        List<BattleSystemProto.EnemyInfo> list = new ArrayList<>(); // 协议敌方信息列表
        for (int i = 0; i < waves.size(); i++) { // 按波次序号逐条生成
            list.add(BattleSystemProto.EnemyInfo.newBuilder() // 组装单波 EnemyInfo
                    .setWaveIndex(i + 1) // 写入波次序号（协议从 1 开始）
                    .build()); // 完成单波构建（怪物列表留空）
        }
        return list; // 返回完整波次列表
    }
}
