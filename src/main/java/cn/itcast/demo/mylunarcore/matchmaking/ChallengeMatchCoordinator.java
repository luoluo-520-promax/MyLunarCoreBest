// 挑战入口协调器：统一「指定房间进战」与「自动匹配进战」两条路径的前置门禁
package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import org.springframework.stereotype.Service;

/**
 * 挑战入口协调器。
 * 不关心战斗如何开始，只校验：房间是否存在、归属是否正确、是否全员准备、匹配是否成局。
 * 失败时返回明确 retcode（10~13），便于客户端提示与重试。
 */
@Service // 注册为 Spring 单例 Bean，供挑战模块在进战前统一调用
public class ChallengeMatchCoordinator {

    /**
     * 挑战前门禁检查结果。
     *
     * @param ready  是否允许进入挑战流程
     * @param retcode 失败时的协议错误码；0 表示通过
     * @param roomId 最终应进入的房间 ID；非匹配模式或未成局时为 0
     */
    public record MatchGateResult(boolean ready, int retcode, long roomId) {}

    /** 匹配编排服务：负责玩家入队、凑齐人数并触发 RoomService 创建房间。 */
    private final MatchmakingService matchmakingService;
    /** 房间状态服务：查询玩家当前所属房间及其 status（1=等待准备，2=全员就绪可开战）。 */
    private final RoomService roomService;
    /** 会话管理器：在自动匹配路径下将玩家标记为 MATCHING，防止同一连接重复入队。 */
    private final GameSessionManager sessionManager;

    /**
     * 构造器注入匹配、房间、会话三个依赖。
     * Spring 容器启动时自动装配，保证协调器能访问匹配队列与房间快照。
     */
    public ChallengeMatchCoordinator(MatchmakingService matchmakingService,
                                     RoomService roomService,
                                     GameSessionManager sessionManager) {
        this.matchmakingService = matchmakingService; // 保存匹配编排引用，供自动匹配路径调用 joinQueue
        this.roomService = roomService;                 // 保存房间服务引用，供校验房间归属与准备状态
        this.sessionManager = sessionManager;           // 保存会话管理器，供入队前更新玩家会话状态
    }

    /**
     * 确认玩家是否可以进入挑战。
     *
     * @param useMatchmaking false 时跳过匹配（如单机/指定房间直连）
     * @param roomId         已指定房间 ID 时校验该房间；0 则走自动匹配
     */
    public MatchGateResult ensureReadyForChallenge(int playerId, boolean useMatchmaking, long roomId) {
        // 调用方声明不走匹配流程（例如单机副本或已在外部完成组队），直接放行且不绑定 roomId
        if (!useMatchmaking) {
            return new MatchGateResult(true, 0, 0); // ready=true 表示可进战；retcode=0 无错误；roomId=0 由上层自行决定
        }
        // 客户端已携带具体 roomId：走「指定房间进战」分支，不再触发自动匹配入队
        if (roomId > 0) {
            // 根据玩家 uid 反查其当前所属房间，验证是否真的在该房间而非伪造 roomId
            RoomService.Room room = roomService.findRoomByPlayer(playerId);
            // 房间不存在、roomId 与请求不一致、或 status≠2（未全员准备）均视为不可开战
            if (room == null || room.roomId() != roomId || room.status() != 2) {
                return new MatchGateResult(false, 10, roomId); // retcode=10：指定房间校验失败，客户端应提示重新准备或重进房间
            }
            return new MatchGateResult(true, 0, roomId); // 房间归属与准备状态均满足，允许以该 roomId 进入挑战
        }
        // 自动匹配路径：roomId=0 表示尚未指定房间，需先尝试入队并等待成局
        // 将在线会话状态置为 MATCHING，避免玩家在排队期间再次发起匹配或重复入队
        sessionManager.findByUid(playerId).ifPresent(s -> s.setSessionState(PlayerSessionState.MATCHING));
        // 以默认维度入队：mode=1（当前默认 PVP/挑战模式）、level=1、power=0；后续可改为请求参数驱动
        MatchmakingService.JoinResult join = matchmakingService.joinQueue(playerId, 1, 1, 0);
        // playerId 非法或入队逻辑失败时，无法继续匹配流程
        if (!join.success()) {
            return new MatchGateResult(false, 11, 0); // retcode=11：入队失败，客户端可提示稍后重试
        }
        // 入队后 MatchmakingService 若已凑齐 DEFAULT_TEAM_SIZE 人，RoomService 会同步创建房间并绑定 playerRoom
        RoomService.Room room = roomService.findRoomByPlayer(playerId);
        if (room == null) {
            // 队列人数不足或尚未轮到此玩家成局，仍在排队中
            return new MatchGateResult(false, 12, 0); // retcode=12：匹配中/未成局，客户端应展示排队 UI 并等待通知
        }
        if (room.status() != 2) {
            // 房间已创建但仍有成员未点击准备，status 仍为 1
            return new MatchGateResult(false, 13, room.roomId()); // retcode=13：已成局但未全员 ready，需等待准备完成
        }
        // 匹配成局且全员准备完毕，返回最终 roomId 供挑战模块加载战斗上下文
        return new MatchGateResult(true, 0, room.roomId());
    }
}
