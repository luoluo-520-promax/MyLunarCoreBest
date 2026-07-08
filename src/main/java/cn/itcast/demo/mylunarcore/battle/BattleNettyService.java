// 战斗 Netty 协议业务服务所在包
package cn.itcast.demo.mylunarcore.battle;

// 战斗主表持久化
import cn.itcast.demo.mylunarcore.repo.BattleRepository;
// 关卡波次配置
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
// Buff 叠层上限查询
import cn.itcast.demo.mylunarcore.repo.MazeBuffRepository;
// 技能行为链
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
// 技能主表
import cn.itcast.demo.mylunarcore.repo.MazeSkillRepository;
// 战局场景工厂
import cn.itcast.demo.mylunarcore.battle.BattleSceneFactory;
// 单场战斗内存状态
import cn.itcast.demo.mylunarcore.battle.BattleContext;
// 单波运行时
import cn.itcast.demo.mylunarcore.battle.WaveRuntime;
// 战局注册表
import cn.itcast.demo.mylunarcore.battle.BattleManager;
// 统计 JSON 工具
import cn.itcast.demo.mylunarcore.battle.BattleStatisticsUtil;
// 实体 HP/Buff 状态
import cn.itcast.demo.mylunarcore.battle.EntityState;
// 怪物实例
import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;
// 领域事件发布
import cn.itcast.demo.mylunarcore.common.GameEventPublisher;
// 战斗结束事件
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
// 战斗开始事件
import cn.itcast.demo.mylunarcore.common.BattleStartedEvent;
// 协议命令号
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 下行游戏包封装
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 战斗系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
// Netty 连接通道
import io.netty.channel.Channel;
// 项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类（战斗业务等）
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Channel 属性键（存玩家 uid）
import io.netty.util.AttributeKey;
// SLF4J 日志接口
import org.slf4j.Logger;
// Spring 服务 Bean
import org.springframework.stereotype.Service;

// SQL 时间戳类型
import java.sql.Timestamp;
// 可变数组列表
import java.util.ArrayList;
// 不可修改集合工厂方法
import java.util.Collections;
// 比较器：实体快照稳定排序
import java.util.Comparator;
// 列表接口
import java.util.List;

/**
 * 战斗协议业务入口：处理开战、技能释放、回合推进等 Netty 消息，协调 {@link cn.itcast.demo.mylunarcore.battle.BattleManager} 与迷宫技能仓储。
 */
@Service // 注册为 Spring Bean，作为战斗协议 Netty 消息入口
public class BattleNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleNettyService.class); // 绑定战斗业务分类的 SLF4J 日志记录器

    /** 从 Channel 属性中读取登录玩家 uid 时使用的键 */
    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid"); // 定义 Channel 属性键名 playerUid

    /** 进行中的战局注册表（内存） */
    private final BattleManager battleManager;
    /** 战斗主表 JDBC 访问 */
    private final BattleRepository battleRepository; // 战斗主表持久化访问
    /** 关卡波次配置读取 */
    private final BattleMonsterWaveRepository waveRepository; // 关卡怪物波次配置仓储
    /** 迷宫技能主表校验 */
    private final MazeSkillRepository skillRepository; // 迷宫技能主表仓储
    /** 技能行为链加载 */
    private final MazeSkillActionRepository skillActionRepository; // 技能行为链配置仓储
    /** Buff 叠层上限查询 */
    private final MazeBuffRepository buffRepository; // Buff 叠层上限配置仓储
    /** 战局运行时装配工厂 */
    private final BattleSceneFactory battleSceneFactory; // 战局运行时对象工厂
    /** 领域事件发布（开战/结束等） */
    private final GameEventPublisher gameEventPublisher; // 战斗领域事件发布器

    /**
     * 构造器注入战斗相关依赖。
     */
    public BattleNettyService(BattleManager battleManager, // 注入内存战局管理器
                               BattleRepository battleRepository, // 注入战斗主表仓储
                               BattleMonsterWaveRepository waveRepository, // 注入关卡波次配置仓储
                               MazeSkillRepository skillRepository, // 注入迷宫技能主表仓储
                               MazeSkillActionRepository skillActionRepository, // 注入技能行为链仓储
                               MazeBuffRepository buffRepository,
                               BattleSceneFactory battleSceneFactory, // 注入战局场景工厂
                               GameEventPublisher gameEventPublisher) { // 注入领域事件发布器
        this.battleManager = battleManager; // 构造器注入 battleManager
        this.battleRepository = battleRepository; // 构造器注入 battleRepository
        this.waveRepository = waveRepository; // 构造器注入 waveRepository
        this.skillRepository = skillRepository; // 构造器注入 skillRepository
        this.skillActionRepository = skillActionRepository; // 构造器注入 skillActionRepository
        this.buffRepository = buffRepository; // 构造器注入 buffRepository
        this.battleSceneFactory = battleSceneFactory; // 构造器注入 battleSceneFactory
        this.gameEventPublisher = gameEventPublisher; // 构造器注入 gameEventPublisher
    }

    /**
     * 开始战斗：创建 battle 记录、构建运行时上下文并返回首帧敌方信息。
     *
     * @param req 开战请求
     * @param channel 客户端连接
     * @return 开战响应；参数错误、DB 异常、波次配置缺失都会返回对应错误码
     */
    public BattleSystemProto.FightStartScRsp handleFightStart(BattleSystemProto.FightStartCsReq req, Channel channel) { // 处理开战请求
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return BattleSystemProto.FightStartScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 未登录，无法开战
                    .build();
        }
        if (req.getBattleStageId() <= 0 || req.getLineupId() <= 0) { // 关卡 ID 或阵容 ID 必须大于 0
            return BattleSystemProto.FightStartScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2) // 关卡或阵容 ID 非法
                    .build();
        }

        long nowSeconds = System.currentTimeMillis() / 1000L; // 记录开战时间戳（秒）
        int playerId = (int) (uid.longValue() & 0xffffffffL); // uid 低 32 位映射为 playerId

        long battleId; // 声明 battleId，在 try 块内由数据库赋值
        try { // 尝试执行可能失败的 I/O 或数据库操作
            battleId = battleRepository.insertBattle(playerId, (int) req.getLineupId(), (int) req.getBattleStageId(), new Timestamp(System.currentTimeMillis())); // 向 battle 表插入新战斗记录
        } catch (Exception e) { // 捕获异常并记录日志后返回错误码
            log.warn("insertBattle failed, playerId={}, stageId={}, lineupId={}", playerId, req.getBattleStageId(), req.getLineupId(), e); // 记录业务异常日志便于排查
            return BattleSystemProto.FightStartScRsp.newBuilder().setRetcode(3).build(); // 写库失败
        }

        List<BattleMonsterWaveRepository.WaveConfig> waves; // 声明波次配置列表，在 try 块内加载
        try { // 尝试执行可能失败的 I/O 或数据库操作
            waves = waveRepository.loadWavesByStageId((int) req.getBattleStageId()); // 按关卡 ID 加载怪物波次配置
        } catch (Exception e) { // 捕获异常并记录日志后返回错误码
            log.warn("loadWavesByStageId failed, stageId={}", req.getBattleStageId(), e); // 记录业务异常日志便于排查
            return BattleSystemProto.FightStartScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(4) // 关卡波次配置加载失败
                    .build();
        }

        BattleContext context = battleSceneFactory.createBattleScene(battleId, // 根据波次配置装配内存战局
                playerId, // 玩家 ID
                (int) req.getLineupId(),
                (int) req.getBattleStageId(),
                nowSeconds, // 开战时间戳（秒）
                waves); // 传入已加载的波次配置列表
        battleManager.put(context); // 注册战局到内存，供后续协议查找
        gameEventPublisher.publish(new BattleStartedEvent( // 发布战斗开始领域事件
                battleId, // 事件携带战斗 ID
                playerId, // 事件携带玩家 ID
                context.getBattleStageId(), // 事件携带关卡 ID
                context.getLineupId(), // 事件携带阵容 ID
                context.getWaveCount(), // 事件携带总波次数
                nowSeconds // 事件携带开战时间
        )); // 完成事件发布调用

        BattleSystemProto.StageInfo stageInfo = BattleSystemProto.StageInfo.newBuilder() // 构建关卡摘要信息
                .setId(context.getBattleStageId()) // 写入关卡/实体 ID
                .setWaveCount(context.getWaveCount()) // 写入总波次数
                .build();

        // 按波次组装敌方列表，供客户端渲染首帧怪物
        List<BattleSystemProto.EnemyInfo> enemyInfo = new ArrayList<>(); // 组装各波敌方信息列表
        for (int i = 0; i < context.getWaves().size(); i++) { // 遍历每一波怪物
            WaveRuntime wave = context.getWaves().get(i); // 取第 i 波运行时数据
            BattleSystemProto.EnemyInfo.Builder waveBuilder = BattleSystemProto.EnemyInfo.newBuilder() // 构建单波 EnemyInfo 消息
                    .setWaveIndex(i + 1); // 协议波次从 1 开始
            for (MonsterRuntime monster : wave.getMonsters()) { // 遍历该波中的每只怪物
                waveBuilder.addMonsters(BattleSystemProto.MonsterInfo.newBuilder() // 将怪物信息追加到该波列表
                        .setId(monster.getConfigMonsterId()) // 写入关卡/实体 ID
                        .setLevel(monster.getLevel()) // 写入怪物等级
                        .setHp(monster.getHp()) // 写入当前 HP
                        .setMaxHp(monster.getMaxHp()) // 写入最大 HP
                        .addAllBuffs(Collections.emptyList()) // 开战时尚无 Buff
                        .build());
            }
            enemyInfo.add(waveBuilder.build()); // 完成单波 EnemyInfo 并加入总列表
        }

        return BattleSystemProto.FightStartScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .setStageInfo(stageInfo) // 写入关卡摘要
                .addAllEnemyInfo(enemyInfo) // 写入各波敌方列表
                .setStartTime(nowSeconds) // 写入开战时间戳
                .build();
    }

    /**
     * 处理战斗行动请求（当前重点支持 actionType=1 的技能释放）。
     *
     * @param req 行动请求
     * @param channel 客户端连接
     * @return 行动结算响应；会校验战斗归属、战斗状态、技能合法性与目标合法性
     */
    public BattleSystemProto.FightActionScRsp handleFightAction(BattleSystemProto.FightActionCsReq req, Channel channel) { // 处理战斗行动（技能释放等）
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(6) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        long battleId = req.getBattleId(); // 从请求中读取战斗 ID
        BattleContext context = battleManager.get(battleId); // 按 battleId 查找内存战局
        if (context == null) { // 内存中找不到对应战局
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 战局不存在或已过期
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (!isBattleOwner(context, currentPlayerId)) { // 校验请求者是否为战局拥有者
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(7) // 非本人战局，拒绝操作
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (context.isEnded()) { // 战局已结束则拒绝继续操作
            return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2)
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        int actionType = (int) req.getActionType(); // 读取行动类型（1=技能）
        int skillId = (int) req.getSkillId(); // 读取技能 ID

        List<BattleSystemProto.ActionResult> results = new ArrayList<>(); // 收集各目标的行动结算结果
        // 以战局锁串行化行动结算，避免并发请求导致同一回合内状态覆盖或重复结算
        synchronized (context.getLock()) { // 加战局锁，串行化本回合状态变更
            if (actionType == 1) { // actionType=1 表示释放技能
                if (skillId <= 0) { // 技能 ID 必须大于 0
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(3) // 技能 ID 无效
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }
                if (skillRepository.findById(skillId) == null) { // 校验技能是否在配置表中存在
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(4) // 技能配置不存在
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }

                if (req.getTargetIdsCount() == 0) { // 技能释放必须指定至少一个目标
                    return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                            .setRetcode(5)
                            .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                            .build();
                }

                List<BattleContext.MazeSkillActionRuntime> actions = context.getResolvedActionsForSkill(skillId, skillActionRepository); // 解析技能对应的行为链配置

                for (Integer targetId : req.getTargetIdsList()) { // 逐个目标结算技能效果
                    EntityState entity = context.getEntity(targetId); // 在战局实体表中查找目标
                    if (entity == null) { // 目标实体不在当前战局中
                        // 目标不存在时仍返回占位结果，便于客户端对齐目标列表
                        results.add(BattleSystemProto.ActionResult.newBuilder() // 将单目标结算结果加入响应列表
                                .setTargetId(targetId) // 写入行动目标实体 ID
                                .setHpChange(0) // HP 变化为 0（无效果或目标不存在）
                                .setMpChange(0) // MP 变化为 0
                                .build());
                        continue; // 跳过当前循环迭代
                    }
                    int hpChangeTotal = 0; // 累计本目标 HP 变化量
                    List<Integer> addedBuffs = new ArrayList<>(); // 记录本目标新增的 Buff ID
                    List<Integer> removedBuffs = new ArrayList<>(); // 记录本目标移除的 Buff ID

                    for (BattleContext.MazeSkillActionRuntime action : actions) { // 按顺序执行技能行为链中的每个 action
                        switch (action.getActionType()) { // 按 action_type 分发到不同效果处理器
                            case 1: { // modify hp — 按配置修改生命值
                                int delta = action.tryParseHpDelta(); // 从配置 JSON 解析 HP 变化量
                                if (delta != 0 && !entity.isDead()) { // 仅对存活实体且变化量非零时修改 HP
                                    int before = entity.getHp(); // 记录修改前的 HP
                                    int after = Math.max(0, before + delta); // HP 不低于 0
                                    entity.setHp(after); // 写回实体当前 HP
                                    if (after == 0) { // HP 归零则标记死亡
                                        entity.setDead(true); // 标记实体为死亡状态
                                    }
                                    hpChangeTotal += (after - before); // 累加实际 HP 变化到结算结果
                                }
                                break; // 结束当前 action_type 分支
                            }
                            case 2: { // add buff — 叠加 Buff 层数
                                List<Integer> buffIds = action.tryParseBuffIds(); // 从配置 JSON 解析要添加的 Buff 列表
                                for (Integer buffId : buffIds) { // 逐个 Buff 尝试叠加到目标
                                    if (entity.isDead()) { // 已死亡实体跳过 Buff 叠加
                                        continue; // 跳过当前循环迭代
                                    }
                                    int maxStack = buffRepository.findMaxStack(buffId); // 查询该 Buff 的最大叠层数
                                    // 叠层上限在仓储层统一管理，避免协议层硬编码导致配置改动后逻辑失真
                                    int oldStacks = entity.getBuffStacks().getOrDefault(buffId, 0); // 读取目标当前 Buff 层数
                                    if (oldStacks < maxStack) { // 未达叠层上限时才继续叠加
                                        entity.addBuffStack(buffId, 1, maxStack); // 层数 +1，不超过 maxStack
                                        if (oldStacks == 0) { // 首次获得该 Buff 时记录到 addedBuffs
                                            addedBuffs.add(buffId); // 仅首次叠层时通知客户端新增
                                        }
                                    }
                                }
                                break; // 结束当前 action_type 分支
                            }
                            case 5: { // set death — 强制击杀
                                if (action.tryParseKillTrue()) { // 配置要求强制击杀
                                    if (!entity.isDead()) { // 仅对存活实体执行击杀
                                        int before = entity.getHp(); // 记录修改前的 HP
                                        entity.setHp(0); // 强制将 HP 置零
                                        entity.setDead(true); // 标记实体为死亡状态
                                        hpChangeTotal += -before; // 击杀时 HP 变化为负的全部当前 HP
                                    }
                                }
                                break; // 结束当前 action_type 分支
                            }
                            default: // 未实现的 action_type 走默认分支
                                // action_type 3/4/6+ 暂未实现，跳过
                                break; // 结束当前 action_type 分支
                        }
                    }

                    BattleSystemProto.ActionResult.Builder r = BattleSystemProto.ActionResult.newBuilder() // 构建单目标行动结果 Protobuf
                            .setTargetId(entity.getId()) // 写入行动目标实体 ID
                            .setHpChange(hpChangeTotal) // 写入本目标累计 HP 变化
                            .setMpChange(0); // 当前技能链不涉及 MP 变化

                    for (Integer b : addedBuffs) { // 将新增 Buff 写入 ActionResult
                        r.addAddedBuffs(b); // 追加单个新增 Buff ID
                    }
                    for (Integer b : removedBuffs) { // 将移除 Buff 写入 ActionResult
                        r.addRemovedBuffs(b); // 追加单个移除 Buff ID
                    }
                    results.add(r.build()); // 将单目标结算结果加入响应列表
                }
            } else { // 非技能类行动（actionType≠1）走空操作分支
                // action_type=2/3 等其它行动类型暂为空操作，仍返回零变化结果
                for (Integer targetId : req.getTargetIdsList()) { // 逐个目标结算技能效果
                    results.add(BattleSystemProto.ActionResult.newBuilder() // 将单目标结算结果加入响应列表
                            .setTargetId(targetId) // 写入行动目标实体 ID
                            .setHpChange(0) // HP 变化为 0（无效果或目标不存在）
                            .setMpChange(0) // MP 变化为 0
                            .build());
                }
            }

            // 波次切换放在本次行动结算末尾，确保客户端观察到「先结算伤害，再进下一波」的稳定时序
            boolean allDead = context.isAllMonstersDeadInWave(context.getCurrentWave()); // 判断当前波次怪物是否已全部死亡
            if (allDead && context.getCurrentWave() < context.getWaveCount()) { // 当前波怪物全灭且仍有后续波次
                int nextWave = context.getCurrentWave() + 1; // 计算下一波次序号
                context.switchToWave(nextWave); // 切换到下一波怪物

                BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
                BattleSystemProto.FightStateScNotify notify = BattleSystemProto.FightStateScNotify.newBuilder()
                        .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                        .setUpdateType(3) // updateType=3 表示波次推进
                        .setStateSnapshot(snapshot) // 写入状态快照到推送通知
                        .setExtraDataJson("{}") // 附加扩展 JSON（当前为空对象）
                        .build();
                channel.writeAndFlush(new GamePacket(CmdIds.FIGHT_STATE_SC_NOTIFY, notify.toByteArray())); // 主动推送战斗状态变更通知给客户端
            }

            // 无论行动类型是否造成伤害都推进回合计数，确保状态机前进可预测
            context.incrementTurn(); // 推进回合计数器
        }

        BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
        return BattleSystemProto.FightActionScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .addAllActionResults(results) // 写入各目标行动结算结果
                .setCurrentState(snapshot) // 写入战局当前快照
                .build();
    }

    /**
     * 上报战斗结果并结束战局。
     *
     * @param req 结果请求
     * @param channel 客户端连接
     * @return 结果响应；若 DB 更新失败返回错误码且不提前清理战局
     */
    public BattleSystemProto.FightResultScRsp handleFightResult(BattleSystemProto.FightResultCsReq req, Channel channel) { // 处理战斗结果上报
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.FightResultScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(4) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        long battleId = req.getBattleId(); // 从请求中读取战斗 ID
        BattleContext context = battleManager.get(battleId); // 按 battleId 查找内存战局
        if (context == null) { // 内存中找不到对应战局
            return BattleSystemProto.FightResultScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 战局不存在
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (!isBattleOwner(context, currentPlayerId)) { // 校验请求者是否为战局拥有者
            return BattleSystemProto.FightResultScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(5)
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        int endStatus = (int) req.getEndStatus(); // 读取客户端上报的结束状态码
        int playerExp = 0; // 演示环境暂不结算经验
        String statsJson = BattleStatisticsUtil.toJson(req.getStatistics()); // 将统计 Protobuf 序列化为 JSON 字符串

        try { // 尝试执行可能失败的 I/O 或数据库操作
            battleRepository.updateBattleResult(battleId, endStatus, statsJson, new Timestamp(System.currentTimeMillis())); // 持久化战斗结果到数据库
        } catch (Exception e) { // 捕获异常并记录日志后返回错误码
            log.warn("updateBattleResult failed, battleId={}, endStatus={}", battleId, endStatus, e); // 记录业务异常日志便于排查
            return BattleSystemProto.FightResultScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2) // 写库失败，保留内存战局供客户端重试
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        synchronized (context.getLock()) { // 加战局锁，串行化本回合状态变更
            context.setEnded(true); // 标记战局为已结束
        }
        battleManager.remove(battleId); // 从内存移除已结束的战局
        gameEventPublisher.publish(new BattleEndedEvent( // 发布战斗结束领域事件
                battleId, // 事件携带战斗 ID
                context.getPlayerId(), // 事件携带玩家 ID
                endStatus, // 事件携带结束状态码
                "RESULT", // 事件来源标识：正常结算
                System.currentTimeMillis() / 1000L // 事件携带结束时间戳（秒）
        )); // 完成事件发布调用

        return BattleSystemProto.FightResultScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .setPlayerExp(playerExp) // 写入经验奖励（演示为 0）
                .build();
    }

    /**
     * 主动退出战斗。
     *
     * @param req 退出请求
     * @param channel 客户端连接
     * @return 退出响应；成功后会清理运行时战局并广播战斗结束事件
     */
    public BattleSystemProto.FightQuitScRsp handleFightQuit(BattleSystemProto.FightQuitCsReq req, Channel channel) { // 处理主动退出战斗
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.FightQuitScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(3) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        long battleId = req.getBattleId(); // 从请求中读取战斗 ID
        BattleContext context = battleManager.get(battleId); // 按 battleId 查找内存战局
        if (context == null) { // 内存中找不到对应战局
            return BattleSystemProto.FightQuitScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 战局不存在
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (!isBattleOwner(context, currentPlayerId)) { // 校验请求者是否为战局拥有者
            return BattleSystemProto.FightQuitScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(4)
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        try { // 尝试执行可能失败的 I/O 或数据库操作
            battleRepository.updateBattleResult(battleId, 3, "{}", new Timestamp(System.currentTimeMillis())); // end_status=3 表示主动退出
        } catch (Exception e) { // 捕获异常并记录日志后返回错误码
            log.warn("updateBattleResult failed on quit, battleId={}", battleId, e); // 记录业务异常日志便于排查
            return BattleSystemProto.FightQuitScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2) // 写库失败
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        synchronized (context.getLock()) { // 加战局锁，串行化本回合状态变更
            context.setEnded(true); // 标记战局为已结束
        }
        battleManager.remove(battleId); // 从内存移除已结束的战局
        gameEventPublisher.publish(new BattleEndedEvent( // 发布战斗结束领域事件
                battleId, // 事件携带战斗 ID
                context.getPlayerId(), // 事件携带玩家 ID
                3, // end_status=3 表示主动退出
                "QUIT", // 事件来源标识：主动退出
                System.currentTimeMillis() / 1000L // 事件携带退出时间戳（秒）
        )); // 完成事件发布调用

        BattleSystemProto.Vec3 pos = BattleSystemProto.Vec3.newBuilder()
                .setX(0f) // 传送 X 坐标占位 0
                .setY(0f) // 传送 Y 坐标占位 0
                .setZ(0f) // 占位坐标，演示环境不做场景传送
                .build();

        return BattleSystemProto.FightQuitScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .setTeleportSceneId(0) // 0 表示不切换场景
                .setTeleportPos(pos) // 写入退出后的传送坐标
                .build();
    }

    /**
     * 查询战局快照。
     *
     * @param req 查询请求
     * @param channel 客户端连接
     * @return 战局信息响应；仅战斗拥有者可查询
     */
    public BattleSystemProto.GetBattleInfoScRsp handleGetBattleInfo(BattleSystemProto.GetBattleInfoCsReq req, Channel channel) { // 处理战局快照查询
        Integer currentPlayerId = getCurrentPlayerId(channel); // 解析当前连接绑定的 playerId
        if (currentPlayerId == null) { // playerId 为空表示未登录
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(2) // 未登录
                    .setBattleId(req.getBattleId()) // 回填战斗 ID 供客户端关联
                    .build();
        }
        long battleId = req.getBattleId(); // 从请求中读取战斗 ID
        BattleContext context = battleManager.get(battleId); // 按 battleId 查找内存战局
        if (context == null) { // 内存中找不到对应战局
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(1) // 战局不存在
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }
        if (!isBattleOwner(context, currentPlayerId)) { // 校验请求者是否为战局拥有者
            return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                    .setRetcode(3)
                    .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                    .build();
        }

        BattleSystemProto.StageInfo stageInfo = BattleSystemProto.StageInfo.newBuilder() // 构建关卡摘要信息
                .setId(context.getBattleStageId()) // 写入关卡/实体 ID
                .setWaveCount(context.getWaveCount()) // 写入总波次数
                .build();

        BattleSystemProto.CurrentState snapshot = buildCurrentState(context); // 将内存战局转换为协议快照
        return BattleSystemProto.GetBattleInfoScRsp.newBuilder() // 组装并返回战斗协议响应
                .setRetcode(0)
                .setBattleId(battleId) // 回填战斗 ID 供客户端关联
                .setStageInfo(stageInfo) // 写入关卡摘要
                .setCurrentState(snapshot) // 写入战局当前快照
                .setStartTime(context.getStartTimeSeconds()) // 写入开战时间戳
                .build();
    }

    /**
     * 将运行时上下文转换为协议快照。
     *
     * @param context 战斗上下文
     * @return 当前状态快照
     */
    private BattleSystemProto.CurrentState buildCurrentState(BattleContext context) { // 将内存战局转为 CurrentState 快照
        BattleSystemProto.CurrentState.Builder b = BattleSystemProto.CurrentState.newBuilder()
                .setTurn(context.getTurn()) // 写入当前回合数
                .setCurrentWave(context.getCurrentWave()); // 写入当前波次

        List<EntityState> entities = new ArrayList<>(context.getEntities().values()); // 拷贝战局实体集以便排序
        entities.sort(Comparator.comparingInt(EntityState::getId)); // 按 id 排序，保证客户端展示顺序稳定
        for (EntityState e : entities) { // 逐个实体写入快照
            BattleSystemProto.EntityState.Builder eb = BattleSystemProto.EntityState.newBuilder() // 构建单实体状态 Protobuf
                    .setId(e.getId()) // 写入关卡/实体 ID
                    .setHp(e.getHp()) // 写入当前 HP
                    .setDead(e.isDead()); // 写入死亡标记
            for (Integer buffId : e.getBuffStacks().keySet()) { // 遍历实体持有的 Buff
                if (e.getBuffStacks().getOrDefault(buffId, 0) > 0) { // 仅输出层数大于 0 的 Buff
                    eb.addBuffs(buffId); // 仅输出层数大于 0 的 Buff
                }
            }
            b.addEntities(eb.build()); // 将实体快照追加到 CurrentState
        }
        return b.build(); // 返回完整 CurrentState 快照
    }

    /**
     * 从连接属性读取当前玩家 id。
     *
     * @param channel 客户端连接
     * @return 玩家 id；未登录返回 null
     */
    private Integer getCurrentPlayerId(Channel channel) { // 从 Channel 解析当前玩家 ID
        Long uid = channel.attr(UID_KEY).get(); // 从 Channel 属性读取登录 uid
        if (uid == null) { // uid 为空表示未登录
            return null; // 未登录时返回 null
        }
        return (int) (uid.longValue() & 0xffffffffL); // uid 低 32 位映射为 playerId 并返回
    }

    /**
     * 校验当前请求是否来自战斗所属玩家。
     *
     * @param context 战斗上下文
     * @param currentPlayerId 当前玩家 id
     * @return true 表示归属匹配
     */
    private boolean isBattleOwner(BattleContext context, int currentPlayerId) { // 校验请求者是否为战局拥有者
        return context.getPlayerId() == currentPlayerId; // 比较战局 playerId 与请求者
    }
}
