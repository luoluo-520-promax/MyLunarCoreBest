// 模拟宇宙 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.rogue;

// 模拟宇宙系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.RogueSystemProto;
// 玩家 Rogue 全局数据实体
import cn.itcast.demo.mylunarcore.model.RoguePlayerDataEntity;
// Rogue 天赋实体
import cn.itcast.demo.mylunarcore.model.RogueTalentEntity;
// 玩家 Rogue 全局数据仓储
import cn.itcast.demo.mylunarcore.repo.RoguePlayerDataRepository;
// Rogue 天赋仓储
import cn.itcast.demo.mylunarcore.repo.RogueTalentRepository;
// 运行时管理器（分配/缓存 RogueRuntime）
import cn.itcast.demo.mylunarcore.rogue.RogueManager;
// 单局 Rogue 内存状态
import cn.itcast.demo.mylunarcore.rogue.RogueRuntime;
// Netty 客户端连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
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

/**
 * 模拟宇宙 Netty 业务：开局、移动、战斗、天赋与数据更新，协调 {@link RogueManager} 与 Rogue 相关仓储。
 */
@Service // Spring Bean：模拟宇宙 Netty 消息分发入口
public class RogueNettyService {

    // 本类日志记录器（Rogue 业务分类）
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ROGUE, RogueNettyService.class); // 绑定 Rogue 业务分类 SLF4J 日志

    private final RogueManager rogueManager;
    private final RoguePlayerDataRepository playerDataRepository;
    private final RogueTalentRepository talentRepository;
    private final PlayerContextResolver contextResolver;

    public RogueNettyService(RogueManager rogueManager,
                             RoguePlayerDataRepository playerDataRepository,
                             RogueTalentRepository talentRepository,
                             PlayerContextResolver contextResolver) {
        this.rogueManager = rogueManager;
        this.playerDataRepository = playerDataRepository;
        this.talentRepository = talentRepository;
        this.contextResolver = contextResolver;
    }

    /**
     * 开始一局 Rogue：校验参数、创建运行时并返回初始房间与祝福/奇物快照。
     */
    public RogueSystemProto.StartRogueScRsp handleStartRogue(RogueSystemProto.StartRogueCsReq req, Channel channel) { // 处理 Rogue 开局请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法开局
            return RogueSystemProto.StartRogueScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        int rogueId = (int) req.getRogueId(); // 读取 Rogue 玩法配置 ID
        int difficulty = (int) req.getDifficulty(); // 读取难度等级
        if (rogueId <= 0 || difficulty <= 0) { // rogueId 与 difficulty 必须为正
            return RogueSystemProto.StartRogueScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：rogueId 或 difficulty 非法
        }

        RogueRuntime rt = rogueManager.createOrReplace(playerId, rogueId, difficulty); // 创建或替换本玩家 Rogue 运行时
        return RogueSystemProto.StartRogueScRsp.newBuilder() // 组装开局成功响应
                .setRetcode(0) // retcode=0：成功
                .setRogueId(rogueId) // 回写 Rogue 配置 ID
                .setCurrentRoom(rt.currentRoomInfo()) // 写入当前房间信息
                .addAllBlessings(rt.getBlessings()) // 写入当前祝福列表
                .addAllMiracles(rt.miraclesList()) // 写入当前奇物列表
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .setFloor(rt.getFloor()) // 写入当前层数
                .setWave(rt.getWave()) // 写入当前波次
                .build(); // 完成响应构建
    }

    /**
     * 查询当前进行中的 Rogue 局详情（房间、祝福、奇物与状态）。
     */
    public RogueSystemProto.GetRogueInfoScRsp handleGetRogueInfo(RogueSystemProto.GetRogueInfoCsReq req, Channel channel) { // 处理查询 Rogue 局信息请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法查询
            return RogueSystemProto.GetRogueInfoScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.GetRogueInfoScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        RogueSystemProto.RogueRoomsMap roomsMap = RogueSystemProto.RogueRoomsMap.newBuilder() // 组装房间映射（演示仅含当前房间）
                .addRooms(rt.currentRoomInfo()) // 写入当前房间信息
                .build(); // 完成 RogueRoomsMap 构建

        return RogueSystemProto.GetRogueInfoScRsp.newBuilder() // 组装查询成功响应
                .setRetcode(0) // retcode=0：成功
                .setRogueId(rt.getRogueId()) // 回写 Rogue 配置 ID
                .setCurrentRoom(rt.currentRoomInfo()) // 写入当前房间信息
                .setRoomsMap(roomsMap) // 写入房间映射
                .addAllBlessings(rt.getBlessings()) // 写入祝福列表
                .addAllMiracles(rt.miraclesList()) // 写入奇物列表
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .setFloor(rt.getFloor()) // 写入当前层数
                .setWave(rt.getWave()) // 写入当前波次
                .setStatus(rt.getStatus()) // 写入局状态（进行中/已结束等）
                .build(); // 完成响应构建
    }

    /**
     * 移动到指定房间：校验 nextRoomId 并更新运行时位置。
     */
    public RogueSystemProto.RogueMoveScRsp handleMove(RogueSystemProto.RogueMoveCsReq req, Channel channel) { // 处理 Rogue 房间移动请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法移动
            return RogueSystemProto.RogueMoveScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueMoveScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        int nextRoomId = (int) req.getNextRoomId(); // 读取目标房间 ID
        if (nextRoomId <= 0) { // 目标房间 ID 必须为正
            return RogueSystemProto.RogueMoveScRsp.newBuilder()
                    .setRetcode(3)
                    .build(); // retcode=3：nextRoomId 非法
        }
        rt.moveToRoom(nextRoomId); // 更新运行时当前房间

        return RogueSystemProto.RogueMoveScRsp.newBuilder() // 组装移动成功响应
                .setRetcode(0) // retcode=0：成功
                .setRoomInfo(rt.currentRoomInfo()) // 写入移动后的房间信息
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .build(); // 完成响应构建
    }

    /**
     * 选择祝福：校验 blessingId、写入运行时并增加得分。
     */
    public RogueSystemProto.RogueSelectBlessingScRsp handleSelectBlessing(RogueSystemProto.RogueSelectBlessingCsReq req, Channel channel) { // 处理选择祝福请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法选祝福
            return RogueSystemProto.RogueSelectBlessingScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueSelectBlessingScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        int blessingId = (int) req.getBlessingId(); // 读取祝福配置 ID
        if (blessingId <= 0) { // blessingId 必须为正
            return RogueSystemProto.RogueSelectBlessingScRsp.newBuilder()
                    .setRetcode(3)
                    .setBlessingId(blessingId)
                    .build(); // retcode=3：blessingId 非法
        }
        rt.addBlessing(blessingId); // 将祝福加入运行时
        rt.addScore(10); // 选祝福奖励 +10 分

        return RogueSystemProto.RogueSelectBlessingScRsp.newBuilder() // 组装选祝福成功响应
                .setRetcode(0) // retcode=0：成功
                .setBlessingId(blessingId) // 回写所选祝福 ID
                .addAllNewBlessings(rt.getBlessings()) // 写入更新后的祝福列表
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .build(); // 完成响应构建
    }

    /**
     * 选择奇物：校验 miracleId、写入运行时并尝试持久化全局解锁列表。
     */
    public RogueSystemProto.RogueSelectMiracleScRsp handleSelectMiracle(RogueSystemProto.RogueSelectMiracleCsReq req, Channel channel) { // 处理选择奇物请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法选奇物
            return RogueSystemProto.RogueSelectMiracleScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueSelectMiracleScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        int miracleId = (int) req.getMiracleId(); // 读取奇物配置 ID
        if (miracleId <= 0) { // miracleId 必须为正
            return RogueSystemProto.RogueSelectMiracleScRsp.newBuilder()
                    .setRetcode(3)
                    .setMiracleId(miracleId)
                    .build(); // retcode=3：miracleId 非法
        }
        rt.addMiracle(miracleId); // 将奇物加入运行时
        rt.addScore(20); // 选奇物奖励 +20 分

        // 全局解锁奇物：演示实现仅在字段为空时写入单元素 JSON 数组，未做数组合并
        try { // 捕获数据库读写异常，失败不影响本局选奇物成功
            RoguePlayerDataEntity pd = playerDataRepository.loadOrCreate(playerId); // 加载或创建玩家全局数据
            if (pd != null && (pd.getUnlockedMiraclesJson() == null || pd.getUnlockedMiraclesJson().trim().isEmpty())) { // 解锁列表尚未初始化
                playerDataRepository.updateUnlockedMiraclesJson(playerId, "[" + miracleId + "]"); // 写入首个已解锁奇物 ID
            }
        } catch (Exception e) { // 持久化解锁列表失败
            log.warn("update unlocked miracles failed, playerId={}, miracleId={}", playerId, miracleId, e); // 记录持久化异常便于排查
        }

        return RogueSystemProto.RogueSelectMiracleScRsp.newBuilder() // 组装选奇物成功响应
                .setRetcode(0) // retcode=0：成功
                .setMiracleId(miracleId) // 回写所选奇物 ID
                .addAllNewMiracles(rt.miraclesList()) // 写入更新后的奇物列表
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .build(); // 完成响应构建
    }

    /**
     * 上报 Rogue 战斗结果：胜利发放奖励，失败则结束本局。
     */
    public RogueSystemProto.RogueBattleResultScRsp handleBattleResult(RogueSystemProto.RogueBattleResultCsReq req, Channel channel) { // 处理 Rogue 战斗结果请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法上报
            return RogueSystemProto.RogueBattleResultScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueBattleResultScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        if (req.getRogueId() != rt.getRogueId()) { // 请求 rogueId 与当前局不一致
            return RogueSystemProto.RogueBattleResultScRsp.newBuilder()
                    .setRetcode(3)
                    .setRogueId(req.getRogueId())
                    .build(); // retcode=3：请求 rogueId 与当前局不匹配
        }
        boolean win = req.getResult() == 1; // result=1 表示战斗胜利
        if (win) { // 战斗胜利分支：合并祝福并发放货币与得分
            for (Integer bid : req.getBlessingsObtainedList()) { // 遍历客户端上报的获得祝福
                if (bid != null && bid > 0) { // 跳过空或非法祝福 ID
                    rt.addBlessing(bid); // 将祝福加入运行时
                }
            }
            rt.addCurrency(50); // 胜利奖励 +50 虚拟货币
            rt.addScore(200); // 胜利奖励 +200 分
        } else { // 战斗失败分支：结束本局
            rt.quit(); // 标记运行时退出
        }

        return RogueSystemProto.RogueBattleResultScRsp.newBuilder() // 组装战斗结果响应
                .setRetcode(0) // retcode=0：成功
                .setRogueId(rt.getRogueId()) // 回写 Rogue 配置 ID
                .setIsWin(win) // 回写是否胜利
                .addAllNewBlessings(win ? rt.getBlessings() : Collections.emptyList()) // 胜利时回写祝福列表，失败为空
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .addAllRewards(Collections.emptyList()) // 演示实现：不发具体道具奖励
                .setIsRunEnd(!win) // 失败时标记本局结束
                .build(); // 完成响应构建
    }

    /**
     * 处理 Rogue 房间事件选项：演示实现统一发放 +20 虚拟货币。
     */
    public RogueSystemProto.RogueEventScRsp handleEvent(RogueSystemProto.RogueEventCsReq req, Channel channel) { // 处理 Rogue 房间事件请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法处理事件
            return RogueSystemProto.RogueEventScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueEventScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        int roomId = (int) req.getRoomId(); // 读取事件所在房间 ID
        int optionId = (int) req.getOptionId(); // 读取玩家选择的选项 ID
        if (roomId <= 0 || optionId <= 0) { // 房间或选项 ID 必须为正
            return RogueSystemProto.RogueEventScRsp.newBuilder()
                    .setRetcode(3)
                    .setRoomId(roomId)
                    .build(); // retcode=3：roomId 或 optionId 非法
        }

        // 简化：事件选项统一给 +20 虚拟货币
        rt.addCurrency(20); // 发放事件奖励货币
        RogueSystemProto.RogueEventResult result = RogueSystemProto.RogueEventResult.newBuilder() // 组装事件结果摘要
                .setType(3) // 结果类型 3：货币变更
                .setCurrencyChange(20) // 写入货币变更量 +20
                .build(); // 完成 RogueEventResult 构建

        return RogueSystemProto.RogueEventScRsp.newBuilder() // 组装事件处理成功响应
                .setRetcode(0) // retcode=0：成功
                .setRoomId(roomId) // 回写房间 ID
                .setEventResult(result) // 写入事件结果
                .addAllUpdatedBlessings(rt.getBlessings()) // 写入更新后的祝福列表
                .addAllUpdatedMiracles(rt.miraclesList()) // 写入更新后的奇物列表
                .setVirtualCurrency(rt.getVirtualCurrency()) // 写入虚拟货币余额
                .setScore(rt.getScore()) // 写入当前得分
                .build(); // 完成响应构建
    }

    /**
     * 主动退出 Rogue 局：移除运行时并持久化局末统计。
     */
    public RogueSystemProto.RogueQuitScRsp handleQuit(RogueSystemProto.RogueQuitCsReq req, Channel channel) { // 处理 Rogue 退出请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法退出
            return RogueSystemProto.RogueQuitScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RogueRuntime rt = rogueManager.get(playerId); // 按 playerId 查找进行中的 Rogue 运行时
        if (rt == null) { // 无进行中的 Rogue 运行时
            return RogueSystemProto.RogueQuitScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：无进行中的 Rogue 局
        }
        rt.quit(); // 标记运行时退出
        rogueManager.remove(playerId); // 从内存索引移除本局

        try { // 捕获数据库写入异常，失败不影响退出响应
            playerDataRepository.applyRunEndStats(playerId, rt.getFloor(), rt.getScore(), false); // 持久化局末层数、得分（非通关完成）
        } catch (Exception e) { // 持久化局末统计失败
            log.warn("applyRunEndStats failed on quit, playerId={}", playerId, e); // 记录持久化异常便于排查
        }
        RoguePlayerDataEntity pd = playerDataRepository.loadOrCreate(playerId); // 重新加载玩家全局数据用于响应
        RogueSystemProto.RoguePlayerDataUpdate upd = RogueSystemProto.RoguePlayerDataUpdate.newBuilder() // 组装玩家数据更新摘要
                .setHighestFloor(pd == null ? 0 : pd.getHighestFloor()) // 写入历史最高层数
                .setTotalScore(pd == null ? 0L : pd.getTotalScore()) // 写入累计总得分
                .setCompletedRuns(pd == null ? 0 : pd.getCompletedRuns()) // 写入完成局数
                .build(); // 完成 RoguePlayerDataUpdate 构建

        return RogueSystemProto.RogueQuitScRsp.newBuilder() // 组装退出成功响应
                .setRetcode(0) // retcode=0：成功
                .setFinalScore(rt.getScore()) // 回写本局最终得分
                .setFinalFloor(rt.getFloor()) // 回写本局最终层数
                .addAllRewards(Collections.emptyList()) // 演示实现：不发具体道具奖励
                .setPlayerDataUpdate(upd) // 写入玩家全局数据更新摘要
                .build(); // 完成响应构建
    }

    /**
     * 查询 Rogue 全局数据：天赋列表、已解锁奇物与累计统计。
     */
    public RogueSystemProto.GetRogueGlobalInfoScRsp handleGetGlobalInfo(RogueSystemProto.GetRogueGlobalInfoCsReq req, Channel channel) { // 处理查询 Rogue 全局数据请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法查询
            return RogueSystemProto.GetRogueGlobalInfoScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        RoguePlayerDataEntity pd; // 玩家 Rogue 全局数据实体
        try { // 捕获数据库加载异常
            pd = playerDataRepository.loadOrCreate(playerId); // 加载或创建玩家全局数据
        } catch (Exception e) { // 加载失败
            log.warn("loadOrCreate rogue_player_data failed, playerId={}", playerId, e); // 记录加载异常便于排查
            return RogueSystemProto.GetRogueGlobalInfoScRsp.newBuilder()
                    .setRetcode(2)
                    .build(); // retcode=2：加载玩家 Rogue 数据失败
        }

        List<RogueSystemProto.RogueTalent> talents = new ArrayList<>(); // 协议层天赋列表
        try { // 捕获天赋查询异常，失败时仍返回其余全局数据
            List<RogueTalentEntity> talentEntities = talentRepository.listByPlayerId(playerId); // 查询玩家全部天赋记录
            for (RogueTalentEntity te : talentEntities) { // 逐条将实体转为 Protobuf
                talents.add(RogueSystemProto.RogueTalent.newBuilder() // 组装单条天赋信息
                        .setId(te.getTalentId()) // 写入天赋配置 ID
                        .setLevel(te.getLevel()) // 写入天赋当前等级
                        .setActivated(te.isActivated()) // 写入是否已激活
                        .build()); // 完成单条构建并加入列表
            }
        } catch (Exception e) { // 天赋加载失败
            log.warn("load rogue_talent failed, playerId={}", playerId, e); // 记录查询异常便于排查
        }

        String unlockedMiraclesJson = pd == null ? null : pd.getUnlockedMiraclesJson(); // 读取已解锁奇物 JSON 字符串
        List<Integer> unlockedMiracles = parseIntArrayJson(unlockedMiraclesJson); // 解析为整数 ID 列表

        int selectedPath = pd == null || pd.getSelectedPath() == null ? 0 : pd.getSelectedPath(); // 当前所选路径 ID
        int completedRuns = pd == null ? 0 : pd.getCompletedRuns(); // 累计完成局数
        int highestFloor = pd == null ? 0 : pd.getHighestFloor(); // 历史最高层数
        long totalScore = pd == null ? 0L : pd.getTotalScore(); // 累计总得分

        return RogueSystemProto.GetRogueGlobalInfoScRsp.newBuilder() // 组装全局数据查询成功响应
                .setRetcode(0) // retcode=0：成功
                .addAllTalents(talents) // 写入天赋列表
                .addAllUnlockedMiracles(unlockedMiracles) // 写入已解锁奇物 ID 列表
                .setSelectedPath(selectedPath) // 写入所选路径
                .setCompletedRuns(completedRuns) // 写入完成局数
                .setHighestFloor(highestFloor) // 写入历史最高层数
                .setTotalScore(totalScore) // 写入累计总得分
                .build(); // 完成响应构建
    }

    /**
     * 升级 Rogue 天赋：校验 talentId 与目标等级并持久化。
     */
    public RogueSystemProto.RogueUpgradeTalentScRsp handleUpgradeTalent(RogueSystemProto.RogueUpgradeTalentCsReq req, Channel channel) { // 处理 Rogue 天赋升级请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法升级
            return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }

        int talentId = (int) req.getTalentId(); // 读取天赋配置 ID
        int upgradeToLevel = (int) req.getUpgradeToLevel(); // 读取目标升级等级
        if (talentId <= 0) { // talentId 必须为正
            return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder() // 组装 talentId 非法响应
                    .setRetcode(2) // retcode=2：talentId 非法
                    .setTalentId(talentId) // 回写天赋 ID
                    .setNewLevel(0) // 等级未变更
                    .setActivated(false) // 激活状态未变更
                    .build(); // 完成响应构建
        }
        if (upgradeToLevel <= 0 || upgradeToLevel > 3) { // 天赋等级允许范围 1~3
            return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder() // 组装等级范围非法响应
                    .setRetcode(3) // retcode=3：upgradeToLevel 超出允许范围 1~3
                    .setTalentId(talentId) // 回写天赋 ID
                    .setNewLevel(0) // 等级未变更
                    .setActivated(false) // 激活状态未变更
                    .build(); // 完成响应构建
        }

        try { // 捕获数据库升级异常
            RogueTalentEntity updated = talentRepository.upgradeTalent(playerId, talentId, upgradeToLevel); // 执行天赋升级并返回更新后实体
            if (updated == null) { // 天赋记录不存在或无法升级
                return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder() // 组装升级失败响应
                        .setRetcode(4) // retcode=4：天赋记录不存在或无法升级
                        .setTalentId(talentId) // 回写天赋 ID
                        .setNewLevel(0) // 等级未变更
                        .setActivated(false) // 激活状态未变更
                        .build(); // 完成响应构建
            }
            return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder() // 组装升级成功响应
                    .setRetcode(0) // retcode=0：成功
                    .setTalentId(talentId) // 回写天赋 ID
                    .setNewLevel(updated.getLevel()) // 回写升级后等级
                    .setActivated(updated.isActivated()) // 回写激活状态
                    .build(); // 完成响应构建
        } catch (Exception e) { // 数据库升级失败
            log.warn("upgrade talent failed, playerId={}, talentId={}, upgradeToLevel={}", playerId, talentId, upgradeToLevel, e); // 记录升级异常便于排查
            return RogueSystemProto.RogueUpgradeTalentScRsp.newBuilder() // 组装数据库失败响应
                    .setRetcode(5) // retcode=5：数据库升级天赋失败
                    .setTalentId(talentId) // 回写天赋 ID
                    .setNewLevel(0) // 等级未变更
                    .setActivated(false) // 激活状态未变更
                    .build(); // 完成响应构建
        }
    }

    /**
     * 将 JSON 整数数组字符串解析为 List&lt;Integer&gt;（兼容 ["1","2"] 与 [1,2] 写法）。
     */
    private List<Integer> parseIntArrayJson(String json) { // 解析 JSON 整数数组为 List
        if (json == null) { // null 视为无数据
            return Collections.emptyList(); // 返回空列表
        }
        String s = json.trim(); // 去除首尾空白
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) { // 空串或字面量 null
            return Collections.emptyList(); // 返回空列表
        }
        if (s.startsWith("[")) { // 去掉左方括号
            s = s.substring(1);
        }
        if (s.endsWith("]")) { // 去掉右方括号
            s = s.substring(0, s.length() - 1);
        }
        s = s.trim(); // 再次去除空白
        if (s.isEmpty()) { // 括号内无内容
            return Collections.emptyList(); // 返回空列表
        }

        String[] parts = s.split(","); // 按逗号分割各元素
        List<Integer> out = new ArrayList<>(parts.length); // 预分配结果列表容量
        for (String p : parts) { // 逐段解析整数
            String part = p.trim(); // 去除元素首尾空白
            if (part.isEmpty()) { // 跳过空段
                continue; // 继续下一元素
            }
            // 兼容 ["1", "2"] 或 [1,2] 这两种写法
            part = part.replace("\"", ""); // 去掉元素两侧引号
            try { // 尝试解析为整数
                out.add(Integer.parseInt(part)); // 解析成功则加入结果列表
            } catch (Exception ignore) { // 单个元素非法则跳过
                // 忽略无法解析的元素
            }
        }
        return out; // 返回解析后的整数列表
    }

    /**
     * 选择 Rogue 路径并持久化到玩家全局数据。
     */
    public RogueSystemProto.RogueSelectPathScRsp handleSelectPath(RogueSystemProto.RogueSelectPathCsReq req, Channel channel) { // 处理选择 Rogue 路径请求
        Integer playerId = getPlayerId(channel); // 从 Channel 解析当前玩家 ID
        if (playerId == null) { // 未登录则无法选路径
            return RogueSystemProto.RogueSelectPathScRsp.newBuilder()
                    .setRetcode(1)
                    .build(); // retcode=1：未登录
        }
        int pathId = (int) req.getPathId(); // 读取路径配置 ID
        if (pathId <= 0) { // pathId 必须为正
            return RogueSystemProto.RogueSelectPathScRsp.newBuilder()
                    .setRetcode(2)
                    .setPathId(pathId)
                    .build(); // retcode=2：pathId 非法
        }
        try { // 捕获数据库写入异常
            playerDataRepository.updateSelectedPath(playerId, pathId); // 持久化所选路径
        } catch (Exception e) { // 持久化失败
            log.warn("updateSelectedPath failed, playerId={}, pathId={}", playerId, pathId, e); // 记录持久化异常便于排查
            return RogueSystemProto.RogueSelectPathScRsp.newBuilder()
                    .setRetcode(3)
                    .setPathId(pathId)
                    .build(); // retcode=3：持久化所选路径失败
        }
        return RogueSystemProto.RogueSelectPathScRsp.newBuilder() // 组装选路径成功响应
                .setRetcode(0) // retcode=0：成功
                .setPathId(pathId) // 回写所选路径 ID
                .build(); // 完成响应构建
    }

    /**
     * 从 Channel 读取当前玩家 ID；未登录返回 null。
     */
    private Integer getPlayerId(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        return playerId <= 0 ? null : playerId;
    }
}
